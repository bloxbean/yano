package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

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

    /** Optional read path exposed via REST {@code /query} and the Java API. */
    default byte[] query(String path, byte[] params) {
        throw new UnsupportedOperationException("query not supported by " + id());
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
