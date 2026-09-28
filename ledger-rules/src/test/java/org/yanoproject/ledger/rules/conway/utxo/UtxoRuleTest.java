package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.EngineTestSupport.run;

/**
 * The {@code UTXO} checks of the Java engine (ADR-056 Phase 3a), one fault at a time against the mutation world
 * (preprod-like, protocol version 10). Where Haskell reports a fault with more than one constructor, the test
 * asserts Haskell's whole {@code LEDGER} list in its order ({@code RuleFrame}).
 */
class UtxoRuleTest {

    @Test
    void baseTransactionsAreValid() {
        assertThat(run(MutationWorld.simpleSpec())).containsExactly("Valid");
        assertThat(run(MutationWorld.scriptSpec())).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.BabbageNonDisjointRefInputs")
    void referenceInputsMustBeDisjointFromSpendingInputsBeforeProtocolVersion11() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.referenceInputs.add(MutationWorld.KEY_INPUT);
        BuiltTx tx = EngineTestSupport.build(spec);

        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), tx.cbor())))
                .containsExactly("UTXO.BabbageNonDisjointRefInputs");
        // The PV gate's other side: Babbage/Rules/Utxo.hs:207-214 stops at protocol version 11.
        TxValidationOutcome v11 = EngineTestSupport.validate(new StubEvaluator(), tx.cbor(),
                MutationWorld.builder(EngineTestSupport.params(11)).build(), EngineTestSupport.env(11), null);
        assertThat(EngineTestSupport.names(v11)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.OutsideValidityIntervalUTxO")
    void validityIntervalIsLowerInclusiveUpperExclusive() {
        TxSpec expired = MutationWorld.simpleSpec();
        expired.ttl = MutationWorld.SLOT;
        assertThat(run(expired)).containsExactly("UTXO.OutsideValidityIntervalUTxO");

        TxSpec early = MutationWorld.simpleSpec();
        early.validityStart = MutationWorld.SLOT + 1;
        assertThat(run(early)).containsExactly("UTXO.OutsideValidityIntervalUTxO");

        TxSpec edges = MutationWorld.simpleSpec();
        edges.validityStart = MutationWorld.SLOT;
        edges.ttl = MutationWorld.SLOT + 1;
        assertThat(run(edges)).containsExactly("Valid");
    }

    /**
     * {@code OutsideForecast} cannot fail at cardano-ledger {@code f649f975}: the bound is translated with an
     * epoch info extended linearly from the current slot. A bound past the forecast horizon is rejected by the
     * script context instead ({@code UTXOS.CollectErrors [BadTranslation TimeTranslationPastHorizon]}, from the
     * evaluator's {@code ForecastHorizon}; the conformance module tests it with the Scalus evaluator); with a
     * stub evaluator nothing in {@code UTXO} rejects it.
     */
    @Test
    void outsideForecastCannotFailForATranslatableCurrentSlot() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.ttl = MutationWorld.SLOT + 1_000_000_000L;
        assertThat(run(spec)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.InputSetEmptyUTxO")
    void emptyInputSet() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.clear();
        spec.outputs.clear();
        spec.changeAdjust = BigInteger.valueOf(5_000_000);
        // The change is paid from nothing, so value conservation fails with it; LEDGER lists UTXO failures reversed.
        assertThat(run(spec)).containsExactly("UTXO.ValueNotConservedUTxO", "UTXO.InputSetEmptyUTxO");
    }

    @Test
    @Covers("UTXO.FeeTooSmallUTxO")
    void feeOneLovelaceBelowTheMinimum() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.feeAdjust = BigInteger.ONE.negate(); // the builder's change takes the lovelace, so the value balances
        assertThat(run(spec)).containsExactly("UTXO.FeeTooSmallUTxO");

        TxSpec script = MutationWorld.scriptSpec();
        script.feeAdjust = BigInteger.ONE.negate();
        assertThat(run(script)).as("the ExUnits price is part of the minimum").containsExactly("UTXO.FeeTooSmallUTxO");
    }

    @Test
    @Covers("UTXO.ScriptsNotPaidUTxO")
    void collateralLockedByAScript() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral.set(0, MutationWorld.SCRIPT_COLLATERAL_INPUT);
        assertThat(run(spec)).containsExactly("UTXO.ScriptsNotPaidUTxO");
    }

    @Test
    @Covers("UTXO.CollateralContainsNonADA")
    void collateralWithATokenAndNoReturn() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral.set(0, MutationWorld.TOKEN_COLLATERAL_INPUT);
        assertThat(run(spec)).containsExactly("UTXO.CollateralContainsNonADA");
    }

    @Test
    @Covers("UTXO.InsufficientCollateral")
    void collateralBelowThePercentageOfTheFee() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral.set(0, MutationWorld.SMALL_COLLATERAL_INPUT);
        assertThat(run(spec)).containsExactly("UTXO.InsufficientCollateral");
    }

    @Test
    @Covers("UTXO.IncorrectTotalCollateralField")
    void declaredTotalCollateralDiffersFromTheBalance() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.totalCollateral = MutationWorld.COLLATERAL_LOVELACE.add(BigInteger.ONE);
        assertThat(run(spec)).containsExactly("UTXO.IncorrectTotalCollateralField");

        TxSpec exact = MutationWorld.scriptSpec();
        exact.totalCollateral = MutationWorld.COLLATERAL_LOVELACE;
        assertThat(run(exact)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.NoCollateralInputs")
    void redeemersWithoutCollateral() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral.clear();
        // feesOK parts 5 and 7 in one sequenceA_, reversed in the LEDGER list.
        assertThat(run(spec)).containsExactly("UTXO.NoCollateralInputs", "UTXO.InsufficientCollateral");
    }

    @Test
    void collateralChecksNeedRedeemers() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.collateral.add(MutationWorld.SCRIPT_COLLATERAL_INPUT);
        spec.collateral.add(MutationWorld.TOKEN_COLLATERAL_INPUT);
        // Without redeemers feesOK stops after part 1 (Babbage/Rules/Utxo.hs:186-193).
        assertThat(run(spec)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.BadInputsUTxO")
    void unknownSpendingCollateralAndReferenceInputs() {
        TxSpec spending = MutationWorld.simpleSpec();
        spending.inputs.add(phantom('9'));
        assertThat(run(spending)).containsExactly("UTXO.BadInputsUTxO");

        TxSpec reference = MutationWorld.simpleSpec();
        reference.referenceInputs.add(phantom('a'));
        assertThat(run(reference)).containsExactly("UTXO.BadInputsUTxO");

        TxSpec collateral = MutationWorld.scriptSpec();
        collateral.collateral.add(phantom('b'));
        assertThat(run(collateral)).containsExactly("UTXO.BadInputsUTxO");
    }

    @Test
    @Covers("UTXO.ValueNotConservedUTxO")
    void changeOneLovelaceTooLarge() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.changeAdjust = BigInteger.ONE;
        assertThat(run(spec)).containsExactly("UTXO.ValueNotConservedUTxO");
    }

    @Test
    void treasuryDonationIsProduced() {
        TxSpec balanced = MutationWorld.simpleSpec();
        balanced.donation = BigInteger.valueOf(1_000_000);
        balanced.changeAdjust = BigInteger.valueOf(-1_000_000);
        assertThat(run(balanced)).containsExactly("Valid");

        TxSpec unpaid = MutationWorld.simpleSpec();
        unpaid.donation = BigInteger.valueOf(1_000_000);
        assertThat(run(unpaid)).containsExactly("UTXO.ValueNotConservedUTxO");
    }

    @Test
    @Covers("UTXO.BabbageOutputTooSmallUTxO")
    void outputBelowItsMinimumUtxoValue() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(500_000)));
        assertThat(run(spec)).containsExactly("UTXO.BabbageOutputTooSmallUTxO");
    }

    @Test
    void minimumUtxoValueUsesTheOriginalOutputSize() {
        // The payment output (legacy form [address, coin]) encodes in 37 bytes: (160 + 37) * 4310 = 849070.
        TxSpec exact = MutationWorld.simpleSpec();
        exact.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(849_070)));
        BuiltTx tx = EngineTestSupport.build(exact);
        assertThat(RawTransaction.parse(tx.cbor(), tx.tx()).outputs().get(0)
                .size()).isEqualTo(37);
        assertThat(run(exact)).containsExactly("Valid");

        TxSpec below = MutationWorld.simpleSpec();
        below.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(849_069)));
        assertThat(run(below)).containsExactly("UTXO.BabbageOutputTooSmallUTxO");
    }

    @Test
    @Covers("UTXO.OutputTooBigUTxO")
    void outputValueSerialisesAboveMaxValSize() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.MANY_ASSETS_INPUT);
        assertThat(run(spec)).containsExactly("UTXO.OutputTooBigUTxO");
    }

    @Test
    @Covers("UTXO.OutputBootAddrAttrsTooBig")
    void byronAddressWithOversizedAttributes() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.outputs.set(0, MutationWorld.output(MutationWorld.byronAddress(65), MutationWorld.PAYMENT));
        assertThat(run(spec)).containsExactly("UTXO.OutputBootAddrAttrsTooBig");

        TxSpec atLimit = MutationWorld.simpleSpec();
        atLimit.outputs.set(0, MutationWorld.output(MutationWorld.byronAddress(64), MutationWorld.PAYMENT));
        assertThat(run(atLimit)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.WrongNetwork")
    void outputToAMainnetAddress() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.outputs.set(0, MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(Networks.mainnet()),
                MutationWorld.PAYMENT));
        assertThat(run(spec)).containsExactly("UTXO.WrongNetwork");
    }

    @Test
    @Covers("UTXO.WrongNetworkWithdrawal")
    void withdrawalFromAMainnetRewardAccount() {
        TxSpec spec = MutationWorld.simpleSpec();
        String mainnetRewardAccount = AddressProvider.getRewardAddress(
                Credential.fromKey(HexUtil.decodeHexString(TestKey.DEV_42.keyHash())), Networks.mainnet())
                .toBech32();
        spec.withdrawals.add(new Withdrawal(mainnetRewardAccount, BigInteger.ZERO));
        assertThat(run(spec)).containsExactly("UTXO.WrongNetworkWithdrawal");
    }

    @Test
    @Covers("UTXO.WrongNetworkInTxBody")
    void bodyNetworkIdForAnotherNetwork() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.bodyNetworkId = NetworkId.MAINNET;
        assertThat(run(spec)).containsExactly("UTXO.WrongNetworkInTxBody");

        TxSpec same = MutationWorld.simpleSpec();
        same.bodyNetworkId = NetworkId.TESTNET;
        assertThat(run(same)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXO.MaxTxSizeUTxO")
    void transactionAboveMaxTxSize() {
        BuiltTx tx = EngineTestSupport.build(MutationWorld.simpleSpec());
        int size = tx.cbor().length - 1; // toCBORForSizeComputation leaves out is_valid
        assertThat(EngineTestSupport.names(validateWithMaxTxSize(tx, size))).containsExactly("Valid");
        assertThat(EngineTestSupport.names(validateWithMaxTxSize(tx, size - 1)))
                .containsExactly("UTXO.MaxTxSizeUTxO");
    }

    @Test
    @Covers("UTXO.ExUnitsTooBigUTxO")
    void redeemerAboveMaxTxExUnits() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.get(0).setExUnits(ExUnits.builder().mem(BigInteger.valueOf(14_000_001))
                .steps(BigInteger.valueOf(50_000_000)).build());
        assertThat(run(spec)).containsExactly("UTXO.ExUnitsTooBigUTxO");
    }

    @Test
    @Covers("UTXO.TooManyCollateralInputs")
    void moreCollateralInputsThanAllowed() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral = new ArrayList<>(List.of(MutationWorld.collateralInput(0),
                MutationWorld.collateralInput(1), MutationWorld.collateralInput(2), MutationWorld.collateralInput(3)));
        assertThat(run(spec)).containsExactly("UTXO.TooManyCollateralInputs");
    }

    @Test
    void failuresAccumulateInHaskellsOrder() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.feeAdjust = BigInteger.ONE.negate();
        spec.collateral = new ArrayList<>(List.of(MutationWorld.collateralInput(0),
                MutationWorld.collateralInput(1), MutationWorld.collateralInput(2), MutationWorld.collateralInput(3)));
        spec.bodyNetworkId = NetworkId.MAINNET;
        // Execution order FeeTooSmall (:371), WrongNetworkInTxBody (:403), TooManyCollateralInputs (:412); the
        // LEDGER list has UTXO's failures reversed, and UTXOS (Plutus) does not run after a failure.
        assertThat(run(spec)).containsExactly("UTXO.TooManyCollateralInputs", "UTXO.WrongNetworkInTxBody",
                "UTXO.FeeTooSmallUTxO");
    }

    private static TxValidationOutcome validateWithMaxTxSize(BuiltTx tx, int maxTxSize) {
        var params = MutationWorld.protocolParams();
        params.setMaxTxSize(maxTxSize);
        return EngineTestSupport.validate(new StubEvaluator(), tx.cbor(), MutationWorld.builder(params).build(),
                MutationWorld.env(), null);
    }

    private static TransactionInput phantom(char digit) {
        return new TransactionInput(String.valueOf(digit).repeat(64), 0);
    }
}
