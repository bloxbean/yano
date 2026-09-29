package org.yanoproject.ledger.rules.conway.utxos;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.PvRange;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The Conway {@code UTXOS} rule ({@code utxosTransition}, Conway/Rules/Utxos.hs:207-242), which Haskell runs for
 * both validity flags:
 *
 * <ol>
 *   <li>{@code CollectErrors} ({@code ?!:}, unlabelled, so dynamic: Babbage/Rules/Utxos.hs:143 for
 *       {@code isValid = True}, :206 for {@code False}). The evaluator's preparation found them while
 *       {@code UTXOW} ran ({@link TransitionContext#collectFailures()}).</li>
 *   <li>Plutus execution, inside {@code when2Phase $ whenFailureFree} (:145-157, :208-222): skipped on
 *       re-application ({@code when2Phase} is labelled static) and whenever any rule of the transition has already
 *       failed. {@code isValid = True} with a failing script is {@code ValidationTagMismatch (IsValid True)
 *       (FailedUnexpectedly …)}; {@code isValid = False} with all scripts passing (including no scripts at all:
 *       {@code evalPlutusScripts []} passes) is {@code ValidationTagMismatch (IsValid False) PassedUnexpectedly}.</li>
 * </ol>
 */
public final class UtxosRule {

    /** Engine constructor: a transaction needs Plutus execution and the node has no evaluator. */
    public static final String PHASE_TWO_UNAVAILABLE = "PhaseTwoEvaluatorUnavailable";
    /**
     * Engine constructor: the evaluator cannot build the script context Haskell builds for this transaction at this
     * protocol version ({@link #bootstrapPhaseContextUnsupported}); the engine fails closed instead of running the
     * scripts over another context.
     */
    public static final String PHASE_TWO_CONTEXT_UNSUPPORTED = "PhaseTwoContextUnsupported";

    /**
     * The protocol versions at which {@code transTxCert} drops the deposit of Conway (de)registration certificates
     * from a PlutusV3 {@code TxInfo}: {@code hardforkConwayBootstrapPhase} (Conway/TxInfo.hs:572-581).
     */
    static final PvRange CERTIFICATE_DEPOSITS_OMITTED = PvRange.BOOTSTRAP;

    private static final int PLUTUS_V3 = 3;

    private UtxosRule() {
    }

    /** Runs {@code UTXOS} as a sub-rule of {@code UTXO}. */
    public static void apply(RuleFrame utxo) {
        TransitionContext ctx = utxo.context();
        RuleFrame utxos = utxo.child(LedgerRuleName.UTXOS);
        if (ctx.runs(ConwayPredicate.COLLECT_ERRORS)) {
            utxos.predicate(ctx.collectFailures());
        }
        if (ctx.runs(ConwayPredicate.VALIDATION_TAG_MISMATCH) && !ctx.failing()) {
            evaluate(ctx, utxos);
        }
        utxo.subRule(utxos);
    }

    private static void evaluate(TransitionContext ctx, RuleFrame utxos) {
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
                utxos.fail(new LedgerFailure(LedgerRuleName.ENGINE, PHASE_TWO_UNAVAILABLE, LedgerFailure.Phase.PHASE_1,
                        "the transaction has redeemers and the node has no phase-2 evaluator"));
                return;
            }
            String unsupported = bootstrapPhaseContextUnsupported(ctx, evaluator);
            if (unsupported != null) {
                utxos.fail(new LedgerFailure(LedgerRuleName.ENGINE, PHASE_TWO_CONTEXT_UNSUPPORTED,
                        LedgerFailure.Phase.PHASE_1, unsupported));
                return;
            }
            result = evaluator.evaluate(raw.txCbor(), ctx.tx(), ctx.resolvedInputs(), ctx.params(),
                    ctx.env().slotConfig(), ctx.forecastBasisSlot());
        }
        switch (result) {
            case ScriptPhaseResult.Passed passed -> {
                if (!claimedValid) {
                    utxos.fail(ConwayPredicate.VALIDATION_TAG_MISMATCH.failure("IsValid False, PassedUnexpectedly"));
                }
            }
            case ScriptPhaseResult.Failed failed -> {
                if (claimedValid) {
                    utxos.fail(ConwayPredicate.VALIDATION_TAG_MISMATCH.failure(
                            "IsValid True, FailedUnexpectedly " + describe(failed.scripts())));
                }
            }
            // The preparation already passed, so this is an evaluator whose collect step does not see everything
            // its evaluate step does; the failures are phase one either way.
            case ScriptPhaseResult.Rejected rejected -> utxos.predicate(rejected.failures());
        }
    }

    /**
     * The bootstrap-phase script context (Conway/TxInfo.hs:572-581, certifying purpose :636-640): at protocol version
     * 9 a PlutusV3 {@code TxInfo}, its redeemer map and the certifying {@code ScriptInfo} carry {@code Nothing} for the
     * deposit of a {@code reg_cert} (tag 7) and the refund of an {@code unreg_cert} (tag 8). Plutus V1/V2 contexts are
     * the same at every protocol version ({@code transTxCertV1V2}, :383-397, translates them to
     * {@code DCertDelegRegKey} / {@code DCertDelegDeRegKey} without a deposit), so only a transaction whose
     * {@code plutusLanguagesUsed} contains PlutusV3 differs.
     *
     * @return why the evaluator cannot build Haskell's context, or null when it can (or the difference does not
     *         apply)
     */
    static String bootstrapPhaseContextUnsupported(TransitionContext ctx, ScriptPhaseEvaluator evaluator) {
        if (!CERTIFICATE_DEPOSITS_OMITTED.contains(ctx.protocolMajor())
                || !ctx.plutusLanguagesUsed().contains(PLUTUS_V3)
                || evaluator.translatesBootstrapPhaseCertificateDeposits()) {
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

    private static String describe(List<ScriptOutcome> scripts) {
        return scripts.stream().filter(s -> !s.success())
                .map(s -> s.purpose() + "[" + s.index() + "]" + (s.error() != null ? ": " + s.error() : ""))
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
