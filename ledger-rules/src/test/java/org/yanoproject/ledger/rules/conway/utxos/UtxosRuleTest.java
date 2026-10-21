package org.yanoproject.ledger.rules.conway.utxos;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code UTXOS} rule: {@code CollectErrors}, then Plutus under {@code when2Phase $ whenFailureFree}. */
class UtxosRuleTest {

    private static final ScriptPhaseResult.Failed FAILED = new ScriptPhaseResult.Failed(List.of(
            new ScriptOutcome("spend", 1, false, 0, 0, List.of(), "error")));

    @Test
    @Covers("UTXOS.CollectErrors")
    void collectErrorsAreReportedAndStopTheScripts() {
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.collect = List.of(new LedgerFailure(LedgerRuleName.UTXOS, "CollectErrors",
                LedgerFailure.Phase.PHASE_1, "NoCostModel PlutusV3"));
        TxValidationOutcome outcome = EngineTestSupport.validate(evaluator, build(MutationWorld.scriptSpec()));
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXOS.CollectErrors");
        assertThat(evaluator.evaluations).as("no script runs after a failure").isZero();
    }

    @Test
    @Covers("UTXOS.ValidationTagMismatch")
    void aFailingScriptClaimedValid() {
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.result = FAILED;
        TxValidationOutcome outcome = EngineTestSupport.validate(evaluator, build(MutationWorld.scriptSpec()));
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXOS.ValidationTagMismatch");
        LedgerFailure failure = ((TxValidationOutcome.Invalid) outcome).failures().getFirst();
        assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_2);
        assertThat(failure.detail()).contains("IsValid True", "FailedUnexpectedly", "spend[1]");
    }

    @Test
    @Covers("UTXOS.ValidationTagMismatch")
    void passingScriptsClaimedInvalid() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.isValid = false;
        TxValidationOutcome outcome = EngineTestSupport.validate(new StubEvaluator(), build(spec));
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXOS.ValidationTagMismatch");
        assertThat(((TxValidationOutcome.Invalid) outcome).failures().getFirst().detail())
                .contains("IsValid False", "PassedUnexpectedly");

        TxSpec noScripts = MutationWorld.simpleSpec();
        noScripts.isValid = false;
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), build(noScripts))))
                .as("evalPlutusScripts [] passes").containsExactly("UTXOS.ValidationTagMismatch");
    }

    @Test
    void anInvalidTransactionWithAFailingScriptCollectsCollateralOnSyncButIsNotAdmitted() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.isValid = false;
        byte[] cbor = build(spec);
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.result = FAILED;

        TxValidationOutcome sync = EngineTestSupport.validate(evaluator, cbor);
        assertThat(sync).isInstanceOf(TxValidationOutcome.Valid.class);
        TxValidationOutcome.Valid valid = (TxValidationOutcome.Valid) sync;
        assertThat(valid.effects().phase2Valid()).isFalse();
        assertThat(valid.effects().consumed()).singleElement()
                .satisfies(o -> assertThat(o.txHash()).isEqualTo(MutationWorld.collateralInput(0).getTransactionId()));

        TxValidationOutcome local = new JavaLedgerValidationEngine(evaluator).validate(new TxValidationRequest(cbor,
                MutationWorld.view(), MutationWorld.env(), TxValidationRequest.Rule.MEMPOOL,
                TxValidationRequest.Origin.LOCAL, null));
        assertThat(EngineTestSupport.names(local)).containsExactly("ENGINE.Phase2InvalidTxNotSupported");
    }

    @Test
    void scriptsDoNotRunAfterAPhaseOneFailure() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.feeAdjust = BigInteger.ONE.negate();
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.result = FAILED;
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(evaluator, build(spec))))
                .containsExactly("UTXO.FeeTooSmallUTxO");
        assertThat(evaluator.evaluations).isZero();
        assertThat(evaluator.collections).as("CollectErrors is dynamic and still prepared").isOne();
    }

    @Test
    void withoutAnEvaluatorARedeemerFailsClosed() {
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(null, build(MutationWorld.scriptSpec()))))
                .containsExactly("ENGINE.PhaseTwoEvaluatorUnavailable");
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(null, build(MutationWorld.simpleSpec()))))
                .containsExactly("Valid");
    }

    /**
     * {@code UTXOW} judges malformed scripts itself ({@code isWellFormed}); a {@code UTXOW} failure in the
     * evaluator's preparation result is ignored.
     */
    @Test
    void utxowFailuresFromThePreparationAreIgnored() {
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.collect = List.of(new LedgerFailure(LedgerRuleName.UTXOW, "MalformedScriptWitnesses",
                LedgerFailure.Phase.PHASE_1, "x"));
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(evaluator, build(MutationWorld.scriptSpec()))))
                .containsExactly("Valid");
    }

    /**
     * A reference script that nothing needs is not prepared; one that a spending input needs, without a redeemer,
     * is {@code UTXOW.MissingRedeemers}, a script integrity hash mismatch (the language is used, so Haskell expects
     * a hash of {@code a0} and the PlutusV3 view) and {@code UTXOS.CollectErrors [NoRedeemer]}, in that order.
     */
    @Test
    void scriptsProvidedByReferenceInputsArePreparedWhenNeeded() {
        TransactionInput withScript = new TransactionInput("c".repeat(64), 0);
        TransactionOutput output = MutationWorld.output(TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(5_000_000));
        output.setScriptRef(HexUtil.decodeHexString("820346450101002499"));
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .utxo(withScript.getTransactionId(), withScript.getIndex(), output).build();

        TxSpec unneeded = MutationWorld.simpleSpec();
        unneeded.referenceInputs.add(withScript);
        unneeded.feeAdjust = BigInteger.valueOf(90); // the 6-byte reference script at 15 lovelace per byte
        StubEvaluator notNeeded = new StubEvaluator();
        byte[] unneededCbor = ConwayTxBuilder.build(unneeded, view).cbor();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(notNeeded, unneededCbor, view,
                MutationWorld.env(), null))).containsExactly("Valid");
        assertThat(notNeeded.collections).as("a provided script nothing needs is not prepared").isZero();

        TxSpec spec = MutationWorld.scriptSpec();
        spec.plutusScripts.clear();
        spec.redeemers.clear();
        spec.referenceInputs.add(withScript);
        spec.feeAdjust = BigInteger.valueOf(90);
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.collect = List.of(new LedgerFailure(LedgerRuleName.UTXOS, "CollectErrors",
                LedgerFailure.Phase.PHASE_1, "NoRedeemer spend[1]"));
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(evaluator, cbor, view, MutationWorld.env(),
                null))).containsExactly("UTXOW.MissingRedeemers", "UTXOW.PPViewHashesDontMatch",
                "UTXOS.CollectErrors");
        assertThat(evaluator.collections).isOne();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(null, cbor, view, MutationWorld.env(), null)))
                .as("fails closed without an evaluator").contains("ENGINE.PhaseTwoEvaluatorUnavailable");

        StubEvaluator nothing = new StubEvaluator();
        EngineTestSupport.validate(nothing, EngineTestSupport.build(MutationWorld.simpleSpec()).cbor());
        assertThat(nothing.collections).as("no Plutus anywhere: nothing to prepare").isZero();
    }

    /**
     * At protocol version 9 a PlutusV3 context leaves out the deposit of {@code reg_cert} / {@code unreg_cert}
     * ({@code hardforkConwayBootstrapPhase}, Conway/TxInfo.hs:572-581). An evaluator that does not model it is not run:
     * the engine fails closed.
     */
    @Test
    void bootstrapPhaseCertificateDepositsInAPlutusV3ContextFailClosed() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.certs.add(new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), MutationWorld.KEY_DEPOSIT));
        spec.changeAdjust = MutationWorld.KEY_DEPOSIT.negate();
        StubEvaluator pv9 = new StubEvaluator();
        assertThat(names(pv9, spec, 9)).containsExactly("ENGINE." + UtxosRule.PHASE_TWO_CONTEXT_UNSUPPORTED);
        assertThat(pv9.evaluations).isZero();
        StubEvaluator pv10 = new StubEvaluator();
        assertThat(names(pv10, spec, 10)).containsExactly("Valid");
        assertThat(pv10.evaluations).isOne();
        // An evaluator that translates the bootstrap-phase context runs.
        StubEvaluator capable = new StubEvaluator() {
            @Override
            public boolean translatesBootstrapPhaseCertificateDeposits() {
                return true;
            }
        };
        assertThat(names(capable, spec, 9)).containsExactly("Valid");
        // A legacy registration (tag 0) carries no deposit in any context.
        TxSpec legacy = MutationWorld.scriptSpec();
        legacy.certs.add(new StakeRegistration(MutationWorld.stakeCredential(TestKey.DEV_42)));
        legacy.changeAdjust = MutationWorld.KEY_DEPOSIT.negate();
        assertThat(names(new StubEvaluator(), legacy, 9)).containsExactly("Valid");
        // Without scripts to run the context is never built.
        TxSpec noScripts = MutationWorld.simpleSpec();
        noScripts.certs.add(new RegCert(MutationWorld.stakeCredential(TestKey.DEV_42), MutationWorld.KEY_DEPOSIT));
        noScripts.changeAdjust = MutationWorld.KEY_DEPOSIT.negate();
        assertThat(names(new StubEvaluator(), noScripts, 9)).containsExactly("Valid");
    }

    /**
     * Plutus V1/V2 contexts translate a {@code reg_cert} without its deposit at every protocol version
     * ({@code transTxCertV1V2}, Conway/TxInfo.hs:383-397): a PlutusV2 certifying script runs at protocol version 9
     * with an evaluator that does not model the bootstrap-phase V3 context.
     */
    @Test
    void bootstrapPhaseCertificateDepositsDoNotConcernPlutusV2() throws Exception {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.certs.add(new RegCert(StakeCredential.fromScriptHash(MutationWorld.ALWAYS_SUCCEEDS_V2.getScriptHash()),
                MutationWorld.KEY_DEPOSIT));
        spec.plutusV2Scripts.add(MutationWorld.ALWAYS_SUCCEEDS_V2);
        spec.redeemers.add(Redeemer.builder().tag(RedeemerTag.Cert).index(BigInteger.ZERO)
                .data(ConstrPlutusData.of(0))
                .exUnits(ExUnits.builder().mem(BigInteger.valueOf(100_000)).steps(BigInteger.valueOf(50_000_000))
                        .build())
                .build());
        spec.collateral.add(MutationWorld.collateralInput(0));
        spec.changeAdjust = MutationWorld.KEY_DEPOSIT.negate();
        StubEvaluator evaluator = new StubEvaluator();
        assertThat(names(evaluator, spec, 9)).containsExactly("Valid");
        assertThat(evaluator.evaluations).isOne();
    }

    private static List<String> names(StubEvaluator evaluator, TxSpec spec, int protocolMajor) {
        InMemoryLedgerView view = MutationWorld.view(protocolMajor);
        return EngineTestSupport.names(EngineTestSupport.validate(evaluator, ConwayTxBuilder.build(spec, view).cbor(),
                view, MutationWorld.env(protocolMajor), null));
    }

    private static byte[] build(TxSpec spec) {
        BuiltTx tx = EngineTestSupport.build(spec);
        return tx.cbor();
    }
}
