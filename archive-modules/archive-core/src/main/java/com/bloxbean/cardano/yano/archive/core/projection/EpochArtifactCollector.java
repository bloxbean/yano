package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.EpochArtifactContributor;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;

import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Records an epoch-stake artifact as a reference to the delegation snapshot the boundary already
 * wrote, rather than as a copy of it.
 *
 * <p>A pending intent is staged into the boundary-state write batch, so it commits with the
 * snapshot and cannot exist without it. The reference itself is attached later from the first
 * applying block's batch. The snapshot is held back from pruning until acknowledgement.
 *
 * <p>The cost profile is the point of choosing this over a staged file: one small record instead
 * of a second copy of the whole distribution, paid for with retention rather than with I/O at the
 * boundary. Nothing is copied, so nothing about the transition gets slower as the stake
 * distribution grows.
 */
public final class EpochArtifactCollector implements EpochArtifactContributor {

    @FunctionalInterface
    public interface FailureListener {
        void failed(ArchiveDatasetId dataset, int epoch, long carrierBlockNumber,
                    RuntimeException failure);

        FailureListener NOOP = (dataset, epoch, carrier, failure) -> { };
    }

    private final ProjectionOutboxStore outbox;
    private final SnapshotRetentionClamp clamp;
    private final Map<ArchiveDatasetId, Integer> projectedFrom;
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
    private final FailureListener failureListener;

    public EpochArtifactCollector(ProjectionOutboxStore outbox, SnapshotRetentionClamp clamp,
                                       boolean enabled, int sourceCodecVersion,
                                       String sourceStateBase) {
        this(outbox, clamp, enabled
                        ? Set.of(ArchiveDatasetId.EPOCH_STAKE, ArchiveDatasetId.ADA_POT)
                        : Set.of(),
                sourceCodecVersion, sourceStateBase);
    }

