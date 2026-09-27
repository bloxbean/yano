package org.yanoproject.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectResult;
import org.yanoproject.api.appchain.observation.AppObservationEmitter;
import org.yanoproject.api.appchain.observation.ObservationResult;
import org.yanoproject.api.appchain.transition.TransitionKernel;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The developer-facing SPI of the Yano app-chain framework: a deterministic
 * state machine over opaque app messages (ADR app-layer/005 D10).
 * <p>
 * The framework supplies networking, ordering (sequencer + finality certs),
 * persistence, state commitment (MPF) and L1 anchoring; the application
 * supplies this transition function. The message {@code body} is an opaque
 * blob — this is the only layer that interprets it.
 * <p>
 * Determinism contract: {@link #apply} is invoked exactly once per finalized
 * block, in height order, on every member node, and must produce identical
 * writes everywhere. All writes commit atomically with the block and the
 * state root.
 * <p>
 * <b>Forbidden inside {@code apply()}</b> — a nondeterministic machine stalls
 * its chain (followers reject the state root): wall-clock time
 * ({@code System.currentTimeMillis}, {@code Instant.now} — use
 * {@code block.timestamp()}), randomness, network or file I/O,
 * environment/system-property reads, iteration over unordered collections
 * ({@code HashMap}/{@code HashSet} — use ordered ones), and locale/charset
 * dependent or library-default serialization. Verify custom machines with the
 * conformance harness ({@code StateMachineConformance} in yano-runtime)
 * before deploying (ADR app-layer/008.1 I1.6).
 */
public interface AppStateMachine {

    /** Stable identifier of this state machine implementation (e.g. "ordered-log"). */
    String id();

    /** Optional pure command kernel for atomic composition; standalone apply remains the entry point. */
    default Optional<TransitionKernel<?, ?>> transitionKernel() {
        return Optional.empty();
    }

    /** Called once before the first block is applied / on node start. */
    default void init(AppStateReader state, AppChainInfo info) {
    }

    /**
     * Mempool admission — fast, side-effect free, may run concurrently.
     * Envelope integrity/auth/membership have already been verified.
     */
    default AdmissionResult validate(AppMessage message) {
        return AdmissionResult.accept();
    }

    /**
     * Height- and state-aware mempool admission for the next candidate block.
     * <p>
     * The runtime invokes this overload before pooling local submissions and
     * again while selecting a proposal, with the
     * committed state at {@code candidateHeight - 1}. Versioned state machines
     * should override it when the valid topic or payload set changes at an
     * activation height. Local admission is advisory: a subsequent candidate
     * may see different state. Implementations must be side-effect free and
     * safe for concurrent invocation, and must not retain the supplied reader.
     */
    default AdmissionResult validateForBlock(
            AppMessage message,
            long candidateHeight,
            AppStateReader committedState
    ) {
        return validate(message);
    }

    /**
     * Local operator admission for one member-signed reserved-topic command.
     * Ordinary {@link AppChainGateway#submit(String, byte[])} never reaches
     * this hook. Implementations must fail closed; the runtime calls it before
     * signing or diffusing a privileged system message.
     */
    default AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        return AdmissionResult.reject("Privileged state-machine system messages are unsupported");
    }

    /** Cached, off-consensus operational diagnostics; never used for validity. */
    default java.util.Map<String, Object> operationalStatus() {
        return java.util.Map.of();
    }

    /** Immutable application/composition discovery data; never used as mutable health state. */
    default AppCapabilityManifest capabilityManifest() {
        return AppCapabilityManifest.application(id());
    }

    /** Data-only typed proof contracts contributed by this application profile. */
    default java.util.List<org.yanoproject.api.appchain.proof.ProofSubjectProvider>
    proofSubjectProviders() {
        return java.util.List.of();
    }

    /** Authenticated snapshot series this machine can populate when enabled by the chain. */
    default java.util.List<org.yanoproject.api.appchain.snapshot
            .AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
        return java.util.List.of();
    }

    /** Incremental source-commitment verifiers for every declared snapshot series. */
    default java.util.List<org.yanoproject.api.appchain.snapshot
            .AuthenticatedSnapshotSourceCommitmentV1> authenticatedSnapshotSourceCommitments() {
        return java.util.List.of();
    }

    /**
     * Deterministic transition with block-scoped, replayable inputs and effect
     * emission (ADR-031). This is the only execution entry point.
     * <p>
     * {@code effects.emit(...)} records intent as consensus data — it never
     * performs I/O. Everything forbidden in {@code apply()} remains forbidden
     * here; emission must be a pure function of {@code (context, committed
     * state)}, and emission-logic changes MUST be height-gated
     * (ADR app-layer/010.1, {@code ActivationSchedule}).
     */
    void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects);

    /**
     * Observation-aware deterministic transition. Existing machines remain
     * source and binary compatible through this default bridge.
     */
    default void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects,
            AppObservationEmitter observations
    ) {
        apply(context, writer, effects);
    }

    /**
     * Deterministic callback when a consensus-incorporated effect outcome
     * commits (ADR app-layer/010 F8/F9): a member-attested {@code ~fx/result}
     * the framework interpreter accepted, or a deterministic EXPIRED
     * transition from the expiry sweep. Runs inside block application, before
     * this block's app messages are applied; writes join the same atomic
     * commit. Same determinism contract as {@code apply()}. Default: no-op.
     */
    default void onEffectResult(
            AppBlockExecutionContext context,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
    }

    /**
     * Observation-aware effect callback. One block-scoped observation emitter
     * is shared by every framework callback and {@link #apply} invocation.
     */
    default void onEffectResult(
            AppBlockExecutionContext context,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects,
            AppObservationEmitter observations
    ) {
        onEffectResult(context, result, writer, effects);
    }

    /**
     * Deterministic callback for one consensus-incorporated observation result.
     * Cancellation is audit-only in v1 and does not invoke this callback.
     */
    default void onObservationResult(
            AppBlockExecutionContext context,
            ObservationResult result,
            AppStateWriter writer,
            AppEffectEmitter effects,
            AppObservationEmitter observations
    ) {
    }

    /**
     * Query a root-fixed snapshot of committed state outside deterministic
     * block execution. This callback is off-consensus: it must be read-only,
     * must not emit effects or mutate state-machine fields, and may overlap a
     * later {@link #apply} on another thread. Its payload must be a function
     * only of {@code path}, {@code params}, and the supplied snapshot; external
     * I/O, wall-clock time, and randomness would not be root-attested. The
     * supplied reader is valid only for the dynamic extent of this callback and
     * must not be retained. Its
     * {@link AppQueryContext#committedHeight()}, {@link AppStateReader#stateRoot()}
     * and every {@link AppStateReader#get(byte[])} read refer to the same
     * committed snapshot and never advance while the query runs, even when
     * later blocks commit concurrently.
     *
     * <p>The runtime bounds request/response size, concurrency and execution
     * time. Implementations must still avoid unbounded CPU or retained work:
     * timing out a caller interrupts the callback, but its generation remains
     * alive until the callback actually exits. Child work that survives this
     * method is forbidden; the host cannot safely manage arbitrary threads
     * created by an in-process plugin.</p>
     *
     * <p>A plugin may deliberately throw {@link AppQueryException} only with
     * {@link AppQueryException.Code#UNSUPPORTED} for an unknown query path or
     * {@link AppQueryException.Code#INVALID_REQUEST} for invalid parameters.
     * Other reason codes are host-owned; unexpected plugin failures are
     * redacted and mapped to {@link AppQueryException.Code#FAILED}.</p>
     *
     * <p>The default reports {@code UNSUPPORTED}.</p>
     */
    default byte[] query(String path, byte[] params, AppQueryContext state) {
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "committed query not supported by " + id());
    }

    /**
     * Admission verdict for {@link #validate}.
     *
     * <p>A rejection carries a reason, which remote callers see only as a bounded symbolic code, and optional
     * structured details (bloxbean/yano#153). Details are advisory diagnostics for local ingress; they never affect
     * consensus. {@link AppSubmissionRejectedException} keeps only allowlisted, grammar-checked details, so a plugin
     * cannot echo arbitrary text to a client through them.
     */
    final class AdmissionResult {
        /** Maximum detail entries retained from a rejection's details map. */
        public static final int MAX_DETAILS = 16;
        private static final AdmissionResult ACCEPTED = new AdmissionResult(true, null, Map.of());

        private final boolean accepted;
        private final String reason;
        private final Map<String, Object> details;

        private AdmissionResult(boolean accepted, String reason, Map<String, Object> details) {
            this.accepted = accepted;
            this.reason = reason;
            this.details = details;
        }

        public static AdmissionResult accept() {
            return ACCEPTED;
        }

        public static AdmissionResult reject(String reason) {
            return new AdmissionResult(false, reason, Map.of());
        }

        /**
         * Rejects with a symbolic reason and structured details, for example a rule id and its deny code. The
         * reason stays the code; details are not parsed from it. At most {@link #MAX_DETAILS} visited entries are
         * considered, and only those with a text key and a {@code String}, {@code Long}, {@code Integer},
         * {@code Short} or {@code Byte} value are retained, so no plugin-defined object is carried across the plugin
         * boundary. Retained values are not otherwise checked; {@link AppSubmissionRejectedException} applies the
         * allowlist and grammars before anything reaches a client.
         *
         * @param reason symbolic code, {@code [A-Z_]{1,32}} to be shown to remote callers
         * @param details detail values by key; {@code null} means none
         */
        public static AdmissionResult reject(String reason, Map<String, ?> details) {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (details != null) {
                Iterator<? extends Map.Entry<String, ?>> entries = details.entrySet().iterator();
                for (int visited = 0; visited < MAX_DETAILS && entries.hasNext(); visited++) {
                    Map.Entry<String, ?> entry = entries.next();
                    if (entry == null) continue;
                    Object value = entry.getValue();
                    if (entry.getKey() instanceof String key && (value instanceof String || value instanceof Long
                            || value instanceof Integer || value instanceof Short || value instanceof Byte)) {
                        copy.put(key, value);
                    }
                }
            }
            return new AdmissionResult(false, reason, Collections.unmodifiableMap(copy));
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String reason() {
            return reason;
        }

        /**
         * The rejection's retained details, not yet checked against the allowlist or grammars; empty for an
         * acceptance or a reason-only rejection.
         */
        public Map<String, Object> details() {
            return details;
        }
    }
}
