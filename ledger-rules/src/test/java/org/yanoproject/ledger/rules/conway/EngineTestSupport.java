package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared set-up for the Java engine's rule tests: transactions from the mutation world's builder, validated with
 * rule {@code LEDGER} and origin {@code SYNC} by a {@link JavaLedgerValidationEngine} whose phase-2 evaluator is a
 * {@link StubEvaluator} (ledger-rules has no Plutus machine; the conformance module runs the real one).
 */
public final class EngineTestSupport {

    private EngineTestSupport() {
    }

    /**
     * A phase-2 evaluator with fixed answers that counts how often it ran scripts. Every Plutus script is well formed
     * except the ones whose {@code PlutusBinary} is in {@link #malformed}.
     */
    public static class StubEvaluator implements ScriptPhaseEvaluator {
        public List<LedgerFailure> collect = List.of();
        public ScriptPhaseResult result = new ScriptPhaseResult.Passed(List.of());
        public final Set<String> malformed = new HashSet<>();
        public int evaluations;
        public int collections;

        @Override
        public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
            return !malformed.contains(HexUtil.encodeHexString(script));
        }

        @Override
        public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                          ProtocolParams params, SlotConfig slotConfig) {
            evaluations++;
            return result;
        }

        @Override
        public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                           ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
            collections++;
            return collect;
        }
    }

    public static BuiltTx build(TxSpec spec) {
        return ConwayTxBuilder.build(spec, MutationWorld.view());
    }

    public static TxValidationOutcome validate(ScriptPhaseEvaluator evaluator, byte[] cbor) {
        return validate(evaluator, cbor, MutationWorld.view(), MutationWorld.env(), null);
    }

    public static TxValidationOutcome validate(ScriptPhaseEvaluator evaluator, byte[] cbor, LedgerView view,
                                               ValidationEnv env, ValidatedTx previous) {
        return new JavaLedgerValidationEngine(evaluator).validate(new TxValidationRequest(cbor, view, env,
                TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, previous));
    }

    /** Validates with rule {@code rule} (origin {@code SYNC} for {@code LEDGER}, {@code LOCAL} for {@code MEMPOOL}). */
    public static TxValidationOutcome validateWith(JavaLedgerValidationEngine engine, byte[] cbor, LedgerView view,
                                               ValidationEnv env, TxValidationRequest.Rule rule) {
        TxValidationRequest.Origin origin = rule == TxValidationRequest.Rule.MEMPOOL
                ? TxValidationRequest.Origin.LOCAL : TxValidationRequest.Origin.SYNC;
        return engine.validate(new TxValidationRequest(cbor, view, env, rule, origin, null));
    }

    /** @return the qualified failure names, or {@code ["Valid"]} */
    public static List<String> names(TxValidationOutcome outcome) {
        return switch (outcome) {
            case TxValidationOutcome.Valid v -> List.of("Valid");
            case TxValidationOutcome.Invalid i -> i.failures().stream().map(LedgerFailure::qualifiedName).toList();
        };
    }

    /** Builds {@code spec} and validates it against the mutation world with a passing evaluator. */
    public static List<String> run(TxSpec spec) {
        return names(validate(new StubEvaluator(), build(spec).cbor()));
    }

    /** @return the mutation world's parameters at another protocol major version */
    public static ProtocolParams params(int protocolMajor) {
        ProtocolParams params = MutationWorld.protocolParams();
        params.setProtocolMajorVer(protocolMajor);
        return params;
    }

    public static ValidationEnv env(int protocolMajor) {
        ValidationEnv base = MutationWorld.env();
        return new ValidationEnv(base.currentSlot(), base.currentEpoch(), protocolMajor, 0, base.networkId(),
                base.slotConfig(), base.phase2EnvDigest());
    }
}
