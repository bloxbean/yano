package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DurableEpochFileSourceTest {
    @TempDir Path temp;

    @Test
    void streamsBoundedPagesAndDiscoversJobAfterRestart() {
        EpochFactCodec<String> codec = new EpochFactCodec<>() {
            public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
        };
        var source = new DurableEpochFileSource<>(ArchiveDatasetId.EPOCH_STAKE, temp, codec);
        var job = new EpochArchiveJob(UUID.randomUUID(), new ArchiveNetworkIdentity(1, "genesis"),
                ArchiveDatasetId.EPOCH_STAKE, 1, 12, 100, 500, 1_700_000_000L, new byte[] {1},
                "state-v1", "source/12", Instant.EPOCH);
        source.stage(job, List.of("a", "b", "c"));

        var restarted = new DurableEpochFileSource<>(ArchiveDatasetId.EPOCH_STAKE, temp, codec);
        assertThat(restarted.pendingAfter(11, 1)).containsExactly(job);
        try (var lease = restarted.acquire(job, Instant.now().plusSeconds(30))) {
            var first = restarted.read(job, Optional.empty(), 2, lease);
            assertThat(first.rows()).containsExactly("a", "b");
            var second = restarted.read(job, first.nextCursor(), 2, lease);
            assertThat(second.rows()).containsExactly("c");
            assertThat(second.nextCursor()).isEmpty();
        }
    }

    @Test
    void rollbackDiscardRemovesOnlyLaterEpochsAcrossRestart() {
        EpochFactCodec<String> codec = new EpochFactCodec<>() {
            public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
        };
        var source = new DurableEpochFileSource<>(ArchiveDatasetId.EPOCH_STAKE, temp, codec);
        var network = new ArchiveNetworkIdentity(1, "genesis");
        var retained = new EpochArchiveJob(UUID.randomUUID(), network, ArchiveDatasetId.EPOCH_STAKE,
                1, 12, 100, 500, 1_700_000_000L, new byte[] {1}, "state-v1", "source/12", Instant.EPOCH);
        var orphaned = new EpochArchiveJob(UUID.randomUUID(), network, ArchiveDatasetId.EPOCH_STAKE,
                1, 13, 110, 600, 1_700_001_000L, new byte[] {2}, "state-v1", "source/13", Instant.EPOCH);
        source.stage(retained, List.of("canonical"));
        source.stage(orphaned, List.of("orphaned"));

        assertThat(source.discardAfterEpoch(12)).isEqualTo(1);
        var restarted = new DurableEpochFileSource<>(ArchiveDatasetId.EPOCH_STAKE, temp, codec);
        assertThat(restarted.pending(10)).containsExactly(retained);
        assertThat(restarted.find(orphaned.jobId())).isEmpty();
    }

    @Test
    void provisionalCaptureBindsWithoutCopyAndRecoversAnInterruptedManifestPublish() throws Exception {
        EpochFactCodec<String> codec = new EpochFactCodec<>() {
            public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
        };
        var network = new ArchiveNetworkIdentity(1, "genesis");
        var capture = new ProvisionalEpochArchiveJob(UUID.randomUUID(), network,
                ArchiveDatasetId.REWARD, 1, 20, 200, 2_000,
                "ledger-boundary-v1/rewards", "REWARD/rewards/20", Instant.EPOCH);
        var job = new EpochArchiveJob(UUID.randomUUID(), network, ArchiveDatasetId.REWARD,
                1, 20, 200, 2_000, 1_700_002_000L, new byte[] {9, 8, 7},
                capture.sourceStateVersion(), capture.sourceReference(), Instant.EPOCH);
        Path finalDirectory = temp.resolve("final");
        var source = new DurableEpochFileSource<>(ArchiveDatasetId.REWARD, finalDirectory, codec);
        var provisional = new DurableProvisionalEpochFileSource<>(
                ArchiveDatasetId.REWARD, temp.resolve("provisional"), codec);
        try (var writer = provisional.open(capture)) {
            writer.append("first");
            writer.append("second");
            writer.commit();
        }

        var restarted = new DurableEpochFileSource<>(ArchiveDatasetId.REWARD, finalDirectory, codec);
        var restartedProvisional = new DurableProvisionalEpochFileSource<>(
                ArchiveDatasetId.REWARD, temp.resolve("provisional"), codec);
        assertThat(restartedProvisional.pending()).containsExactly(capture);
        var evidence = restartedProvisional.bind(capture, restarted, job);
        assertThat(evidence.rowCount()).isEqualTo(2);
        assertThat(temp.resolve("provisional/" + capture.captureId() + ".rows")).exists();
        assertThat(finalDirectory.resolve(job.jobId() + ".rows")).exists();

        // Model a crash after rows were rebound but before the final manifest survived.
        Files.delete(finalDirectory.resolve(job.jobId() + ".properties"));
        var recovered = new DurableEpochFileSource<>(ArchiveDatasetId.REWARD, finalDirectory, codec);
        var recoveredProvisional = new DurableProvisionalEpochFileSource<>(
                ArchiveDatasetId.REWARD, temp.resolve("provisional"), codec);
        assertThat(recoveredProvisional.bind(capture, recovered, job)).isEqualTo(evidence);
        try (var lease = recovered.acquire(job, Instant.now().plusSeconds(30))) {
            assertThat(recovered.read(job, Optional.empty(), 10, lease).rows())
                    .containsExactly("first", "second");
        }

        recovered.acknowledge(job);
        recoveredProvisional.acknowledgeBound(job.jobId());
        assertThat(recoveredProvisional.pending()).isEmpty();
        assertThat(recovered.pending(10)).isEmpty();
    }
}
