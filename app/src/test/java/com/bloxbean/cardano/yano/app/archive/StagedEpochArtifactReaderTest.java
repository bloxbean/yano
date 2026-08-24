package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read half of the staged-evidence flow.
 *
 * <p>A staging class that writes files nobody references is not a migration. What matters here is
 * the ordering of the last three steps: the sink commits, the receipt makes that durable, and
 * only then does acknowledgement release the evidence. Releasing earlier would destroy something
 * that cannot be recomputed.
 */
class StagedEpochArtifactReaderTest {

    private static final UUID JOB = UUID.nameUUIDFromBytes("reward-42".getBytes());

    private static ProjectionArtifactRef ref(ArchiveDatasetId dataset, long rows) {
        return new ProjectionArtifactRef(dataset, 42, 4_800_000L, 100_000L,
                ProjectionArtifactRepresentation.STAGED_FILE, JOB.toString(), 1,
                "ledger-boundary-v1/reward", OptionalLong.of(rows), "ab".repeat(32), -1L);
    }

    /** Stands in for the staging service, recording what was released and when. */
    private static final class FakeEvidence implements StagedEpochArtifactReader.StagedEvidenceSource {
        List<ArchiveRow> rows = new ArrayList<>();
        boolean present = true;
        int released;
        int pagesServed;

        /** Serves real pages, so the reader's cursor handling is exercised rather than assumed. */
        @Override public StagedEpochArtifactReader.EvidencePage rows(
                ProjectionArtifactRef ref, Optional<String> cursor, int limit) {
            if (!present) throw new IllegalStateException("evidence gone");
            int from = cursor.map(Integer::parseInt).orElse(0);
            int to = Math.min(rows.size(), from + limit);
            pagesServed++;
            return new StagedEpochArtifactReader.EvidencePage(
                    List.copyOf(rows.subList(from, to)),
                    to >= rows.size() ? Optional.empty() : Optional.of(Integer.toString(to)));
        }
        @Override public void release(ProjectionArtifactRef ref) { released++; present = false; }
        @Override public boolean present(ProjectionArtifactRef ref) { return present; }
    }

    private static ArchiveRow rewardRow(int i) {
        return new ArchiveRow("rewards", java.util.Arrays.asList(42L, "member", (long) i));
    }

    private static StagedEpochArtifactReader reader(FakeEvidence evidence) {
        return new StagedEpochArtifactReader(Map.of(ArchiveDatasetId.REWARD, evidence));
    }

    @Test
    void evidenceIsServedAsMaterialisedRows() {
        var evidence = new FakeEvidence();
        evidence.rows = List.of(rewardRow(1), rewardRow(2));
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 2);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            var page = reader.read(artifact, lease, Optional.empty(), 100);

            assertThat(page.rows()).hasSize(2);
            assertThat(ArchiveRowCodec.decode(page.rows().getFirst()).table()).isEqualTo("rewards");
        }
    }

    @Test
    void acknowledgementIsWhatReleasesTheEvidence() {
        // Ordering is the point: nothing may delete irreproducible evidence before the sink's
        // receipt proves its rows are durable elsewhere.
        var evidence = new FakeEvidence();
        evidence.rows = List.of(rewardRow(1));
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 1);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            reader.read(artifact, lease, Optional.empty(), 100);
            assertThat(evidence.released).as("reading must not release").isZero();
        }
        assertThat(evidence.released).as("closing a lease must not release either").isZero();

        reader.acknowledge(artifact);

        assertThat(evidence.released).isEqualTo(1);
    }

    @Test
    void acknowledgementIsIdempotentAcrossAReplayedCrash() {
        // The consumer releases artifacts before the outbox drops their reference, so a crash in
        // between replays this call.
        var evidence = new FakeEvidence();
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 0);

        reader.acknowledge(artifact);
        reader.acknowledge(artifact);

        assertThat(evidence.released).as("release is idempotent, not doubled").isEqualTo(2);
        assertThat(evidence.present).isFalse();
    }

    @Test
    void aLeaseIsHeldWhileReadingAndReleasedOnClose() {
        var evidence = new FakeEvidence();
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 0);

        var lease = reader.acquire(artifact, Instant.now().plusSeconds(60));
        assertThat(reader.isLeased(artifact)).isTrue();
        lease.close();
        assertThat(reader.isLeased(artifact)).isFalse();
    }

    @Test
    void missingEvidenceFailsClosedBeforeALeaseIsGranted() {
        // Committing an epoch with no rows because its evidence vanished would be silent loss.
        var evidence = new FakeEvidence();
        evidence.present = false;
        var reader = reader(evidence);

        assertThatThrownBy(() -> reader.acquire(ref(ArchiveDatasetId.REWARD, 3), Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reproduced once the boundary has passed");
    }

    @Test
    void aShortReadIsRefusedRatherThanCommittedAsACompleteEpoch() {
        var evidence = new FakeEvidence();
        evidence.rows = List.of(rewardRow(1));
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 5);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            assertThatThrownBy(() -> reader.read(artifact, lease, Optional.empty(), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("yielded 1 rows but the reference declares 5");
        }
    }

    @Test
    void anUnroutedDatasetFailsLoudly() {
        var reader = reader(new FakeEvidence());

        assertThatThrownBy(() -> reader.acquire(
                ref(ArchiveDatasetId.DREP_DISTRIBUTION, 1), Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no staged evidence source is installed");
    }

    @Test
    void restartReconciliationReleasesNothing() {
        // Staged files are their own durable record. An artifact still referenced must keep its
        // evidence; one no longer referenced was already released on acknowledgement. Releasing
        // here would delete evidence the drain is about to need.
        var evidence = new FakeEvidence();
        evidence.rows = List.of(rewardRow(1));
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 1);

        reader.reconcileAfterRestart(List.of(artifact));

        assertThat(evidence.released).isZero();
        assertThat(evidence.present).isTrue();
        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            assertThat(reader.read(artifact, lease, Optional.empty(), 10).rows()).hasSize(1);
        }
    }

    @Test
    void aLargeArtifactIsServedInPagesRatherThanWholesale() {
        // The regression this guards: read() used to ignore limit and materialise the entire
        // artifact, so peak heap tracked the largest reward epoch on the chain.
        var evidence = new FakeEvidence();
        var rows = new ArrayList<ArchiveRow>();
        for (int i = 0; i < 250; i++) rows.add(rewardRow(i));
        evidence.rows = rows;
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 250);

        int seen = 0;
        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            Optional<String> cursor = Optional.empty();
            do {
                var page = reader.read(artifact, lease, cursor, 100);
                assertThat(page.rows().size()).isLessThanOrEqualTo(100);
                seen += page.rows().size();
                cursor = page.nextCursor();
            } while (cursor.isPresent());
        }

        assertThat(seen).isEqualTo(250);
        assertThat(evidence.pagesServed).as("three pages of 100, 100 and 50").isEqualTo(3);
    }

    @Test
    void aTruncatedArtifactIsStillRefusedWhenPaged() {
        // The count is now checked on the last page instead of the only one; a short artifact
        // must still be refused rather than committed as a complete epoch.
        var evidence = new FakeEvidence();
        evidence.rows = List.of(rewardRow(1), rewardRow(2));
        var reader = reader(evidence);
        var artifact = ref(ArchiveDatasetId.REWARD, 9);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            assertThatThrownBy(() -> reader.read(artifact, lease, Optional.empty(), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("yielded 2 rows but the reference declares 9");
        }
    }
}
