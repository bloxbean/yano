package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
                ArchiveDatasetId.EPOCH_STAKE, 1, 12, 100, 500, new byte[] {1},
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
}
