package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The transition's roots and Haskell's failure-list order (ADR-056 §4; {@link RuleFrame}). */
class ConwayLedgerTransitionTest {

    @Test
    void theListOrderDependsOnTheRootRule() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.collateral.clear();
        byte[] cbor = EngineTestSupport.build(spec).cbor();
        // feesOK parts 5 and 7 (InsufficientCollateral, NoCollateralInputs) are UTXO's own list reversed; every
        // enclosing rule reverses what its sub-rule reports once more: UTXOW, LEDGER, MEMPOOL.
        assertThat(validate(cbor, TxValidationRequest.Rule.LEDGER))
                .containsExactly("UTXO.NoCollateralInputs", "UTXO.InsufficientCollateral");
        assertThat(validate(cbor, TxValidationRequest.Rule.MEMPOOL))
                .containsExactly("UTXO.InsufficientCollateral", "UTXO.NoCollateralInputs");
    }

    @Test
    void aDuplicateReportsOnlyTheMempoolFailure() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs = new ArrayList<>(List.of(new TransactionInput("9".repeat(64), 0)));
        spec.outputs.clear();
        spec.changeAdjust = BigInteger.valueOf(5_000_000);
        byte[] cbor = EngineTestSupport.build(spec).cbor();
        assertThat(validate(cbor, TxValidationRequest.Rule.MEMPOOL)).containsExactly("LEDGER.ConwayMempoolFailure");
        assertThat(validate(cbor, TxValidationRequest.Rule.LEDGER)).contains("UTXO.BadInputsUTxO")
                .doesNotContain("LEDGER.ConwayMempoolFailure");
    }

    @Test
    void ruleFramesAccumulateAsSmallSteps() {
        BuiltTx built = EngineTestSupport.build(MutationWorld.simpleSpec());
        RawTransaction raw = RawTransaction.parse(built.cbor(), built.tx());
        TransitionContext ctx = new TransitionContext(raw, MutationWorld.view(), MutationWorld.env(),
                MutationWorld.protocolParams(), 10, TransitionContext.Mode.FULL, TxValidationRequest.Rule.LEDGER,
                ConwayLedgerConstants.HASKELL, null, TransitionContext.resolve(raw, MutationWorld.view()));
        RuleFrame parent = new RuleFrame(LedgerRuleName.LEDGER, ctx);
        assertThat(ctx.failing()).isFalse();
        parent.fail(failure("A"));
        RuleFrame child = parent.child(LedgerRuleName.UTXOW);
        child.predicate(List.of(failure("B"), failure("C")));
        child.fail(failure("D"));
        assertThat(child.failures()).extracting(LedgerFailure::constructor).containsExactly("D", "C", "B");
        parent.subRule(child);
        parent.fail(failure("E"));
        // map orElse (reverse errs) <> fs, and traverse_ (a :) over the sub-rule's list (Extended.hs:708, 721)
        assertThat(parent.failures()).extracting(LedgerFailure::constructor).containsExactly("E", "B", "C", "D", "A");
        assertThat(ctx.failing()).isTrue();
    }

    private static LedgerFailure failure(String name) {
        return new LedgerFailure(LedgerRuleName.UTXO, name, LedgerFailure.Phase.PHASE_1, "");
    }

    private static List<String> validate(byte[] cbor, TxValidationRequest.Rule rule) {
        TxValidationOutcome outcome = new JavaLedgerValidationEngine(new StubEvaluator()).validate(
                new TxValidationRequest(cbor, MutationWorld.view(), MutationWorld.env(), rule,
                        TxValidationRequest.Origin.SYNC, null));
        return EngineTestSupport.names(outcome);
    }
}
