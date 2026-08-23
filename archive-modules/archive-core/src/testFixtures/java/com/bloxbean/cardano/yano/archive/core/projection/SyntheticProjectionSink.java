package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link ProjectionSink} used to exercise the outbox before any real backend
 * exists (ADR-039 Phase 2). It models the two properties that matter for the crash
 * matrix: rows and receipt become visible together, and a receipt survives a simulated
 * process restart while uncommitted work does not.
 */
public final class SyntheticProjectionSink implements ProjectionSink {

    /** Durable side: survives {@link #simulateRestart()}. */
    private final Map<Long, ProjectionReceipt> receipts = new LinkedHashMap<>();
    private final Map<Long, List<ArchiveRow>> committed = new LinkedHashMap<>();

    private ProjectionIdentity identity;
    private ProjectionSinkHealth health = ProjectionSinkHealth.ready();
    private final AtomicInteger appendCalls = new AtomicInteger();

    /** When positive, the next N appends fail after "staging" but before commit. */
    private int failNextAppends;
    /** When true, an append commits durably and then throws, as a crash after commit would. */
    private boolean throwAfterCommit;
    /** One-shot hook for forcing an interleaving after batch materialisation but before commit. */
    private Runnable beforeNextCommit;

    @Override
    public String engine() {
        return "synthetic";
    }

    @Override
    public void initialize(ProjectionIdentity expected) {
        if (identity != null && !identity.matches(expected)) {
            throw new ProjectionSinkException("synthetic sink already bound to " + identity.fingerprint());
        }
        this.identity = expected;
    }

    private ProjectionCoordinate coordinate = ProjectionCoordinate.NONE;

    @Override
    public ProjectionCoordinate coordinate() {
        return coordinate;
    }

    @Override
    public Optional<ProjectionReceipt> receiptFor(long firstBlock) {
        return Optional.ofNullable(receipts.get(firstBlock));
    }

    @Override
    public ProjectionReceipt append(ProjectionRowBatch batch, ArchiveArtifactReader artifacts) {
        appendCalls.incrementAndGet();
        if (failNextAppends > 0) {
            failNextAppends--;
            throw new ProjectionSinkException("injected sink failure before commit");
        }

        Map<String, Long> rowCounts = new HashMap<>();
        for (ArchiveRow row : batch.rows()) {
            rowCounts.merge(row.table(), 1L, Long::sum);
        }

        Runnable hook = beforeNextCommit;
        beforeNextCommit = null;
        if (hook != null) hook.run();

        ProjectionReceipt receipt = ProjectionReceipt.of(batch, rowCounts, Instant.EPOCH);
        // Rows and receipt become visible together.
        for (long block = batch.firstBlock(); block <= batch.lastBlock(); block++) {
            committed.put(block, List.of());
        }
        receipts.put(batch.firstBlock(), receipt);

        if (throwAfterCommit) {
            throwAfterCommit = false;
            throw new ProjectionSinkException("injected failure after durable commit");
        }
        return receipt;
    }

    private final java.util.List<ProjectionMaintenance.Budget> maintenanceCalls = new ArrayList<>();
    private ProjectionMaintenance.Result nextMaintenanceResult;

    /**
     * Records the budget it was given so tests can assert the coordinator only offers
     * compaction when it is genuinely idle at tip.
     */
    @Override
    public ProjectionMaintenance.Result maintain(ProjectionMaintenance.Budget budget) {
        maintenanceCalls.add(budget);
        if (nextMaintenanceResult != null) {
            var result = nextMaintenanceResult;
            nextMaintenanceResult = null;
            return result;
        }
        return new ProjectionMaintenance.Result(ProjectionMaintenance.Outcome.COMPLETED,
                java.time.Duration.ZERO, java.util.OptionalLong.of(10),
                java.util.OptionalLong.of(budget.compactionAllowed() ? 2 : 10),
                java.util.OptionalLong.of(0), 1, 1,
                java.time.Duration.ZERO, Optional.empty());
    }

    public java.util.List<ProjectionMaintenance.Budget> maintenanceCalls() {
        return java.util.List.copyOf(maintenanceCalls);
    }

    public void nextMaintenanceResult(ProjectionMaintenance.Result result) {
        this.nextMaintenanceResult = result;
    }

    @Override
    public ProjectionSinkHealth health() {
        return health;
    }

    @Override
    public void close() {
        health = ProjectionSinkHealth.unavailable("closed");
    }

    // ---------------------------------------------------------------- test hooks

    public void failNextAppends(int count) {
        this.failNextAppends = count;
    }

    public void throwAfterNextCommit() {
        this.throwAfterCommit = true;
    }

    public void beforeNextCommit(Runnable hook) {
        this.beforeNextCommit = java.util.Objects.requireNonNull(hook, "hook");
    }

    /** Drops in-flight state; durable receipts and rows remain, as after a real restart. */
    public void simulateRestart() {
        failNextAppends = 0;
        throwAfterCommit = false;
        beforeNextCommit = null;
        health = ProjectionSinkHealth.ready();
    }

    public int appendCalls() {
        return appendCalls.get();
    }

    public List<Long> committedBlocks() {
        return new ArrayList<>(committed.keySet());
    }

    public Map<Long, ProjectionReceipt> receipts() {
        return Map.copyOf(receipts);
    }

    /** Replaces a stored receipt, modelling a differently shaped job for the same range. */
    public void forceReceipt(long firstBlock, ProjectionReceipt receipt) {
        receipts.put(firstBlock, receipt);
    }
}
