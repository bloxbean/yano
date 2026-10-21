package org.yanoproject.ledger.rules.conway.utxos;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.ruleset.RuleUnit;
import org.yanoproject.ledger.rules.conway.ruleset.UnitKind;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code UTXOS} units ({@code utxosTransition}, Conway/Rules/Utxos.hs:207-242), of {@code ConwayScopes.UTXOS}
 * over the {@link TransitionContext}.
 */
public final class UtxosChecks {

    private static final int PLUTUS_V3 = 3;

    private UtxosChecks() {
    }

    /**
     * {@code CollectErrors} ({@code ?!:}, unlabelled, so dynamic: Babbage/Rules/Utxos.hs:143 for {@code isValid = True},
     * :206 for {@code False}). The evaluator's preparation found them while {@code UTXOW} ran
     * ({@link TransitionContext#collectFailures()}).
     */
    public static final class CollectErrors implements RuleUnit<TransitionContext> {

        @Override
        public List<LedgerFailure> apply(TransitionContext ctx) {
            return ctx.collectFailures();
        }

        @Override
        public String id() {
            return ConwayPredicate.COLLECT_ERRORS.qualifiedName();
        }

        @Override
        public UnitKind kind() {
            return UnitKind.CHECK;
        }

        @Override
        public CheckLabel label() {
            return ConwayPredicate.COLLECT_ERRORS.label();
        }

        @Override
        public String haskellRef() {
            return ConwayPredicate.COLLECT_ERRORS.haskellRef();
        }

        @Override
        public List<ConwayPredicate> reports() {
            return List.of(ConwayPredicate.COLLECT_ERRORS);
        }
    }

    /**
     * Plutus execution, inside {@code when2Phase $ whenFailureFree} (Babbage/Rules/Utxos.hs:145-157, :208-222): skipped
     * on re-application ({@code when2Phase} is labelled static) and whenever any rule of the transition has already
     * failed. {@code isValid = True} with a failing script is {@code ValidationTagMismatch (IsValid True)
     * (FailedUnexpectedly …)}; {@code isValid = False} with all scripts passing (including no scripts at all:
     * {@code evalPlutusScripts []} passes) is {@code ValidationTagMismatch (IsValid False) PassedUnexpectedly}.
     *
     * <p>The two implementations differ only in {@link #contextUnsupported}: whether the phase-2 evaluator can build
     * the script context Haskell builds at the protocol version.</p>
     */
    abstract static class PlutusExecution implements RuleUnit<TransitionContext> {

        /**
         * @return why the evaluator cannot build Haskell's script context for this transaction, or null when it can
         */
        abstract String contextUnsupported(TransitionContext ctx, ScriptPhaseEvaluator evaluator);

        @Override
        public final List<LedgerFailure> apply(TransitionContext ctx) {
            if (ctx.failing()) {
                return List.of(); // whenFailureFree
            }
            RawTransaction raw = ctx.raw();
            boolean claimedValid = raw.isValid();
            ScriptPhaseResult result;
            if (!raw.hasRedeemers()) {
                // No redeemer: no Plutus script can run (a needed one without a redeemer is UTXOW's MissingRedeemers
                // and CollectErrors NoRedeemer), and evalPlutusScripts [] passes.
                result = new ScriptPhaseResult.Passed(List.of());
            } else {
                ScriptPhaseEvaluator evaluator = ctx.evaluator();
                if (evaluator == null) {
                    return List.of(new LedgerFailure(LedgerRuleName.ENGINE, UtxosRule.PHASE_TWO_UNAVAILABLE,
                            LedgerFailure.Phase.PHASE_1,
                            "the transaction has redeemers and the node has no phase-2 evaluator"));
                }
                String unsupported = contextUnsupported(ctx, evaluator);
                if (unsupported != null) {
                    return List.of(new LedgerFailure(LedgerRuleName.ENGINE, UtxosRule.PHASE_TWO_CONTEXT_UNSUPPORTED,
                            LedgerFailure.Phase.PHASE_1, unsupported));
                }
                result = evaluator.evaluate(raw, ctx.resolvedInputs(), ctx.params(), ctx.env().slotConfig(),
                        ctx.forecastBasisSlot());
            }
            return switch (result) {
                case ScriptPhaseResult.Passed passed -> claimedValid ? List.of()
                        : List.of(ConwayPredicate.VALIDATION_TAG_MISMATCH.failure("IsValid False, PassedUnexpectedly"));
                case ScriptPhaseResult.Failed failed -> !claimedValid ? List.of()
                        : List.of(ConwayPredicate.VALIDATION_TAG_MISMATCH.failure(
                        "IsValid True, FailedUnexpectedly " + describe(failed.scripts())));
                // The preparation already passed, so this is an evaluator whose collect step does not see everything
                // its evaluate step does; the failures are phase one either way.
                case ScriptPhaseResult.Rejected rejected -> rejected.failures();
            };
        }

