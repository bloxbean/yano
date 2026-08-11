package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** One fresh executor/client/provider fixture. */
public interface ExecutorFixture extends AutoCloseable {
    /**
     * Returns the fresh executor product under test.
     *
     * @return the executor
     */
    AppEffectExecutor executor();

    /**
     * Returns the pending effect installed for this scenario.
     *
     * @return the stable pending effect
     */
    PendingEffect effect();

    /**
     * Creates an execution context for a bounded attempt.
     *
     * @param attempt the positive attempt number
     * @param submittedRef the prior submitted reference, or an empty array
     * @return the execution context
     */
    EffectExecutionContext context(int attempt, byte[] submittedRef);

    /**
     * Returns cumulative bounded observations of external interactions.
     *
     * @return the external probe
     */
    ExternalProbe probe();

    /**
     * Returns real or shape-equivalent Effect Runtime stats for this attempt.
     *
     * @return a bounded, non-secret runtime-stats snapshot
     */
    Map<String, Object> runtimeStats();

    /**
     * Returns real or shape-equivalent Effect Runtime status for {@link #effect()}.
     *
     * @return a bounded, non-secret runtime-status snapshot
     */
    Map<String, Object> runtimeStatus();

    /**
     * Events observed by a real test log appender installed before the
     * executor is constructed. An empty event list is valid; inactive capture
     * is not, because it would make the redaction assertion vacuous.
     *
     * @return the current bounded log observation
     */
    CapturedLogObservation capturedLogs();

    /**
     * Tear down the test log appender after the conformance suite has closed
     * the executor and inspected all cleanup events. Implementations must not
     * remove or deactivate capture from {@link #close()}.
     *
     * @throws Exception when log-capture teardown fails
     */
    void closeLogCapture() throws Exception;

    /**
     * Returns exact archived bytes for a returned detail hash, when present.
     *
     * @param detailHash the result's 32-byte detail commitment
     * @return the exact archived document bytes, or empty when no detail was emitted
     */
    default Optional<byte[]> archivedDetail(byte[] detailHash) {
        return Optional.empty();
    }

    /**
     * Waits until a simulated provider call has entered its blocked state.
     *
     * @param timeout the positive wait bound
     * @return {@code true} when the call was observed as blocked
     * @throws InterruptedException when the test thread is interrupted
     */
    default boolean awaitBlocked(Duration timeout) throws InterruptedException {
        return false;
    }

    /** Releases a provider call blocked by the optional blocked-call scenario. */
    default void unblock() {
    }

    @Override
    default void close() throws Exception {
        executor().close();
    }
}
