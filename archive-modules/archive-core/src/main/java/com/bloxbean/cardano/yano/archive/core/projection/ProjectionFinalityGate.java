package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;

import java.util.Objects;

/**
 * Greatest block the archive may ingest (ADR-039 §6).
 *
 * <p>This deliberately reuses {@link ArchiveSafetyWindows} rather than defining a
 * second archive-only finality calculation. Because {@code resolve} already enforces
 * {@code archiveFinalityBlocks >= rollbackRetentionBlocks >= securityParam}, the
 * combined depth {@code tip - max(archiveFinality, rollbackRetention)} reduces to
 * {@code tip - archiveFinalityBlocks}, so the simple form is used.
 *
 * <p>The runtime common rollback floor is deliberately <em>not</em> a term here. It is a
 * lower bound on what must remain reachable, not an upper bound on what may be
 * archived; combining them in a {@code min()} would make the archive most restricted
 * exactly when retention is best, and would stall at slot 0 under unbounded retention.
 * Retention health is asserted separately by {@link ProjectionRetentionHealth}.
 */
public final class ProjectionFinalityGate {

    private final ArchiveSafetyWindows windows;

    public ProjectionFinalityGate(ArchiveSafetyWindows windows) {
        this.windows = Objects.requireNonNull(windows, "windows");
    }

    /** Greatest rollback-safe block number, or -1 when the chain is still too short. */
    public long eligibleThrough(long tipBlockNumber) {
        if (tipBlockNumber < 0) return -1;
        long depth = Math.max(windows.archiveFinalityBlocks(), windows.rollbackRetentionBlocks());
        long eligible = tipBlockNumber - depth;
        return Math.max(eligible, -1);
    }

    public ArchiveSafetyWindows windows() {
        return windows;
    }
}
