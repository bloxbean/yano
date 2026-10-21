package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.view.Lookup;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: preprod {@code 96ae78f7…}, accepted by the chain, whose output 1 has a value of exactly
 * {@code maxValSize} = 5000 bytes as the ledger measures it (a 324-asset map is indefinite-length above 23 entries,
 * cardano-ledger-binary Encoder.hs:432-443) and 5001 as a definite-length encoding. Scalus 1.1.1 measures the
 * definite-length encoding; the bridge's {@code YanoOutputValueSizeValidator} measures Haskell's.
 */
class ScalusOutputValueSizeTest {

    private final ScalusLedgerValidationEngine engine = new ScalusLedgerValidationEngine(null);

    @Test
    void aValueOfExactlyMaxValSizeWithAnIndefiniteLengthAssetMapIsValid() {
        TxValidationOutcome outcome = engine.validate(bundle("5000").replayRequest());
        assertThat(outcome).as(String.valueOf(outcome)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    @Test
    void theSameValueIsTooBigForAMaxValSizeOfOneByteLess() {
        TxValidationOutcome outcome = engine.validate(bundle("4999").replayRequest());
        assertThat(outcome).as(String.valueOf(outcome)).isInstanceOf(TxValidationOutcome.Invalid.class);
        assertThat(((TxValidationOutcome.Invalid) outcome).failures())
                .extracting(LedgerFailure::qualifiedName).containsExactly("UTXO.OutputTooBigUTxO");
    }

    /** @return the bundle with {@code maxValSize} set in its recorded parameters (what its replay view answers) */
    private static ShadowDumpBundle bundle(String maxValSize) {
        ShadowDumpBundle bundle = PublicNetworkTransactions.bundle(
                PublicNetworkTransactions.PREPROD_INDEFINITE_ASSET_MAP_OUTPUT);
        ((Lookup.Present<ProtocolParams>) bundle.replayView().protocolParams()).value().setMaxValSize(maxValSize);
        return bundle;
    }
}
