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
    SHADOW
}
