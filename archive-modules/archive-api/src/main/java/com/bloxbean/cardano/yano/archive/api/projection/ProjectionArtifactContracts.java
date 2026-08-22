package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;

import java.util.List;

/**
 * The epoch artifacts this build captures.
 *
 * <p>Deliberately a fixed list rather than configuration. Artifacts do not appear in the section
 * fingerprint, so a node that ran without one and later enables it would present an identical
 * fingerprint over an archive with a hole in it. Making the set a property of the build means the
 * stored contracts either match or the archive is refused - there is no configuration that can
 * silently disagree with what was captured.
 */
public final class ProjectionArtifactContracts {

    private ProjectionArtifactContracts() {}

    /**
     * Epoch stake, read from the delegation snapshot the boundary persists.
     *
     * <p>RECONSTRUCTIBLE because the snapshot is a persisted generation with retention, not
     * derived state that later blocks overwrite; IMMUTABLE_GENERATION because nothing is copied -
     * the artifact references the generation and a pruning clamp holds it until acknowledged.
     */
    public static ProjectionArtifactContract epochStake() {
        return new ProjectionArtifactContract(ArchiveDatasetId.EPOCH_STAKE,
                ArchiveSchemas.schema(ArchiveDatasetId.EPOCH_STAKE).projectionVersion(), 1,
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                ProjectionArtifactReconstructibility.RECONSTRUCTIBLE);
    }

    /**
     * The ADA pot, carried as inline evidence on its own reference.
     *
     * <p>RECONSTRUCTIBLE because the pot is persisted per epoch and has no retention prune path -
     * only rollback removes it, and rollback removes the reference with it. ATOMIC_EVIDENCE
     * because the pot is not written through the boundary's batch and is re-stored as rewards and
     * governance adjust it, so there is no generation to point at; eight numbers travel with the
     * reference instead, which is cheaper than any protection scheme and needs no lease.
     */
    public static ProjectionArtifactContract adaPot() {
        return new ProjectionArtifactContract(ArchiveDatasetId.ADA_POT,
                ArchiveSchemas.schema(ArchiveDatasetId.ADA_POT).projectionVersion(), 1,
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE,
                ProjectionArtifactReconstructibility.RECONSTRUCTIBLE);
    }

    /** Every contract this build maintains, in wire-name order. */
    public static ProjectionArtifactIdentity shipped() {
        return ProjectionArtifactIdentity.of(List.of(epochStake(), adaPot()));
    }
}
