package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Staged epoch evidence is irreproducible: rewards, DRep state and governance decisions cannot be
 * recomputed once the boundary has passed. These pin the two properties that follow from that -
 * the bytes must survive power loss, and damage must fail closed rather than present as a shorter
 * epoch.
 */
class StagedEvidenceIntegrityTest {

    @TempDir Path temp;

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture-genesis");

    /** Length-prefixed strings, matching the source's own framing. */
    private static final EpochFactCodec<String> CODEC = new EpochFactCodec<>() {
        @Override public byte[] encode(String fact) { return fact.getBytes(StandardCharsets.UTF_8); }
        @Override public String decode(byte[] encoded) { return new String(encoded, StandardCharsets.UTF_8); }
    };

    private DurableEpochFileSource<String> source() {
        return new DurableEpochFileSource<>(ArchiveDatasetId.REWARD, temp.resolve("reward"), CODEC);
    }

    private static EpochArchiveJob job(int epoch) {
        return new EpochArchiveJob(UUID.nameUUIDFromBytes(("reward-" + epoch).getBytes(StandardCharsets.UTF_8)),
                NETWORK, ArchiveDatasetId.REWARD, 4, epoch, 100, 200, 1_600_000_000L,
                new byte[]{1, 2, 3}, "ledger-boundary-v1/reward", "reward/" + epoch, Instant.now());
    }

    private static ArchiveSourceLease lease() {
        return new ArchiveSourceLease() {
            private final UUID id = UUID.randomUUID();
            @Override public UUID leaseId() { return id; }
            @Override public Instant expiresAt() { return Instant.now().plusSeconds(600); }
            @Override public ArchiveSourceLease renew(Instant newExpiry) { return this; }
            @Override public void close() { }
        };
    }

    private void stage(DurableEpochFileSource<String> source, EpochArchiveJob job, List<String> facts) {
        try (var writer = source.open(job)) {
            facts.forEach(writer::append);
            writer.commit();
        }
    }

    private Path rowsFile(EpochArchiveJob job) {
        return temp.resolve("reward").resolve(job.jobId() + ".rows");
    }

    private Path manifestFile(EpochArchiveJob job) {
        return temp.resolve("reward").resolve(job.jobId() + ".properties");
    }

    @Test
    void committedEvidenceRecordsItsSizeAndChecksum() throws IOException {
        var source = source();
        var job = job(1);
        stage(source, job, List.of("a", "b", "c"));

        Properties manifest = new Properties();
        try (var in = Files.newInputStream(manifestFile(job))) { manifest.load(in); }

        assertThat(manifest.getProperty("rowCount")).isEqualTo("3");
        assertThat(manifest.getProperty("rowChecksum")).isNotBlank();
        assertThat(Long.parseLong(manifest.getProperty("rowBytes")))
                .isEqualTo(Files.size(rowsFile(job)));
        assertThat(manifest.getProperty("rowChecksum"))
                .isEqualTo(DurableFiles.checksum(rowsFile(job)));
    }

    @Test
    void intactEvidenceIsServedNormally() {
        var source = source();
        var job = job(2);
        stage(source, job, List.of("x", "y"));

        var page = source.read(job, Optional.empty(), 10, lease());

        assertThat(page.rows()).containsExactly("x", "y");
    }

    @Test
    void truncatedEvidenceFailsClosedInsteadOfLookingLikeAShorterEpoch() throws IOException {
        // The danger is specific: the row loop stops at EOF, so a truncated rewards file would
        // otherwise be served as a complete epoch that simply had fewer delegators.
        var source = source();
        var job = job(3);
        stage(source, job, List.of("one", "two", "three"));

        try (var channel = java.nio.channels.FileChannel.open(rowsFile(job),
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate(Files.size(rowsFile(job)) - 2);
        }

        assertThatThrownBy(() -> source.read(job, Optional.empty(), 10, lease()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("truncated epoch would look like a complete one");
    }

    @Test
    void corruptEvidenceFailsClosed() throws IOException {
        var source = source();
        var job = job(4);
        stage(source, job, List.of("aaaa", "bbbb"));

        // Same length, different bytes: size alone would not catch this.
        byte[] bytes = Files.readAllBytes(rowsFile(job));
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(rowsFile(job), bytes);

        assertThatThrownBy(() -> source.read(job, Optional.empty(), 10, lease()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("is corrupt and cannot be reproduced");
    }

    @Test
    void missingEvidenceFailsClosed() throws IOException {
        var source = source();
        var job = job(5);
        stage(source, job, List.of("z"));
        Files.delete(rowsFile(job));

        assertThatThrownBy(() -> source.read(job, Optional.empty(), 10, lease()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("cannot be recomputed once the boundary has passed");
    }

    @Test
    void evidenceStagedWithoutIntegrityFieldsIsRefusedRatherThanTrusted() throws IOException {
        // A manifest predating hardening is exactly the file most likely to be damaged, so
        // accepting it because it has no checksum would defeat the guarantee.
        var source = source();
        var job = job(6);
        stage(source, job, List.of("q"));

        Properties manifest = new Properties();
        try (var in = Files.newInputStream(manifestFile(job))) { manifest.load(in); }
        manifest.remove("rowChecksum");
        try (var out = Files.newOutputStream(manifestFile(job))) { manifest.store(out, "downgraded"); }

        assertThatThrownBy(() -> source.read(job, Optional.empty(), 10, lease()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("staged before integrity hardening");
    }

    @Test
    void aChecksumDistinguishesEveryDistinctContent() throws IOException {
        var source = source();
        stage(source, job(7), List.of("alpha"));
        stage(source, job(8), List.of("beta"));

        assertThat(DurableFiles.checksum(rowsFile(job(7))))
                .isNotEqualTo(DurableFiles.checksum(rowsFile(job(8))));
        assertThat(DurableFiles.checksum(rowsFile(job(7))))
                .as("and is stable for the same content")
                .isEqualTo(DurableFiles.checksum(rowsFile(job(7))));
    }

    @Test
    void publishingIsAtomicAndLeavesNoTemporary() throws IOException {
        var source = source();
        var job = job(9);
        stage(source, job, List.of("m"));

        try (var files = Files.list(temp.resolve("reward"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("no .partial or .manifest.tmp survives a successful commit")
                    .noneMatch(name -> name.endsWith(".partial") || name.endsWith(".manifest.tmp"));
        }
        assertThat(rowsFile(job)).exists();
        assertThat(manifestFile(job)).exists();
    }
}
