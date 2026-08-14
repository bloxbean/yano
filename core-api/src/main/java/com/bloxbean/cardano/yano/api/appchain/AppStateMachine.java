package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;

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
     * The runtime invokes this overload while selecting a proposal, with the
     * committed state at {@code candidateHeight - 1}. Versioned state machines
     * should override it when the valid topic or payload set changes at an
     * activation height.
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
    default java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider>
    proofSubjectProviders() {
        return java.util.List.of();
    }

    /** Authenticated snapshot series this machine can populate when enabled by the chain. */
    default java.util.List<com.bloxbean.cardano.yano.api.appchain.snapshot
            .AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
        return java.util.List.of();
    }

    /** Incremental source-commitment verifiers for every declared snapshot series. */
    default java.util.List<com.bloxbean.cardano.yano.api.appchain.snapshot
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

    /** Admission verdict for {@link #validate}. */
    final class AdmissionResult {
        private static final AdmissionResult ACCEPTED = new AdmissionResult(true, null);

        private final boolean accepted;
        private final String reason;

        private AdmissionResult(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        public static AdmissionResult accept() {
            return ACCEPTED;
        }

        public static AdmissionResult reject(String reason) {
            return new AdmissionResult(false, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String reason() {
            return reason;
        }
    }
}
