package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.spec.NetworkId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: preprod {@code 96ae78f7…}, accepted by the chain, whose output 1 has a value of exactly
 * {@code maxValSize} = 5000 bytes as the ledger measures it (a 324-asset map is indefinite-length above 23 entries,
 * cardano-ledger-binary Encoder.hs:432-443) and 5001 as a definite-length encoding. Amaru counts the ledger's way
 * ({@code inherent_value.rs}); this guards a future Amaru pin against regressing.
 */
class AmaruOutputValueSizeTest {

    /** Preprod's genesis (magic 1, Byron until slot 86400, system start 2022-06-01T00:00:00Z). */
    private static final NetworkParameters PREPROD = new NetworkParameters(AmaruNetworks.PREPROD_MAGIC,
            NetworkId.TESTNET, 2160, 0.05, BigInteger.valueOf(45_000_000_000_000_000L), 129_600, 62,
            1_654_041_600_000L, 21_600, 20_000, 86_400, 432_000, 1000);

    private final AmaruTransactionValidator engine = new AmaruTransactionValidator(
            LedgerValidationEngines.AMARU_SCALUS, AmaruEngineConfig.defaults(1), AmaruNetworks.from(PREPROD),
            new ScalusScriptPhaseEvaluator(), AmaruLedgerConstants.HASKELL, ScenarioSupport.wasm());

    @AfterEach
    void close() {
        engine.close();
    }

    @Test
    void aValueOfExactlyMaxValSizeWithAnIndefiniteLengthAssetMapIsValid() {
        TxValidationOutcome outcome = validate("5000");
        assertThat(outcome).as(String.valueOf(outcome)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    @Test
    void theSameValueIsTooBigForAMaxValSizeOfOneByteLess() {
        TxValidationOutcome outcome = validate("4999");
        assertThat(outcome).as(String.valueOf(outcome)).isInstanceOf(TxValidationOutcome.Invalid.class);
        assertThat(((TxValidationOutcome.Invalid) outcome).failures())
                .extracting(LedgerFailure::qualifiedName).containsExactly("UTXO.OutputTooBigUTxO");
    }

    /**
     * Validates the bundle's transaction with {@code maxValSize} set. The bundle holds the reads the java engine made:
     * the parameters and the two spent UTxOs. Amaru also reads the committee and other governance state, which this
     * transaction (no certificates, votes or proposals) does not touch, so the view holds the recorded reads and
     * nothing else.
     */
    private TxValidationOutcome validate(String maxValSize) {
        ShadowDumpBundle bundle = PublicNetworkTransactions.bundle(
                PublicNetworkTransactions.PREPROD_INDEFINITE_ASSET_MAP_OUTPUT);
        InMemoryLedgerView.Builder view = InMemoryLedgerView.builder();
        for (RecordingLedgerView.Read read : bundle.reads()) {
            switch (read.result()) {
                case Lookup.Present<?>(UtxoEntry utxo) -> view.utxo(utxo);
                case Lookup.Present<?>(ProtocolParams params) -> {
                    params.setMaxValSize(maxValSize);
                    view.protocolParams(params);
                }
                default -> throw new IllegalStateException("unexpected read " + read);
            }
        }
        return engine.validate(new TxValidationRequest(bundle.txCbor(), view.build(), bundle.env(), bundle.rule(),
                bundle.origin(), null));
    }
}
