package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Deterministic effect emission, handed to
 * {@code AppStateMachine.apply(block, writer, effects)} (ADR app-layer/010
 * F1). {@code emit()} records intent as consensus data — it never performs
 * I/O. Everything forbidden inside {@code apply()} stays forbidden here.
 * <p>
 * Ordinals are assigned in emission order within the block; apply is
 * single-threaded and messages are totally ordered, so every node derives the
 * identical effect list or the chain stalls on the state-root check.
 */
public interface AppEffectEmitter {

    /**
     * Record one effect intent; returns its deterministic id.
     *
     * @throws EffectLimitExceededException deterministically, on every node,
     *         when {@code effects.max-per-block} or
     *         {@code effects.max-payload-bytes} is exceeded — a machine bug
     *         surfaced by the conformance harness, never a divergence vector
     * @throws IllegalStateException when effects are disabled for this chain
     *         ({@code effects.enabled=false}) — also deterministic
     */
    EffectId emit(EffectIntent intent);

    /**
     * Number of currently open {@link ResultPolicy#CHAIN} effects
     * (consensus-derived, identical on every node) — a deterministic
     * backpressure signal a machine may use to defer or reject new work.
     */
    long pendingCount();

    /**
     * An emitter that deterministically rejects emission with the given
     * reason — the single implementation behind "effects unavailable here"
     * (legacy 2-arg apply path, effects-disabled chains). {@code
     * pendingCount()} reports 0.
     */
    static AppEffectEmitter rejecting(String reason) {
        return new AppEffectEmitter() {
            @Override
            public EffectId emit(EffectIntent intent) {
                throw new IllegalStateException(reason);
            }

            @Override
            public long pendingCount() {
                return 0;
            }
        };
    }
}
