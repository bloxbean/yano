package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.EpochArtifactContributor;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Epoch stake is projected by reference, not by copy, so the properties that matter are that the
 * reference commits with the snapshot and that the snapshot cannot be pruned while referenced.
 */
class EpochArtifactCollectorTest {
    static { RocksDB.loadLibrary(); }
    private static final byte[] ANCHOR_HASH = {1, 2, 3, 4};

    private static final Set<ProjectionSectionType> REQUIRED = Set.of(ProjectionSectionType.TRANSACTION);
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(
            new ArchiveNetworkIdentity(1, "fixture"), "test", 1, REQUIRED);

    @TempDir Path directory;
    private RocksDB db;
    private DBOptions dbOptions;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore store;

    /** Records what was clamped, so protection can be asserted rather than assumed. */
    private static final class RecordingClamp implements SnapshotRetentionClamp {
        private int floor = -1;
        @Override public void protectSnapshotsFrom(int epoch) { floor = epoch; }
        @Override public int protectedSnapshotFloorEpoch() { return floor; }
    }

    private RecordingClamp clamp;

    @BeforeEach
    void open() throws Exception {
        dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (String name : ProjectionCfNames.ALL) {
            descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)));
        }
        handles = new ArrayList<>();
        db = RocksDB.open(dbOptions, directory.resolve("db").toString(), descriptors, handles);
        store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3), handles.get(4));
        clamp = new RecordingClamp();
    }

    @AfterEach
    void close() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    private EpochArtifactCollector collector() {
        return new EpochArtifactCollector(store, clamp, true, 1, "ledger-boundary-v1");
    }

    /** Persist an intent in the transition batch, then attach it in the carrier batch. */
    private void contribute(EpochArtifactCollector collector, int epoch, long slot, long block,
                            long rows) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector.contributeEpochStake(epoch, slot - 1, block - 1, ANCHOR_HASH, block, rows,
                    ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        bind(block);
    }

    private void bind(long carrierBlock) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.bindPendingEpochArtifacts(
                    ProjectionOutboxStore.batchWriter(batch, store.handles()), carrierBlock);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theReferenceIsStagedAndDescribesTheSnapshotItPointsAt() {
        contribute(collector(), 250, 100_000L, 4_800_000L, 1_234);

        var envelope = store.readArtifacts(4_800_000L);
        assertThat(envelope).hasSize(1);
        var ref = envelope.get(0);
        assertThat(ref.dataset()).isEqualTo(ArchiveDatasetId.EPOCH_STAKE);
        assertThat(ref.semanticEpoch()).isEqualTo(250);
        assertThat(ref.producingBlockNumber()).isEqualTo(4_799_999L);
        assertThat(ref.representation())
                .as("a reference, not a copy - nothing about the transition scales with stake size")
                .isEqualTo(ProjectionArtifactRepresentation.IMMUTABLE_GENERATION);
        assertThat(ref.sourceGeneration()).isEqualTo("epoch-deleg-snapshot:250:01020304");
        assertThat(ref.expectedRowCount()).hasValue(1_234);
        assertThat(ref.oldestRequiredSlot())
                .as("the snapshot is held by the clamp, not by chain replay retention; declaring a"
                        + " slot here pauses the drain as soon as the rollback floor passes it")
                .isEqualTo(-1L);
    }

    @Test
    void identityChangesWithAnchorHashButNotWithCarrierIdentity() {
        contribute(collector(), 250, 100_000L, 4_800_000L, 1);
        var first = store.readArtifacts(4_800_000L).getFirst();

        byte[] otherAnchor = {9, 8, 7, 6};
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector().contributeEpochStake(250, 99_999L, 4_799_999L, otherAnchor,
                    4_800_001L, 1, ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        bind(4_800_001L);
        var second = store.readArtifacts(4_800_001L).getFirst();

        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector().contributeEpochStake(250, 99_999L, 4_799_999L, ANCHOR_HASH,
                    4_800_002L, 1, ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        bind(4_800_002L);
        var sameAnchorNewCarrier = store.readArtifacts(4_800_002L).getFirst();

        assertThat(first.producingBlockNumber()).isEqualTo(second.producingBlockNumber());
        assertThat(first.sourceGeneration()).isNotEqualTo(second.sourceGeneration());
        assertThat(first.canonicalForm()).isNotEqualTo(second.canonicalForm());
        assertThat(first.canonicalForm()).isEqualTo(sameAnchorNewCarrier.canonicalForm());
    }

    @Test
    void stagingTheReferenceClampsSnapshotPruning() {
        // Without this the normal retention window would delete a generation the archive still
        // points at, and the reference would resolve to nothing.
        assertThat(clamp.protectedSnapshotFloorEpoch()).isEqualTo(-1);

        contribute(collector(), 250, 100_000L, 4_800_000L, 10);

        assertThat(clamp.protectedSnapshotFloorEpoch()).isEqualTo(250);
    }

    @Test
    void transitionIntentIsNotAnArtifactUntilTheCarrierBatchBindsIt() {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector().contributeEpochStake(250, 99_999L, 4_799_999L, ANCHOR_HASH,
                    4_800_000L, 10, ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(store.readArtifacts(4_800_000L)).isEmpty();
        assertThat(store.pendingArtifacts()).hasSize(1);

        bind(4_800_000L);

        assertThat(store.readArtifacts(4_800_000L)).hasSize(1);
        assertThat(store.pendingArtifacts()).hasSize(1);
    }

    @Test
    void aMissingBoundaryCoordinateFailsClosed() {
        // An artifact with no producing coordinate can never become finality-eligible, so it
        // would pin its snapshot forever while looking like normal pending work.
        assertThatThrownBy(() -> contribute(collector(), 250, 0, 0, 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anchor/carrier");
    }

    @Test
    void aDisabledCollectorStagesNothingAndClampsNothing() {
        var disabled = new EpochArtifactCollector(store, clamp, false, 1, "ledger-boundary-v1");
        contribute(disabled, 250, 100_000L, 4_800_000L, 10);

        assertThat(store.readArtifacts(4_800_000L)).isEmpty();
        assertThat(clamp.protectedSnapshotFloorEpoch()).isEqualTo(-1);
    }

    @Test
    void selectionAndProjectedFromGateEachDirectDataset() {
        var selected = new EpochArtifactCollector(store, clamp,
                Map.of(ArchiveDatasetId.EPOCH_STAKE, 251),
                1, "ledger-boundary-v1");

        contribute(selected, 250, 100_000L, 4_800_000L, 10);
        contribute(selected, 251, 100_100L, 4_800_001L, 11);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            selected.contributeAdaPot(251, 100_099L, 4_800_000L, ANCHOR_HASH, 4_800_001L,
                    new long[]{1,2,3,4,5,6,7,8},
                    ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(store.readArtifacts(4_800_000L)).isEmpty();
        assertThat(store.readArtifacts(4_800_001L))
                .extracting(ref -> ref.dataset())
                .containsExactly(ArchiveDatasetId.EPOCH_STAKE);
        assertThat(clamp.protectedSnapshotFloorEpoch()).isEqualTo(251);
    }

    @Test
    void forwardsAnIsolatedLedgerCaptureFailureToObservability() {
        var failures = new ArrayList<String>();
        var collector = new EpochArtifactCollector(store, clamp,
                Map.of(ArchiveDatasetId.ADA_POT, 0), 1, "ledger-boundary-v1",
                (dataset, epoch, carrier, failure) -> failures.add(
                        dataset.logicalName() + ':' + epoch + ':' + carrier + ':' + failure.getMessage()));

        collector.captureFailed(EpochArtifactContributor.Dataset.ADA_POT,
                250, 4_800_000L, new IllegalStateException("anchor mismatch"));

        assertThat(failures).containsExactly("ada_pot:250:4800000:anchor mismatch");
    }

    @Test
    void anAdaPotArtifactCarriesItsEvidenceAndRequiresNoRetention() {
        // The pot is not written through the boundary batch and is re-stored as rewards and
        // governance adjust it, so there is no generation to reference and nothing to protect.
        long[] values = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L};
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector().contributeAdaPot(250, 99_999L, 4_799_999L, ANCHOR_HASH, 4_800_000L, values,
                    ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        bind(4_800_000L);

        var ref = store.readArtifacts(4_800_000L).get(0);
        assertThat(ref.dataset()).isEqualTo(ArchiveDatasetId.ADA_POT);
        assertThat(ref.representation()).isEqualTo(ProjectionArtifactRepresentation.ATOMIC_EVIDENCE);
        assertThat(ref.expectedRowCount()).hasValue(1);
        assertThat(AdaPotArtifactRows.decode(ref.inlinePayload()))
                .as("the evidence survives the outbox round trip")
                .containsExactly(values);
        assertThat(ref.oldestRequiredSlot()).as("nothing to retain").isEqualTo(-1L);
        assertThat(clamp.protectedSnapshotFloorEpoch())
                .as("the pot is not a pruned generation").isEqualTo(-1);
    }

    @Test
    void stagingALaterEpochDoesNotReleaseAnEarlierOneStillPending() {
        // The floor means "oldest epoch still referenced". Writing each new epoch over it would
        // let the snapshot for an older, still-unacknowledged artifact be pruned.
        contribute(collector(), 250, 100_000L, 4_800_000L, 10);
        assertThat(clamp.protectedSnapshotFloorEpoch()).isEqualTo(250);

        contribute(collector(), 251, 200_000L, 4_900_000L, 10);

        assertThat(clamp.protectedSnapshotFloorEpoch())
                .as("epoch 250 is still pending, so protection must not move up to 251")
                .isEqualTo(250);
    }

    @Test
    void eachDatasetCarriesTheStateVersionItsReplayCounterpartWrites() {
        // Both pipelines write source_state_version. A shared value would make identical data
        // look like it came from two different producers.
        contribute(collector(), 250, 100_000L, 4_800_000L, 10);
        assertThat(store.readArtifacts(4_800_000L).get(0).sourceStateVersion())
                .isEqualTo("ledger-boundary-v1/snapshot");

        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector().contributeAdaPot(250, 99_999L, 4_899_999L, ANCHOR_HASH, 4_900_000L,
                    new long[]{1,2,3,4,5,6,7,8},
                    ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        bind(4_900_000L);
        assertThat(store.readArtifacts(4_900_000L).get(0).sourceStateVersion())
                .isEqualTo("ledger-boundary-v1/final");
    }

    @Test
    void severalArtifactsForOneEpochAtOneBoundaryAllSurvive() {
        // Rewards alone stage separate parts for the calculator, MIR certificates and governance
        // withdrawals - same dataset, same epoch, same boundary block. Keying without the source
        // generation kept only the last, which looks like a complete epoch and is not.
        var refs = java.util.List.of(
                stagedRef("reward-calc", 4_887),
                stagedRef("reward-mir", 1),
                stagedRef("reward-governance", 12));
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            var writer = ProjectionOutboxStore.batchWriter(batch, store.handles());
            refs.forEach(ref -> store.putArtifact(writer, 4_800_000L, ref));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        var stored = store.readArtifacts(4_800_000L);

        assertThat(stored).hasSize(3);
        assertThat(stored).extracting(r -> r.expectedRowCount().orElseThrow())
                .containsExactlyInAnyOrder(4_887L, 1L, 12L);
        assertThat(stored).extracting(
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef::sourceGeneration)
                .containsExactlyInAnyOrder("reward-calc", "reward-mir", "reward-governance");
    }

    private static com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef stagedRef(
            String generation, long rows) {
        return new com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef(
                ArchiveDatasetId.REWARD, 83, 4_800_000L, 100_000L, ANCHOR_HASH,
                ProjectionArtifactRepresentation.STAGED_FILE, generation, 1,
                "ledger-boundary-v1/reward", java.util.OptionalLong.of(rows), "ab".repeat(32), -1L);
    }
}
