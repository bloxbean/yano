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

    /**
     * Outpoint to address for outputs the live UTXO subsystem never applies.
     *
     * <p>Shelley+ consumed addresses are captured during apply, while the spent output is still
     * in {@code cfUnspent}. Byron has no such moment: the UTXO path returns on the
     * {@code block() == null} sentinel and never applies a Byron transaction, so an output a
     * Byron block created exists in no live column family and is unresolvable when a later
     * block - Byron or Shelley+ - spends it. This is the archive-owned resolver ADR-039
     * requires for Byron address participation, seeded from the Byron genesis distribution and
     * advanced in canonical order.
     */
    public static final String PROJ_BYRON_UTXO = "proj_byron_utxo";

    public static final String[] ALL =
            {PROJ_HEADER, PROJ_SECTION, PROJ_META, PROJ_ARTIFACT, PROJ_BYRON_UTXO};
}
