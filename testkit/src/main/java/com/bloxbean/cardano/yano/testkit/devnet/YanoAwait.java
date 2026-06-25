package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.model.NodeStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Polling helpers for asynchronous devnet progress.
 */
public final class YanoAwait {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

    private final YanoQueries queries;
    private final Duration timeout;
    private final Duration pollInterval;

    YanoAwait(YanoQueries queries) {
        this(queries, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    private YanoAwait(YanoQueries queries, Duration timeout, Duration pollInterval) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.timeout = requirePositive(timeout, "timeout");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
    }

    /**
     * Returns a copy with a different timeout.
     *
     * @param timeout timeout duration
     * @return await helper
     */
    public YanoAwait withTimeout(Duration timeout) {
        return new YanoAwait(queries, timeout, pollInterval);
    }

    /**
     * Returns a copy with a different poll interval.
     *
     * @param pollInterval poll interval
     * @return await helper
     */
    public YanoAwait withPollInterval(Duration pollInterval) {
        return new YanoAwait(queries, timeout, pollInterval);
    }

    /**
     * Waits until the supplied condition is true.
     *
     * @param condition condition to poll
     * @param description human-readable condition description
     * @return this helper
     */
    public YanoAwait until(BooleanSupplier condition, String description) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(description, "description");

        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() <= deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return this;
                }
                lastFailure = null;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
            sleep();
        }

        AssertionError error = new AssertionError("Timed out after " + timeout + " waiting for " + description);
        if (lastFailure != null) {
            error.addSuppressed(lastFailure);
        }
        throw error;
    }

    /**
     * Waits until the node reports running.
     *
     * @return this helper
     */
    public YanoAwait untilRunning() {
        return until(() -> queries.lifecycle().isRunning(), "node to be running");
    }

    /**
     * Waits until the node is running and not degraded.
     *
     * @return this helper
     */
    public YanoAwait untilReady() {
        return until(() -> {
            NodeStatus status = queries.status();
            return status != null && status.isRunning() && !status.isRuntimeDegraded();
        }, "node to be ready");
    }

    /**
     * Waits until the node no longer reports a degraded runtime.
     *
     * @return this helper
     */
    public YanoAwait untilNotDegraded() {
        return until(() -> {
            NodeStatus status = queries.status();
            return status != null && !status.isRuntimeDegraded();
        }, "runtime to be not degraded");
    }

    /**
     * Waits until the current slot reaches a target.
     *
     * @param slot target slot
     * @return this helper
     */
    public YanoAwait untilSlotAtLeast(long slot) {
        return until(() -> queries.currentSlot() >= slot, "slot >= " + slot);
    }

    /**
     * Waits until the current block number reaches a target.
     *
     * @param blockNumber target block number
     * @return this helper
     */
    public YanoAwait untilBlockAtLeast(long blockNumber) {
        return until(() -> queries.currentBlockNumber() >= blockNumber, "block >= " + blockNumber);
    }

    /**
     * Waits until the current epoch reaches a target.
     *
     * @param epoch target epoch
     * @return this helper
     */
    public YanoAwait untilEpochAtLeast(long epoch) {
        return until(() -> queries.currentEpoch() >= epoch, "epoch >= " + epoch);
    }

    /**
     * Waits until a transaction has outputs visible in the UTXO query surface.
     *
     * @param txHash transaction hash
     * @return this helper
     */
    public YanoAwait untilTxVisible(String txHash) {
        Objects.requireNonNull(txHash, "txHash");
        return until(() -> !queries.utxoState().getOutputsByTxHash(txHash).isEmpty(), "tx " + txHash + " to be visible");
    }

    private void sleep() {
        try {
            Thread.sleep(Math.max(1, pollInterval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for Yano devnet condition", e);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
