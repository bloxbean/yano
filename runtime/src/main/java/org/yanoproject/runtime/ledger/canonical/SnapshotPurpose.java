package org.yanoproject.runtime.ledger.canonical;

/**
 * Why a {@link CanonicalSnapshot} is acquired. Only {@link #SHADOW} acquisitions can be refused
 * when the live-snapshot cap is reached (ADR-056 §3, bounded resources).
 */
public enum SnapshotPurpose {
    /** Mempool admission against the published base. Never refused. */
    ADMISSION,
    /** Block selection or building. Never refused. */
    BLOCK_BUILD,
    /** Rebuild of the mempool state after a canonical change. Never refused. */
    REBUILD,
    /** Shadow (comparison) engines. Refused when the cap is reached. */
    SHADOW,
    /**
     * Shadow sync (ADR-056 Phase 7a): the pre-block state of an applied block, captured inside the block's write
     * section by {@link CanonicalStateGate#captureInWriteSection}. Never refused and not counted against
     * {@code max-live-snapshots}: shadow sync bounds its own snapshots ({@code shadow-sync-max-in-flight}).
     */
    SHADOW_SYNC
}
