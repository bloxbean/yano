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
     * Deterministic transition: apply every message of the finalized block,
     * writing state through {@code writer}. Keys/values written here form the
     * MPF state commitment whose root is bound into the block.
     */
    void apply(AppBlock block, AppStateWriter writer);

    /**
     * Deterministic transition WITH effect emission (ADR app-layer/010 F1).
     * The engine always invokes this overload; the default delegates to the
     * 2-arg form, so existing machines are untouched. Effect-emitting
     * machines override this (and may implement the 2-arg form as a plain
     * delegate — the engine never calls it directly).
     * <p>
     * {@code effects.emit(...)} records intent as consensus data — it never
     * performs I/O. Everything forbidden in {@code apply()} remains forbidden
     * here; emission must be a pure function of {@code (block, committed
     * state)}, and emission-logic changes MUST be height-gated
     * (ADR app-layer/010.1, {@code ActivationSchedule}).
     * <p>
     * Callers other than the framework should invoke THIS form. The 2-arg
     * form is the legacy entry point: on an effect-emitting machine it fails
     * deterministically at the first {@code emit()} (with earlier writes
     * already staged) — never catch that and commit.
     */
    default void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
        apply(block, writer);
    }

    /**
     * Deterministic callback when a consensus-incorporated effect outcome
     * commits (ADR app-layer/010 F8/F9): a member-attested {@code ~fx/result}
     * the framework interpreter accepted, or a deterministic EXPIRED
     * transition from the expiry sweep. Runs inside block application, before
     * this block's app messages are applied; writes join the same atomic
     * commit. Same determinism contract as {@code apply()}. Default: no-op.
     */
    default void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
    }

    /**
     * Legacy optional read hook retained for source compatibility and direct
     * library callers. The bounded ADR-011.3 runtime never invokes this hook:
     * a payload produced without the committed query context cannot honestly
     * be bound to the height and state root in {@link AppQueryResult}.
     */
    default byte[] query(String path, byte[] params) {
        throw new UnsupportedOperationException("query not supported by " + id());
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
     * <p>The default reports {@code UNSUPPORTED}. Existing state machines
     * remain source compatible, but must implement this overload before the
     * runtime can expose a root-attested query result.</p>
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