    public EpochArtifactCollector(ProjectionOutboxStore outbox, SnapshotRetentionClamp clamp,
                                  Set<ArchiveDatasetId> selected, int sourceCodecVersion,
                                  String sourceStateBase) {
        this(outbox, clamp, selected.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                dataset -> dataset, ignored -> 0)), sourceCodecVersion, sourceStateBase);
    }

    public EpochArtifactCollector(ProjectionOutboxStore outbox, SnapshotRetentionClamp clamp,
                                  Map<ArchiveDatasetId, Integer> projectedFrom,
                                  int sourceCodecVersion, String sourceStateBase) {
        this(outbox, clamp, projectedFrom, sourceCodecVersion, sourceStateBase, FailureListener.NOOP);
    }

    public EpochArtifactCollector(ProjectionOutboxStore outbox, SnapshotRetentionClamp clamp,
                                  Map<ArchiveDatasetId, Integer> projectedFrom,
                                  int sourceCodecVersion, String sourceStateBase,
                                  FailureListener failureListener) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clamp = clamp == null ? SnapshotRetentionClamp.NONE : clamp;
        Objects.requireNonNull(projectedFrom, "projectedFrom");
        if (!Set.of(ArchiveDatasetId.EPOCH_STAKE, ArchiveDatasetId.ADA_POT)
                .containsAll(projectedFrom.keySet())) {
            throw new IllegalArgumentException("direct collector only supports epoch-stake and ada-pot: "
                    + projectedFrom.keySet());
        }
        if (projectedFrom.values().stream().anyMatch(epoch -> epoch == null || epoch < 0)) {
            throw new IllegalArgumentException("projected-from epochs must be non-negative");
        }
        this.projectedFrom = java.util.Map.copyOf(projectedFrom);
        this.sourceCodecVersion = sourceCodecVersion;
        this.sourceStateBase = Objects.requireNonNull(sourceStateBase, "sourceStateBase");
        this.failureListener = failureListener == null ? FailureListener.NOOP : failureListener;
    }

    @Override
    public boolean enabled() {
        return !projectedFrom.isEmpty();
    }

    @Override
    public void contributeEpochStake(int epoch, long anchorSlot, long anchorBlockNumber,
                                     byte[] anchorBlockHash, long carrierBlockNumber,
                                     long rowCount, ProjectionStagingWriter writer) {
        Integer enrollmentFloor = projectedFrom.get(ArchiveDatasetId.EPOCH_STAKE);
        if (enrollmentFloor == null || epoch < enrollmentFloor) return;
        requireCoordinates(epoch, anchorSlot, anchorBlockNumber, anchorBlockHash, carrierBlockNumber,
                "epoch-stake");

        var ref = new ProjectionArtifactRef(
                ArchiveDatasetId.EPOCH_STAKE, epoch, anchorBlockNumber, anchorSlot, anchorBlockHash,
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                generationOf(epoch, anchorBlockHash), sourceCodecVersion, sourceStateBase + "/snapshot",
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

        outbox.putPendingEpochArtifact(writer, carrierBlockNumber, ref);

        // Lower the floor only. The floor means "oldest epoch still referenced", so writing this
        // epoch unconditionally would raise it past artifacts that are still pending and let
        // their snapshots be pruned out from under them. Raising it is the reader's job: it
        // recomputes the true minimum from open leases plus the outbox's pending set whenever an
        // artifact is acknowledged.
        int floor = clamp.protectedSnapshotFloorEpoch();
        if (floor < 0 || epoch < floor) clamp.protectSnapshotsFrom(epoch);
    }

    @Override
    public void contributeAdaPot(int epoch, long anchorSlot, long anchorBlockNumber,
                                 byte[] anchorBlockHash, long carrierBlockNumber,
                                 long[] values, ProjectionStagingWriter writer) {
        Integer enrollmentFloor = projectedFrom.get(ArchiveDatasetId.ADA_POT);
        if (enrollmentFloor == null || epoch < enrollmentFloor) return;
        requireCoordinates(epoch, anchorSlot, anchorBlockNumber, anchorBlockHash, carrierBlockNumber,
                "ada-pot");

        var ref = new ProjectionArtifactRef(
                ArchiveDatasetId.ADA_POT, epoch, anchorBlockNumber, anchorSlot, anchorBlockHash,
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE,
                "ada-pot:" + epoch + ':' + HexFormat.of().formatHex(anchorBlockHash),
                sourceCodecVersion, sourceStateBase + "/final",
                OptionalLong.of(1), "",
                // Nothing external is required: the evidence is inline, so no source can be
                // pruned out from under this artifact and nothing needs retaining for it.
                -1L,
                AdaPotArtifactRows.encode(values));

        outbox.putPendingEpochArtifact(writer, carrierBlockNumber, ref);
        // Deliberately no clamp. The pot is not a pruned generation, and the evidence does not
        // live in the store at all - it travels with the reference.
    }

    /** Generation identity of the delegation snapshot for an epoch. */
    public static String generationOf(int epoch, byte[] anchorBlockHash) {
        Objects.requireNonNull(anchorBlockHash, "anchorBlockHash");
        return "epoch-deleg-snapshot:" + epoch + ':'
                + HexFormat.of().formatHex(anchorBlockHash);
    }

    @Override
    public void captureFailed(Dataset dataset, int epoch, long carrierBlockNumber,
                              RuntimeException failure) {
        ArchiveDatasetId archiveDataset = switch (dataset) {
            case EPOCH_STAKE -> ArchiveDatasetId.EPOCH_STAKE;
            case ADA_POT -> ArchiveDatasetId.ADA_POT;
        };
        failureListener.failed(archiveDataset, epoch, carrierBlockNumber, failure);
    }

    private static void requireCoordinates(int epoch, long anchorSlot, long anchorBlockNumber,
                                           byte[] anchorBlockHash, long carrierBlockNumber,
                                           String dataset) {
        if (anchorBlockNumber < 0 || anchorSlot < 0 || anchorBlockHash == null
                || anchorBlockHash.length == 0 || carrierBlockNumber <= anchorBlockNumber) {
            throw new IllegalStateException(dataset + " artifact for epoch " + epoch
                    + " has no valid anchor/carrier coordinates");
        }
    }
}
