package org.yanoproject.ledger.scripteval.phase2;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.tx.CclTransactions;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxow.PlutusScriptDecoder;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions.Phase2Case;
import org.yanoproject.ledger.rules.phase2.ScriptCollection;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: the chain-valid preprod and preview transactions of {@link PublicNetworkTransactions#PHASE2_CASES}
 * through the {@code java-julc} engine. Each validates as on chain, except the known julc deviation below.
 */
class JulcPublicNetworkTest {

    /**
     * julc 0.1.0-pre17 fails {@code verifyEcdsaSecp256k1Signature} for r or s = 0, which libsecp256k1 accepts and
     * Plutus answers {@code False} for (julc PR #219). {@link #zeroSignatureComponentIsTheKnownJulcDeviation} fails once
     * the catalog moves to a fixed julc; then remove this case from the exclusion.
     */
    private static final String SECP256K1_ZERO_SIGNATURE =
            "preprod-031e36a7435259b33cb3b55eebc90d44a4f58efeef1322b929d7d00518cdd732";

    private final JavaLedgerValidationEngine engine = new JavaLedgerValidationEngine(new JulcScriptPhaseEvaluator());

    @TestFactory
    Stream<DynamicTest> chainValidTransactionsValidate() {
        return PublicNetworkTransactions.PHASE2_CASES.stream()
                .filter(c -> !c.name().equals(SECP256K1_ZERO_SIGNATURE))
                .map(c -> DynamicTest.dynamicTest(c.toString(), () -> {
                    assertThat(c.bundle().txHash()).isEqualTo(c.txId());
                    TxValidationOutcome outcome = engine.validate(c.bundle().replayRequest());
                    assertThat(outcome).as(c + ": " + outcome).isInstanceOf(TxValidationOutcome.Valid.class);
                }));
    }

    @Test
    void zeroSignatureComponentIsTheKnownJulcDeviation() {
        Phase2Case secp = PublicNetworkTransactions.PHASE2_CASES.stream()
                .filter(c -> c.name().equals(SECP256K1_ZERO_SIGNATURE)).findFirst().orElseThrow();
        TxValidationOutcome outcome = engine.validate(secp.bundle().replayRequest());
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        assertThat(((TxValidationOutcome.Invalid) outcome).failures().getFirst().detail())
                .contains("VerifyEcdsaSecp256k1Signature: r or s out of range");
    }

    /**
     * Preprod {@code 8e4b1ced…} has outputs holding 14999999995627669111 tokens, a Word64 above 2^63: every integer is a
     * {@link BigInteger} in the script context, so the context holds the exact quantity.
     */
    @Test
    void word64QuantitiesAboveTwoToTheSixtyThreeAreCarriedExactly() throws Exception {
        Phase2Case c = PublicNetworkTransactions.PHASE2_CASES.stream()
                .filter(x -> x.name().startsWith("preprod-8e4b1ced")).findFirst().orElseThrow();
        TxValidationRequest request = c.bundle().replayRequest();
        byte[] txCbor = request.txCbor();
        RawTransaction raw = RawTransaction.parse(txCbor, CclTransactions.deserialize(txCbor));
        Map<Outpoint, UtxoEntry> resolved = new HashMap<>();
        for (TxInRef in : raw.allInputs()) {
            if (request.view().utxo(in.outpoint()) instanceof Lookup.Present<UtxoEntry> present) {
                resolved.put(present.value().outpoint(), present.value());
            }
        }
        int major = request.env().protocolMajor();
        ConwayTxInfoTranslator translator = new ConwayTxInfoTranslator(raw, resolved, major,
                request.env().slotConfig());
        byte[] quantity = HexFormat.of().parseHex("1b" + new BigInteger("14999999995627669111").toString(16));
        boolean found = false;
        for (int language = 1; language <= 3; language++) {
            if (ScriptCollection.translationError(raw.decoded(), resolved, major, language, -1).isEmpty()) {
                byte[] info = PlutusDataCborEncoder.encode(translator.txInfo(language));
                found |= Collections.indexOfSubList(toList(info), toList(quantity)) >= 0;
            }
        }
        assertThat(found).as("a TxInfo holds 14999999995627669111 as a CBOR uint64").isTrue();
    }

    private static List<Byte> toList(byte[] bytes) {
        List<Byte> list = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            list.add(b);
        }
        return list;
    }

    /**
     * Preprod {@code aee75c1c…} creates an output whose PlutusV2 reference script holds a Plutus Core 1.1.0 program at
     * protocol version 10. {@code deserialiseScript} does not check the version, so the script is well formed
     * (Haskell accepted the transaction); only running it would fail ({@link ScriptCollection#plutusCoreVersionError}).
     */
    @Test
    void aPlutusCore110V2ReferenceScriptIsWellFormedAtProtocolVersion10() throws Exception {
        Phase2Case c = PublicNetworkTransactions.PHASE2_CASES.stream()
                .filter(x -> x.name().startsWith("preprod-aee75c1c")).findFirst().orElseThrow();
        byte[] txCbor = c.bundle().txCbor();
        RawTransaction raw = RawTransaction.parse(txCbor, CclTransactions.deserialize(txCbor));
        RawScript script = raw.allOutputs().stream().map(RawOutput::scriptRef)
                .filter(s -> s != null && s.language() == 2).findFirst().orElseThrow();

        assertThat(PlutusScriptDecoder.programVersion(script.bytes()))
                .hasValue(List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
        assertThat(new JulcScriptPhaseEvaluator().isWellFormed(2, script.bytes(), 10)).isTrue();
        assertThat(ScriptCollection.plutusCoreVersionError(2, script.bytes(), 10))
                .hasValueSatisfying(e -> assertThat(e).startsWith("PlutusCoreLanguageNotAvailableError 1.1.0"));
        assertThat(ScriptCollection.plutusCoreVersionError(2, script.bytes(), 11)).isEmpty();
    }
}
