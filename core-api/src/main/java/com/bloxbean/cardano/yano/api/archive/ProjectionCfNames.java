package com.bloxbean.cardano.yano.api.archive;

/**
 * Column families backing the canonical projection outbox (ADR-039 §2, §8).
 *
 * <p>These names live in {@code core-api} because both sides need them and neither may
 * depend on the other: the runtime declares the column families when it opens the
 * shared chainstate, and the archive modules read and write them. Sharing one physical
 * RocksDB is what lets a contributor commit its projection section inside the same
 * {@code WriteBatch} as the state that section was derived from, which is the whole
 * atomicity argument of ADR-039 — no global cross-subsystem transaction is needed.
 *
 * <p>Nothing here depends on DuckLake or standalone SQLite.
 */
public final class ProjectionCfNames {
    private ProjectionCfNames() {}

    /** Per-block canonical identity record; one key per applied block. */
    public static final String PROJ_HEADER = "proj_header";

    /** Per-section manifests and their ordered chunk payloads. */
    public static final String PROJ_SECTION = "proj_section";

    /** Contributor cursors, projection identity, acknowledgement coordinate. */
    public static final String PROJ_META = "proj_meta";

    /** Epoch-artifact references and their durable leases. */
    public static final String PROJ_ARTIFACT = "proj_artifact";

    public static final String[] ALL =
            {PROJ_HEADER, PROJ_SECTION, PROJ_META, PROJ_ARTIFACT};
}
