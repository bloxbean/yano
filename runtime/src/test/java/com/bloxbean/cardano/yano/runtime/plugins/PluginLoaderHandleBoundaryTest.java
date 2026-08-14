package com.bloxbean.cardano.yano.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginLoaderHandleBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void initialInspectionRejectsSparseJarAbovePerArtifactLimit() throws Exception {
        Path pluginDirectory = Files.createDirectory(temporary.resolve("oversized-plugins"));
        Path oversized = pluginDirectory.resolve("oversized.jar");
        try (FileChannel channel = FileChannel.open(oversized,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            channel.position(PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThat(Files.size(oversized))
                .isEqualTo(PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES + 1);

        assertThatThrownBy(() -> PluginLoaderHandle.directory(
                pluginDirectory, getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oversized.jar")
                .hasMessageContaining("exceeds " + PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES)
                .hasMessageNotContaining("while captured");
    }

    @Test
    void streamingCaptureRejectsByteGrowthPastSameProductionLimit() throws Exception {
        Path source = Files.write(temporary.resolve("growing.jar"), new byte[]{1});
        assertThat(Files.size(source)).isOne();
        assertThat(PluginLoaderHandle.advanceCapturedPluginJarBytes(
                source, PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES - 1, 1))
                .isEqualTo(PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES);

        assertThatThrownBy(() -> PluginLoaderHandle.advanceCapturedPluginJarBytes(
                source, PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("growing.jar")
                .hasMessageContaining("exceeds " + PluginLoaderHandle.MAX_PLUGIN_JAR_BYTES)
                .hasMessageContaining("while captured");
    }

    @Test
    void orphanCleanupRefusesDirectoryIdentityChangeAfterAcquiringLease() throws Exception {
        OrphanCandidate orphan = orphanCandidate("directory-identity-race");
        Instant now = Instant.now();
        FileTime initialDirectoryTime = FileTime.from(now
                .minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE).minusSeconds(120));
        FileTime changedDirectoryTime = FileTime.from(now
                .minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE).minusSeconds(60));
        Files.setLastModifiedTime(orphan.directory(), initialDirectoryTime);
        AtomicBoolean checkpointReached = new AtomicBoolean();

        boolean removed = PluginLoaderHandle.cleanupOrphanedSnapshotDirectory(
                orphan.directory(), (lockedDirectory, ignoredLease) -> {
                    checkpointReached.set(true);
                    Files.setLastModifiedTime(lockedDirectory, changedDirectoryTime);
                });

        assertThat(checkpointReached).isTrue();
        assertThat(removed).isFalse();
        assertThat(orphan.directory()).exists();
        assertThat(orphan.lease()).exists();
        assertThat(orphan.payload()).exists();
        assertThat(Files.getLastModifiedTime(orphan.directory()))
                .isNotEqualTo(initialDirectoryTime);
    }

    @Test
    void orphanCleanupRefusesLeaseIdentityChangeAfterAcquiringLease() throws Exception {
        OrphanCandidate orphan = orphanCandidate("lease-identity-race");
        Instant now = Instant.now();
        FileTime staleDirectoryTime = FileTime.from(now
                .minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE).minusSeconds(120));
        FileTime initialLeaseTime = Files.getLastModifiedTime(orphan.lease());
        FileTime changedLeaseTime = FileTime.from(now.plusSeconds(3600));
        Files.setLastModifiedTime(orphan.directory(), staleDirectoryTime);
        AtomicBoolean checkpointReached = new AtomicBoolean();

        boolean removed = PluginLoaderHandle.cleanupOrphanedSnapshotDirectory(
                orphan.directory(), (ignoredDirectory, lockedLease) -> {
                    checkpointReached.set(true);
                    Files.setLastModifiedTime(lockedLease, changedLeaseTime);
                });

        assertThat(checkpointReached).isTrue();
        assertThat(removed).isFalse();
        assertThat(orphan.directory()).exists();
        assertThat(orphan.lease()).exists();
        assertThat(orphan.payload()).exists();
        assertThat(Files.getLastModifiedTime(orphan.lease())).isNotEqualTo(initialLeaseTime);
    }

    @Test
    void orphanCleanupStopsAtConfiguredCandidateBound() throws Exception {
        for (int index = 0; index < 3; index++) {
            OrphanCandidate orphan = orphanCandidate("bounded-" + index);
            Files.setLastModifiedTime(orphan.directory(), FileTime.from(
                    Instant.now().minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE)
                            .minusSeconds(60)));
        }

        int removed = PluginLoaderHandle.cleanupOrphanedSnapshotDirectories(
                temporary, 2);

        assertThat(removed).isEqualTo(2);
        try (var remaining = Files.list(temporary)) {
            assertThat(remaining.filter(Files::isDirectory).count()).isOne();
        }
        PluginLoaderHandle.cleanupOrphanedSnapshotDirectories(temporary, 2);
    }

    @Test
    void malformedOrphanIsSkippedWithoutDeletingItsValidPayloadPrefix() throws Exception {
        OrphanCandidate orphan = orphanCandidate("malformed-shape");
        Path nested = Files.createDirectory(orphan.directory().resolve("unexpected"));
        Files.writeString(nested.resolve("payload"), "do-not-follow");
        Files.setLastModifiedTime(orphan.directory(), FileTime.from(
                Instant.now().minus(PluginLoaderHandle.SNAPSHOT_ORPHAN_GRACE)
                        .minusSeconds(60)));

        boolean removed = PluginLoaderHandle.cleanupOrphanedSnapshotDirectory(
                orphan.directory(), (ignoredDirectory, ignoredLease) -> { });

        assertThat(removed).isFalse();
        assertThat(orphan.payload()).exists();
        assertThat(orphan.lease()).exists();
        assertThat(nested.resolve("payload")).exists();
    }

    private OrphanCandidate orphanCandidate(String name) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(
                PluginLoaderHandle.SNAPSHOT_DIRECTORY_PREFIX + name));
        Path lease = directory.resolve(PluginLoaderHandle.SNAPSHOT_LEASE_FILE);
        Files.writeString(lease, PluginLoaderHandle.SNAPSHOT_LEASE_MARKER,
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Path payload = Files.writeString(directory.resolve("00000.jar"), "payload",
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        return new OrphanCandidate(directory, lease, payload);
    }

    private record OrphanCandidate(Path directory, Path lease, Path payload) {
    }
}
