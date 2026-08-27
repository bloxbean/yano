package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.EpochArtifactContributor;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Records an epoch-stake artifact as a reference to the delegation snapshot the boundary already
 * wrote, rather than as a copy of it.
 *
 * <p>The reference is staged into the boundary's own write batch, so it commits with the snapshot
 * and cannot exist without it. The snapshot is then held back from pruning until the sink has
 * durably committed the rows, at which point acknowledgement releases the clamp.
 *
 * <p>The cost profile is the point of choosing this over a staged file: one small record instead
 * of a second copy of the whole distribution, paid for with retention rather than with I/O at the
 * boundary. Nothing is copied, so nothing about the transition gets slower as the stake
 * distribution grows.
 */
public final class EpochArtifactCollector implements EpochArtifactContributor {

    private final ProjectionOutboxStore outbox;
    private final SnapshotRetentionClamp clamp;
    private final boolean enabled;
    private final int sourceCodecVersion;
    /**
     * Base of the state version; each dataset appends its own part.
     *
     * <p>The replay worker writes {@code ledger-boundary-v1/<part>} where the part differs per
     * dataset - {@code snapshot} for epoch stake, {@code final} for the ada pot. Both pipelines
     * write this into {@code source_state_version}, so a single shared value would make identical
     * data look like it came from different producers.
     */
    private final String sourceStateBase;

    public EpochArtifactCollector(ProjectionOutboxStore outbox, SnapshotRetentionClamp clamp,
                                       boolean enabled, int sourceCodecVersion,
                                       String sourceStateBase) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clamp = clamp == null ? SnapshotRetentionClamp.NONE : clamp;
        this.enabled = enabled;
        this.sourceCodecVersion = sourceCodecVersion;
        this.sourceStateBase = Objects.requireNonNull(sourceStateBase, "sourceStateBase");
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void contributeEpochStake(int epoch, long boundarySlot, long boundaryBlockNumber,
                                     long rowCount, ProjectionStagingWriter writer) {
        if (!enabled) return;
        if (boundaryBlockNumber < 0 || boundarySlot < 0) {
            // Without the producing coordinate the finality gate cannot decide when this artifact
            // becomes eligible, and an artifact that is never eligible silently pins its snapshot.
            throw new IllegalStateException("epoch-stake artifact for epoch " + epoch
                    + " has no boundary coordinate; the transition must set it before snapshotting");
        }

        var ref = new ProjectionArtifactRef(
                ArchiveDatasetId.EPOCH_STAKE, epoch, boundaryBlockNumber, boundarySlot,
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                generationOf(epoch), sourceCodecVersion, sourceStateBase + "/snapshot",
                OptionalLong.of(rowCount), "",
                // No chain retention dependency. What this artifact needs is the delegation
                // snapshot, and that is held by the pruning clamp below - not by the node's
                // ability to replay blocks back to the boundary slot. Declaring the boundary slot
                // here made the drain pause permanently as soon as the common rollback floor
                // advanced past it, which on a fresh sync is immediately.
                //
                // Rollback below the boundary is handled structurally: it deletes this reference
                // along with the envelope that carries it.
                -1L);

        outbox.putArtifact(writer, boundaryBlockNumber, ref);

        // Lower the floor only. The floor means "oldest epoch still referenced", so writing this
        // epoch unconditionally would raise it past artifacts that are still pending and let
        // their snapshots be pruned out from under them. Raising it is the reader's job: it
        // recomputes the true minimum from open leases plus the outbox's pending set whenever an
        // artifact is acknowledged.
        int floor = clamp.protectedSnapshotFloorEpoch();
        if (floor < 0 || epoch < floor) clamp.protectSnapshotsFrom(epoch);
    }

    @Override
    public void contributeAdaPot(int epoch, long boundarySlot, long boundaryBlockNumber,
                                 long[] values, ProjectionStagingWriter writer) {
        if (!enabled) return;
        if (boundaryBlockNumber < 0 || boundarySlot < 0) {
            throw new IllegalStateException("ada-pot artifact for epoch " + epoch
                    + " has no boundary coordinate; the transition must set it before staging");
        }

        var ref = new ProjectionArtifactRef(
                ArchiveDatasetId.ADA_POT, epoch, boundaryBlockNumber, boundarySlot,
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE,
                "ada-pot:" + epoch, sourceCodecVersion, sourceStateBase + "/final",
                OptionalLong.of(1), "",
                // Nothing external is required: the evidence is inline, so no source can be
                // pruned out from under this artifact and nothing needs retaining for it.
                -1L,
                AdaPotArtifactRows.encode(values));

        outbox.putArtifact(writer, boundaryBlockNumber, ref);
        // Deliberately no clamp. The pot is not a pruned generation, and the evidence does not
        // live in the store at all - it travels with the reference.
    }

    /** Generation identity of the delegation snapshot for an epoch. */
    public static String generationOf(int epoch) {
        return "epoch-deleg-snapshot:" + epoch;
    }
}
