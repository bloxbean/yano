package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.projection.EpochStakeArtifactRows;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader pages a live generation rather than a copy, so its contract is about protection and
 * fidelity: hold the snapshot while reading, refuse when it has gone, and emit the same row the
 * replay worker would have written.
 */
class EpochSnapshotArtifactReaderTest {

    private static ProjectionArtifactRef ref(int epoch, long expectedRows) {
        return new ProjectionArtifactRef(ArchiveDatasetId.EPOCH_STAKE, epoch, 4_800_000L, 100_000L,
                new byte[] {7, 7, 7, 7},
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                "epoch-deleg-snapshot:" + epoch, 1, "state-v1",
                OptionalLong.of(expectedRows), "", 100_000L);
    }

    private static final class Clamp implements SnapshotRetentionClamp {
        int floor = -1;
        @Override public void protectSnapshotsFrom(int epoch) { floor = epoch; }
        @Override public int protectedSnapshotFloorEpoch() { return floor; }
    }

    @Test
    void theArtifactRowIsTheFinalArchiveRowAndSurvivesTheWireForm() {
        // The reader materialises the FINAL row, because the sink has no access to the boundary
        // hash, the slot clock or the network magic. Every column must survive the wire form.
        var artifact = ref(250, 2);
        byte[] boundaryHash = new byte[]{7, 7, 7, 7};

        // Pool hash may be absent (a registered but undelegated credential), and amounts exceed int.
        for (var source : List.of(
                new DefaultAccountStateStore.EpochSnapshotRow(0, new byte[]{1, 2, 3}, "aa".repeat(28),
                        new BigInteger("45000000000000")),
                new DefaultAccountStateStore.EpochSnapshotRow(1, new byte[]{9}, null, BigInteger.ZERO))) {
            var built = EpochStakeArtifactRows.row(artifact, 1, source.credentialType(),
                    source.credentialHash(), source.poolHash(), source.amount(), boundaryHash,
                    1_600_000_000L, EpochStakeArtifactRows.jobId(artifact));

            var decoded = ArchiveRowCodec.decode(ArchiveRowCodec.encode(built));

            assertThat(decoded.table()).isEqualTo("epoch_stakes");
            assertThat(decoded.values()).containsExactlyElementsOf(built.values());
            assertThat(decoded.values()).hasSize(EpochStakeArtifactRows.columns().size());
        }
    }

    @Test
    void theRowShapeMatchesTheShippedSchema() {
        // Both the projection and the replay worker write this table. If the column list drifts
        // from the schema, one path produces rows the other cannot reproduce.
        var columns = ArchiveSchemas.schema(ArchiveDatasetId.EPOCH_STAKE).tables().stream()
                .filter(table -> table.physicalName().equals("epoch_stakes"))
                .findFirst().orElseThrow()
                .columns().stream().map(column -> column.name()).toList();

        assertThat(EpochStakeArtifactRows.columns()).isEqualTo(columns);
    }

    @Test
    void aReaderOnlyServesEpochStake() {
        var reader = new EpochSnapshotArtifactReader(null, new Clamp(), 100, 1, null);
        // A valid ada-pot reference: ATOMIC_EVIDENCE carries its evidence and requires no source.
        var wrongDataset = new ProjectionArtifactRef(ArchiveDatasetId.ADA_POT, 1, 1, 1, new byte[] {1},
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE, "g", 1, "s",
                OptionalLong.of(1), "", -1L, new byte[8 * Long.BYTES]);

        assertThatThrownBy(() -> reader.acknowledge(wrongDataset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serves EPOCH_STAKE");
    }

    @Test
    void aMissingBoundaryReferenceFailsRatherThanWritingANullHash() {
        // A null boundary_block_hash would differ from the replay path while looking complete.
        var reader = new EpochSnapshotArtifactReader(null, new Clamp(), 100, 1,
                new ArtifactBoundaryFacts() {
                    @Override public Optional<byte[]> blockHash(long blockNumber) { return Optional.empty(); }
                    @Override public long blockTimeSeconds(long slot) { return 0; }
                });

        assertThatThrownBy(() -> reader.acquire(ref(250, 1), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no canonical block reference");
    }

    @Test
    void aDifferentCanonicalHashRefusesTheSnapshotFromTheRolledBackAnchor() {
        var reader = new EpochSnapshotArtifactReader(null, new Clamp(), 100, 1,
                new ArtifactBoundaryFacts() {
                    @Override public Optional<byte[]> blockHash(long blockNumber) {
                        return Optional.of(new byte[]{9, 9, 9, 9});
                    }
                    @Override public long blockTimeSeconds(long slot) { return 0; }
                });

        assertThatThrownBy(() -> reader.acquire(ref(250, 1), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anchor is no longer canonical");
    }

    @Test
    void restartReconciliationProtectsEveryStillPendingArtifact() {
        // Leases are in-memory. After a restart the only durable record is what the outbox still
        // holds, so protection has to be re-derived from it before anything can prune.
        var clamp = new Clamp();
        var reader = new EpochSnapshotArtifactReader(null, clamp, 100, 1, null);

        reader.reconcileAfterRestart(List.of(ref(251, 5), ref(250, 5)));
        assertThat(clamp.floor).as("the oldest pending epoch sets the floor").isEqualTo(250);

        // Acknowledging one must not release the other; 251 is still unacknowledged.
        reader.acknowledge(ref(250, 5));
        assertThat(clamp.floor).isEqualTo(251);

        reader.acknowledge(ref(251, 5));
        assertThat(clamp.floor).as("nothing pending, so pruning resumes").isEqualTo(-1);
    }

    @Test
    void reconcilingAnEmptySetReleasesProtectionRatherThanPinningIt() {
        var clamp = new Clamp();
        clamp.protectSnapshotsFrom(250);
        var reader = new EpochSnapshotArtifactReader(null, clamp, 100, 1, null);

        // An empty set is meaningful: every artifact was acknowledged before the restart.
        reader.reconcileAfterRestart(List.of());
        assertThat(clamp.floor).isEqualTo(-1);
    }

    @Test
    void acknowledgingReleasesTheClampAndIsIdempotent() {
        var clamp = new Clamp();
        clamp.protectSnapshotsFrom(250);
        var reader = new EpochSnapshotArtifactReader(null, clamp, 100, 1, null);

        reader.acknowledge(ref(250, 10));
        assertThat(clamp.floor).as("nothing referenced, so pruning resumes").isEqualTo(-1);

        // The consumer releases before the outbox drops the reference, so a crash replays this.
        reader.acknowledge(ref(250, 10));
        assertThat(clamp.floor).isEqualTo(-1);
    }
}
