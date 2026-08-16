package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MappingBlockArchiveSourceTest {
    @Test
    void delegatesExceptionalParentValidationUsingOriginalSourceBlock() {
        AtomicInteger reads = new AtomicInteger();
        BlockArchiveSource<String> source = new BlockArchiveSource<>() {
            @Override public Optional<BlockSourceContext<String>> readCanonical(long blockNumber) {
                reads.incrementAndGet();
                return Optional.of(context("canonical", new byte[] {99}));
            }
            @Override public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
                throw new UnsupportedOperationException();
            }
            @Override public long earliestRetainedBody() { return 0; }
            @Override public boolean extendsCanonicalParent(byte[] predecessorHash,
                                                            BlockSourceContext<String> current) {
                return predecessorHash.length == 1 && predecessorHash[0] == 42
                        && current.block().equals("canonical");
            }
        };
        var mapped = new MappingBlockArchiveSource<>(source,
                original -> context("fact", original.parentHash()));

        assertThat(mapped.extendsCanonicalParent(new byte[] {42}, context("fact", new byte[] {99}))).isTrue();
        assertThat(reads).hasValue(1);
    }

    private static BlockSourceContext<String> context(String value, byte[] parentHash) {
        return new BlockSourceContext<>(7, 7, 0, Instant.EPOCH,
                new byte[] {7}, parentHash, value);
    }
}