        @Override
        public final String id() {
            return ConwayPredicate.VALIDATION_TAG_MISMATCH.qualifiedName();
        }

        @Override
        public final UnitKind kind() {
            return UnitKind.CHECK;
        }

        @Override
        public final CheckLabel label() {
            return ConwayPredicate.VALIDATION_TAG_MISMATCH.label();
        }

        @Override
        public final List<ConwayPredicate> reports() {
            return List.of(ConwayPredicate.VALIDATION_TAG_MISMATCH);
        }

        private static String describe(List<ScriptOutcome> scripts) {
            return scripts.stream().filter(s -> !s.success())
                    .map(s -> s.purpose() + "[" + s.index() + "]" + (s.error() != null ? ": " + s.error() : ""))
                    .collect(Collectors.joining(", ", "[", "]"));
        }
    }

    /**
     * Plutus execution during the bootstrap phase (protocol version 9). Its script context differs
     * (Conway/TxInfo.hs:572-581, certifying purpose :636-640): a PlutusV3 {@code TxInfo}, its redeemer map and the
     * certifying {@code ScriptInfo} carry {@code Nothing} for the deposit of a {@code reg_cert} (tag 7) and the refund of
     * an {@code unreg_cert} (tag 8). Plutus V1/V2 contexts are the same at every protocol version
     * ({@code transTxCertV1V2}, :383-397, translates them to {@code DCertDelegRegKey} / {@code DCertDelegDeRegKey}
     * without a deposit), so only a transaction whose {@code plutusLanguagesUsed} contains PlutusV3 differs. An evaluator
     * that cannot build that context ({@link ScriptPhaseEvaluator#translatesBootstrapPhaseCertificateDeposits()}) fails
     * closed with {@code ENGINE.PhaseTwoContextUnsupported} instead of running the scripts over another context.
     */
    public static final class BootstrapPhasePlutusExecution extends PlutusExecution {

        @Override
        String contextUnsupported(TransitionContext ctx, ScriptPhaseEvaluator evaluator) {
            if (!ctx.plutusLanguagesUsed().contains(PLUTUS_V3) || evaluator.translatesBootstrapPhaseCertificateDeposits()) {
                return null;
            }
            for (RawCertificate cert : ctx.raw().certificates()) {
                if (cert.tag() == RawCertificate.REG || cert.tag() == RawCertificate.UNREG) {
                    return "protocol version " + ctx.protocolMajor() + ": the PlutusV3 context of certificate "
                            + cert.tag() + " omits its deposit (hardforkConwayBootstrapPhase, Conway/TxInfo.hs:572-581), "
                            + "which the phase-2 evaluator does not model";
                }
            }
            return null;
        }

        @Override
        public String haskellRef() {
            return ConwayPredicate.VALIDATION_TAG_MISMATCH.haskellRef() + "; bootstrap-phase PlutusV3 context without "
                    + "certificate deposits (Conway/TxInfo.hs:572-581, 636-640)";
        }
    }

    /** Plutus execution after the bootstrap phase: every script context the evaluator builds is Haskell's. */
    public static final class PlutusExecutionAfterBootstrap extends PlutusExecution {

        @Override
        String contextUnsupported(TransitionContext ctx, ScriptPhaseEvaluator evaluator) {
            return null;
        }

        @Override
        public String haskellRef() {
            return ConwayPredicate.VALIDATION_TAG_MISMATCH.haskellRef();
        }
    }
}
