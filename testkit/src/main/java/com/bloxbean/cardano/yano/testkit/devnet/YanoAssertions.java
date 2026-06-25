package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.model.NodeStatus;

import java.util.Objects;

/**
 * Assertion helpers for common devnet checks.
 */
public final class YanoAssertions {
    private final YanoQueries queries;
    private final YanoSnapshots snapshots;

    YanoAssertions(YanoQueries queries, YanoSnapshots snapshots) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * Asserts that the node is running.
     *
     * @return this helper
     */
    public YanoAssertions nodeIsRunning() {
        if (!queries.lifecycle().isRunning()) {
            throw new AssertionError("Expected Yano devnet node to be running");
        }
        return this;
    }

    /**
     * Asserts that the node is stopped.
     *
     * @return this helper
     */
    public YanoAssertions nodeIsStopped() {
        if (queries.lifecycle().isRunning()) {
            throw new AssertionError("Expected Yano devnet node to be stopped");
        }
        return this;
    }

    /**
     * Asserts that the runtime is not degraded.
     *
     * @return this helper
     */
    public YanoAssertions runtimeNotDegraded() {
        NodeStatus status = queries.status();
        if (status == null) {
            throw new AssertionError("Expected runtime status to be available");
        }
        if (status.isRuntimeDegraded()) {
            throw new AssertionError("Expected runtime to be healthy, but it is degraded: "
                    + status.getRuntimeDegradedReason());
        }
        return this;
    }

    /**
     * Asserts that the current slot is at least the requested value.
     *
     * @param slot expected lower bound
     * @return this helper
     */
    public YanoAssertions slotAtLeast(long slot) {
        long actual = queries.currentSlot();
        if (actual < slot) {
            throw new AssertionError("Expected slot >= " + slot + ", got " + actual);
        }
        return this;
    }

    /**
     * Asserts that the current block number is at least the requested value.
     *
     * @param blockNumber expected lower bound
     * @return this helper
     */
    public YanoAssertions blockAtLeast(long blockNumber) {
        long actual = queries.currentBlockNumber();
        if (actual < blockNumber) {
            throw new AssertionError("Expected block >= " + blockNumber + ", got " + actual);
        }
        return this;
    }

    /**
     * Asserts that the current epoch is at least the requested value.
     *
     * @param epoch expected lower bound
     * @return this helper
     */
    public YanoAssertions epochAtLeast(long epoch) {
        long actual = queries.currentEpoch();
        if (actual < epoch) {
            throw new AssertionError("Expected epoch >= " + epoch + ", got " + actual);
        }
        return this;
    }

    /**
     * Asserts that a snapshot exists.
     *
     * @param name snapshot name
     * @return this helper
     */
    public YanoAssertions snapshotExists(String name) {
        if (!snapshots.exists(name)) {
            throw new AssertionError("Expected snapshot to exist: " + name);
        }
        return this;
    }

    /**
     * Asserts that a snapshot does not exist.
     *
     * @param name snapshot name
     * @return this helper
     */
    public YanoAssertions snapshotMissing(String name) {
        if (snapshots.exists(name)) {
            throw new AssertionError("Expected snapshot to be missing: " + name);
        }
        return this;
    }

    /**
     * Returns wallet assertions.
     *
     * @param wallet test wallet
     * @return wallet assertions
     */
    public YanoWalletAssertions wallet(TestWallet wallet) {
        return new YanoWalletAssertions(queries, wallet);
    }
}
