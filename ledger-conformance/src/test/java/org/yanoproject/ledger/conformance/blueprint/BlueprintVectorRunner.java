package org.yanoproject.ledger.conformance.blueprint;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSets;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVector;
import org.yanoproject.ledger.rules.fixtures.blueprint.NewEpochStateDecoder;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays one cardano-blueprint vector through the Java engine (ADR-056 Phase 7b).
 *
 * <p>The initial {@code NewEpochState} becomes an {@code InMemoryLedgerView} ({@link NewEpochStateDecoder}); each
 * transaction event is validated under rule {@code LEDGER}, origin {@code SYNC}, at the event's slot and its epoch
 * under the vector's fixed epoch size, at the protocol version of the state's parameters, with the vector's network
 * id and system start and the julc phase-2 evaluator without a forecast horizon (the Imp tests' epoch info is
 * fixed, so {@code TimeTranslationPastHorizon} cannot happen there). The engine's verdict is compared with the
 * vector's: the vectors record only whether Haskell applied the transaction, not the failure.</p>
 *
 * <p><b>Chaining.</b> Between transactions the state follows Haskell, not the engine: a transaction Haskell applied is
 * applied to an {@link OverlayLedgerView} with the engine's effects, or, when the engine rejected it, with the effects
 * {@link TxEffectsDeriver} computes for Haskell's verdict; a transaction Haskell rejected changes nothing. So one
 * disagreement does not cascade into the rest of the vector.</p>
 *
 * <p><b>Tick and epoch events are skipped</b>, as in Amaru's harness: nothing applies the epoch boundary (rewards,
 * pool reaping, ratification and enactment, DRep expiry, parameter updates). A disagreement after a skipped boundary (a
 * transaction in a later epoch than the initial state's {@code nesEL}; the events themselves are not trusted for this) is
 * classified as
 * {@link Status#REQUIRES_EPOCH}, not as a failure; one before any boundary is a {@link Status#FAILED}.</p>
 */
public final class BlueprintVectorRunner {

    /** A vector's outcome. */
    public enum Status {
        /** Every transaction's verdict matches Haskell's. */
        PASSED,
        /** A transaction's verdict differs before any skipped epoch boundary. */
        FAILED,
        /** The first disagreement comes after an epoch boundary the harness skipped. */
        REQUIRES_EPOCH,
        /** The initial state has a part the decoder cannot read (fail closed). */
        UNDECODABLE,
        /** The state's protocol version has no Java rule set. */
        UNSUPPORTED_VERSION
    }

    /**
     * One transaction's result.
     *
     * @param index         the transaction's position among the vector's transactions
     * @param slot          the event's slot
     * @param expected      Haskell's verdict (applied or not)
     * @param observation   the engine's verdict and failures
     * @param afterBoundary whether an epoch boundary was skipped before it
     */
    public record TxResult(int index, long slot, boolean expected, Observation observation, boolean afterBoundary) {

        public boolean matches() {
            return observation.valid() == expected;
        }

        /** @return e.g. {@code tx 3 (slot 3900961): Haskell applied it, java: UTXO.ValueNotConservedUTxO} */
        public String describe() {
            return "tx " + index + " (slot " + slot + "): Haskell " + (expected ? "applied it" : "rejected it")
                    + ", java " + (observation.valid() ? "accepted it"
                    : "rejected it: " + observation.label() + " — " + Observation.abbreviate(observation.first().raw()));
        }
    }

    /**
     * A vector's result.
     *
     * @param vector          the vector
     * @param protocolMajor   the protocol major version of the initial state's parameters, or -1 when undecodable
     * @param status          the outcome
     * @param reason          why it is not {@link Status#PASSED}, or empty
     * @param transactions    per transaction event, in order (empty when not run)
     * @param populated       the non-empty parts of the initial state the decoder read
     * @param stateDiverged   true when Haskell's effects could not be derived for a transaction the engine rejected, so
     *                        the rest of the vector was not run
     * @param stateCheck      the comparison of the reached state with the vector's final state ({@link FinalStateCheck})
     */
    public record VectorResult(BlueprintVector vector, int protocolMajor, Status status, String reason,
                               List<TxResult> transactions, Map<String, Integer> populated, boolean stateDiverged,
                               StateCheck stateCheck) {

        public VectorResult {
            transactions = List.copyOf(transactions);
            populated = Map.copyOf(populated);
        }

        /** @return the first transaction whose verdict differs, or null */
        public TxResult firstMismatch() {
            return transactions.stream().filter(t -> !t.matches()).findFirst().orElse(null);
        }
    }

    /**
     * The final-state comparison of a vector.
     *
     * @param outcome     whether it ran and what it found
     * @param differences what differs, or why the final state could not be decoded / the check does not apply
     */
    public record StateCheck(Outcome outcome, List<String> differences) {

        public enum Outcome {
            /** The reached state equals the vector's final state. */
            MATCHED,
            /** They differ ({@link #differences()}). */
            DIFFERS,
            /** The final state has a part the decoder cannot read. */
            FINAL_UNDECODABLE,
            /** The vector crosses an epoch boundary (or was not run to the end): no comparison. */
            NOT_APPLICABLE
        }

        public StateCheck {
            differences = List.copyOf(differences);
        }

        /**
         * Why a vector that stays in one epoch is not compared: an Imp test changed the parameters directly
         * ({@code modifyPParams}), which the vector records as no event.
         */
        public static final String PARAMS_CHANGED = "protocol parameters changed outside the events";

        static StateCheck notApplicable(String why) {
            return new StateCheck(Outcome.NOT_APPLICABLE, List.of(why));
        }

        /** @return true when the vector's parameters changed without an event */
        public boolean paramsChangedOutsideEvents() {
            return outcome == Outcome.NOT_APPLICABLE && differences.contains(PARAMS_CHANGED);
        }
    }

    private final JavaLedgerValidationEngine engine;
    private final TxEffectsDeriver deriver = new TxEffectsDeriver();
    private final NewEpochStateDecoder decoder;

    /** The runner with engine {@code java-julc} (the gate's). */
    public BlueprintVectorRunner(NewEpochStateDecoder decoder) {
        this(decoder, JavaViewEngine.create(new JulcScriptPhaseEvaluator()));
    }

    /** The runner with another engine (ADR-056 Phase 7c: {@code java-scalus}). */
    public BlueprintVectorRunner(NewEpochStateDecoder decoder, JavaLedgerValidationEngine engine) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Validates one of the vector's transactions against {@code view} in the environment the runner uses (the
     * protocol version of the view's parameters).
     */
    public Observation validate(BlueprintVector vector, BlueprintVector.Event.Tx tx, LedgerView view) {
        ProtocolParams params = view.protocolParams().require("protocol parameters");
        BlueprintVector.Config config = vector.config();
        ValidationEnv env = new ValidationEnv(tx.slot(), config.epochOf(tx.slot()), params.getProtocolMajorVer(),
                params.getProtocolMinorVer(), config.networkId() == 1 ? NetworkId.MAINNET : NetworkId.TESTNET,
                new SlotConfig(BlueprintVector.Config.SLOT_LENGTH_MILLIS, 0, config.systemStartEpochMillis()),
                new byte[32]);
        return Observation.of(engine.validate(new TxValidationRequest(tx.cbor(), view, env,
                TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
    }

    public VectorResult run(BlueprintVector vector) {
        NewEpochStateDecoder.Decoded state = decoder.decode(vector.initialState());
        if (!state.ok()) {
            return new VectorResult(vector, state.params() != null ? state.params().getProtocolMajorVer() : -1,
                    Status.UNDECODABLE, String.join("; ", state.unsupported()), List.of(), state.populated(), false,
                    StateCheck.notApplicable("initial state undecodable"));
        }
        int major = state.params().getProtocolMajorVer();
        int minor = state.params().getProtocolMinorVer();
        if (ConwayRuleSets.forProtocol(major).isEmpty()) {
            return new VectorResult(vector, major, Status.UNSUPPORTED_VERSION, "protocol version " + major
                    + " has no Java rule set (" + ConwayRuleSets.SUPPORTED + ")", List.of(), state.populated(), false,
                    StateCheck.notApplicable("protocol version not supported"));
        }
        BlueprintVector.Config config = vector.config();
        if (!config.consistent()) {
            return new VectorResult(vector, major, Status.UNDECODABLE, "config slot " + config.slot() + " is not in its "
                    + "epoch " + config.epoch() + " (epoch size " + config.epochSize() + ")", List.of(),
                    state.populated(), false, StateCheck.notApplicable("inconsistent config"));
        }
        NetworkId network = config.networkId() == 1 ? NetworkId.MAINNET : NetworkId.TESTNET;
        SlotConfig slotConfig = new SlotConfig(BlueprintVector.Config.SLOT_LENGTH_MILLIS, 0,
                config.systemStartEpochMillis());

        OverlayLedgerView overlay = OverlayLedgerView.over(state.view());
        FinalStateCheck.Touched touched = new FinalStateCheck.Touched();
        touched.add(state.keys());
        boolean boundarySkipped = false;
        List<TxResult> results = new ArrayList<>();
        boolean diverged = false;
        for (BlueprintVector.Event event : vector.events()) {
            if (!(event instanceof BlueprintVector.Event.Tx tx)) {
                // PassTick / PassEpoch: skipped. Whether a boundary lies between the state and a transaction is
                // decided by the state's epoch (nesEL), not by these events: the dump can record a PassEpoch the
                // initial state already includes (conway/fail-gov-expirationepochtoosmall: nesEL 900, config 899).
                continue;
            }
            long epoch = config.epochOf(tx.slot());
            if (epoch != state.epoch()) {
                boundarySkipped = true;
            }
            ValidationEnv env = new ValidationEnv(tx.slot(), epoch, major, minor, network, slotConfig, new byte[32]);
            byte[] cbor = tx.cbor();
            TxValidationOutcome outcome = engine.validate(new TxValidationRequest(cbor, overlay, env,
                    TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
            results.add(new TxResult(results.size(), tx.slot(), tx.success(), Observation.of(outcome),
                    boundarySkipped));
            if (!tx.success()) {
                continue;
            }
            if (outcome instanceof TxValidationOutcome.Valid valid) {
                overlay = overlay.apply(valid.effects());
                touched.add(valid.effects());
                continue;
            }
            // Haskell applied a transaction the engine rejected: follow Haskell.
            try {
                Transaction decoded = Transaction.deserialize(cbor);
                TxEffects effects = deriver.derive(cbor, decoded, null, overlay, env, decoded.isValid());
                overlay = overlay.apply(effects);
                touched.add(effects);
            } catch (Exception e) {
                diverged = true;
                break;
            }
        }

        TxResult first = results.stream().filter(t -> !t.matches()).findFirst().orElse(null);
        Status status;
        String reason = "";
        if (first == null && !diverged) {
            status = Status.PASSED;
        } else if (first == null) {
            status = Status.FAILED;
            reason = "Haskell's effects could not be derived";
        } else {
            status = first.afterBoundary() ? Status.REQUIRES_EPOCH : Status.FAILED;
            reason = first.describe();
        }
        return new VectorResult(vector, major, status, reason, results, state.populated(), diverged,
                stateCheck(vector, state, overlay, touched, diverged, boundarySkipped));
    }

    private StateCheck stateCheck(BlueprintVector vector, NewEpochStateDecoder.Decoded initial, LedgerView reached,
                                  FinalStateCheck.Touched touched, boolean diverged, boolean boundarySkipped) {
        if (diverged) {
            return StateCheck.notApplicable("not run to the end");
        }
        if (boundarySkipped) {
            return StateCheck.notApplicable("crosses an epoch boundary");
        }
        NewEpochStateDecoder.Decoded fin = decoder.decode(vector.finalState());
        if (!fin.ok()) {
            return new StateCheck(StateCheck.Outcome.FINAL_UNDECODABLE, fin.unsupported());
        }
        if (fin.epoch() != initial.epoch()) {
            return StateCheck.notApplicable("final state in epoch " + fin.epoch() + ", initial " + initial.epoch());
        }
        if (!fin.params().equals(initial.params())) {
            return StateCheck.notApplicable(StateCheck.PARAMS_CHANGED);
        }
        try {
            List<String> differences = FinalStateCheck.compare(fin, reached, touched);
            return new StateCheck(differences.isEmpty() ? StateCheck.Outcome.MATCHED : StateCheck.Outcome.DIFFERS,
                    differences);
        } catch (RuntimeException e) {
            return new StateCheck(StateCheck.Outcome.DIFFERS, List.of("comparison failed: " + e));
        }
    }
}
