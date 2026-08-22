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

    /**
     * Rewards, captured as staged evidence.
     *
     * <p>IRREPRODUCIBLE, and deliberately not claimed otherwise. Reconstructing rewards would
     * require the complete deterministic input closure - every stake and pool input at the
     * boundary plus the exact calculation version - and that closure is not proven. Capturing the
     * final calculated rows is the honest representation: all components are preserved as
     * computed, with the calculation's own state version recorded alongside them.
     */
    public static ProjectionArtifactContract reward() {
        return new ProjectionArtifactContract(ArchiveDatasetId.REWARD,
                ArchiveSchemas.schema(ArchiveDatasetId.REWARD).projectionVersion(), 1,
                ProjectionArtifactRepresentation.STAGED_FILE,
                ProjectionArtifactReconstructibility.IRREPRODUCIBLE);
    }

    /**
     * DRep distribution, captured whole at the strictest class any column requires.
     *
     * <p>The {@code amount} column is a distribution over the same stake inputs epoch stake uses
     * and would be reconstructible on its own. {@code storedExpiry}, {@code dormantEpochs},
     * {@code effectiveExpiry} and {@code active} are boundary state that later governance
     * activity overwrites, and are not. Capturing the halves separately would let the persisted
     * amounts and the boundary-time state diverge - one re-derived, one recorded - so the whole
     * dataset is captured together as evidence.
     */
    public static ProjectionArtifactContract drepDistribution() {
        return new ProjectionArtifactContract(ArchiveDatasetId.DREP_DISTRIBUTION,
                ArchiveSchemas.schema(ArchiveDatasetId.DREP_DISTRIBUTION).projectionVersion(), 1,
                ProjectionArtifactRepresentation.STAGED_FILE,
                ProjectionArtifactReconstructibility.IRREPRODUCIBLE);
    }

    /**
     * Governance proposal status, captured as the observation it is.
     *
     * <p>{@code observationPhase}, {@code statusCode} and {@code decisionReason} describe a
     * decision taken AT a boundary. The governance state that follows records the outcome, not
     * the observation that produced it, so it is not an equivalent source: by the time it is
     * read, the reason a proposal was ratified or expired at that boundary is gone.
     */
    public static ProjectionArtifactContract governanceProposalStatus() {
        return new ProjectionArtifactContract(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                ArchiveSchemas.schema(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS).projectionVersion(), 1,
                ProjectionArtifactRepresentation.STAGED_FILE,
                ProjectionArtifactReconstructibility.IRREPRODUCIBLE);
    }

    /** Every contract this build maintains, in wire-name order. */
    public static ProjectionArtifactIdentity shipped() {
        return ProjectionArtifactIdentity.of(List.of(epochStake(), adaPot(),
                reward(), drepDistribution(), governanceProposalStatus()));
    }
}
