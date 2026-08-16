package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.ByronEpochBoundaryReference;
import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ChainBlockArchiveSourceTest {
    @TempDir Path temp;

    @Test
    void acceptsOnlyTheProvenByronEpochBoundaryBridge() {
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            ChainBlockReader reader = reader(Era.Byron,
                    Optional.of(new ByronEpochBoundaryReference(21_600, new byte[] {2}, new byte[] {1})));
            var source = new ChainBlockArchiveSource<String>(reader, (number, reference, body) -> null, hot);
            var current = context(new byte[] {2});

            assertThat(source.extendsCanonicalParent(new byte[] {1}, current)).isTrue();
            assertThat(source.extendsCanonicalParent(new byte[] {3}, current)).isFalse();
        }
    }

    @Test
    void neverUsesEpochBoundaryBridgeOutsideByron() {
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            ChainBlockReader reader = reader(Era.Conway,
                    Optional.of(new ByronEpochBoundaryReference(21_600, new byte[] {2}, new byte[] {1})));
            var source = new ChainBlockArchiveSource<String>(reader, (number, reference, body) -> null, hot);

            assertThat(source.extendsCanonicalParent(new byte[] {1}, context(new byte[] {2}))).isFalse();
        }
    }

    private static ChainBlockReader reader(Era era, Optional<ByronEpochBoundaryReference> ebb) {
        return new ChainBlockReader() {
            @Override public ChainTip getLocalTip() { return null; }
            @Override public byte[] getBlockByNumber(long blockNumber) { return null; }
            @Override public Era getBlockEra(long blockNumber) { return era; }
            @Override public Optional<ByronEpochBoundaryReference> getByronEpochBoundaryBlock(long slot) {
                return ebb;
            }
        };
    }

    private static BlockSourceContext<String> context(byte[] parentHash) {
        return new BlockSourceContext<>(21_587, 21_600, 0, Instant.EPOCH,
                new byte[] {3}, parentHash, "block");
    }
}
