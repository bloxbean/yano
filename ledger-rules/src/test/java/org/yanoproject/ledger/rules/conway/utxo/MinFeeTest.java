package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Conway's minimum fee: linear fee, ExUnits price, tiered reference-script fee. */
class MinFeeTest {

    /** Haskell's unit test (eras/conway/impl/test/Main.hs:44-47): multiplier 1.5, stride 25600, base 15. */
    @Test
    void tieredReferenceScriptFeeMatchesHaskellsVector() {
        ConwayLedgerConstants oneAndHalf = ConwayLedgerConstants.HASKELL.with(null, null, 25_600L,
                BigInteger.valueOf(3), BigInteger.TWO);
        List<BigInteger> fees = LongStream.iterate(0, n -> n <= 204_800, n -> n + 25_600)
                .mapToObj(n -> MinFee.tierRefScriptFee(oneAndHalf, BigDecimal.valueOf(15), n)).toList();
        assertThat(fees).containsExactly(BigInteger.ZERO, BigInteger.valueOf(384_000), BigInteger.valueOf(960_000),
                BigInteger.valueOf(1_824_000), BigInteger.valueOf(3_120_000), BigInteger.valueOf(5_064_000),
                BigInteger.valueOf(7_980_000), BigInteger.valueOf(12_354_000), BigInteger.valueOf(18_915_000));
    }

    @Test
    void tieredReferenceScriptFeeIsLinearWithGrowthOneAndFloored() {
        ConwayLedgerConstants linear = ConwayLedgerConstants.HASKELL.with(null, null, 100L, BigInteger.ONE,
                BigInteger.ONE);
        assertThat(MinFee.tierRefScriptFee(linear, new BigDecimal("0.7"), 1001)).isEqualTo(BigInteger.valueOf(700));
        // Haskell's 1.2 multiplier: 25600 bytes at 15, then 1 byte at 18.
        assertThat(MinFee.tierRefScriptFee(ConwayLedgerConstants.HASKELL, BigDecimal.valueOf(15), 25_601))
                .isEqualTo(BigInteger.valueOf(25_600 * 15 + 18));
    }

    @Test
    void scriptFeeRoundsUp() {
        // 0.0577 * 100000 + 0.0000721 * 50000000 = 5770 + 3605 = 9375 exactly; one more step rounds up.
        assertThat(MinFee.scriptFee(new BigDecimal("0.0577"), new BigDecimal("0.0000721"),
                BigInteger.valueOf(100_000), BigInteger.valueOf(50_000_000))).isEqualTo(BigInteger.valueOf(9_375));
        assertThat(MinFee.scriptFee(new BigDecimal("0.0577"), new BigDecimal("0.0000721"),
                BigInteger.valueOf(100_000), BigInteger.valueOf(50_000_001))).isEqualTo(BigInteger.valueOf(9_376));
    }

    @Test
    void scriptOriginalSizeIsThePlutusBytesOrTheNativeScriptEncoding() {
        assertThat(MinFee.scriptOriginalSize(HexUtil.decodeHexString("820346450101002499"))).isEqualTo(6);
        // native script [0, [0, h'00…']] (ScriptPubkey): the timelock's own bytes, 32 of them
        assertThat(MinFee.scriptOriginalSize(HexUtil.decodeHexString("82008200581c" + "00".repeat(28))))
                .isEqualTo(32);
        // still wrapped in tag 24
        assertThat(MinFee.scriptOriginalSize(HexUtil.decodeHexString("d8184982034645010100249" + "9")))
                .isEqualTo(6);
    }

    /**
     * {@code txNonDistinctRefScriptsSize}: a reference input carrying a 6-byte PlutusV3 reference script adds
     * {@code 6 · 15} lovelace to the minimum fee (preprod's {@code minFeeRefScriptCostPerByte}).
     */
    @Test
    @Covers("UTXO.FeeTooSmallUTxO")
    void referenceScriptsOfReferenceInputsAreCharged() {
        TransactionInput withScript = new TransactionInput("c".repeat(64), 0);
        TransactionOutput output = MutationWorld.output(TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(5_000_000));
        output.setScriptRef(HexUtil.decodeHexString("820346450101002499"));
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .utxo(withScript.getTransactionId(), withScript.getIndex(), output).build();

        TxSpec paid = MutationWorld.simpleSpec();
        paid.referenceInputs.add(withScript);
        paid.feeAdjust = BigInteger.valueOf(90);
        assertThat(validate(paid, view)).containsExactly("Valid");

        TxSpec short1 = MutationWorld.simpleSpec();
        short1.referenceInputs.add(withScript);
        short1.feeAdjust = BigInteger.valueOf(89);
        assertThat(validate(short1, view)).containsExactly("UTXO.FeeTooSmallUTxO");
    }

    private static List<String> validate(TxSpec spec, InMemoryLedgerView view) {
        BuiltTx tx = ConwayTxBuilder.build(spec, view);
        return EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), tx.cbor(), view,
                MutationWorld.env(), null));
    }
}
