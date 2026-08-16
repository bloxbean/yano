package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CycleCachingBlockArchiveSourceTest {
    @Test
    void concurrentProjectionsDecodeEachCanonicalBlockOncePerCycle() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        BlockArchiveSource<String> delegate = new FixtureSource(reads);
        var source = new CycleCachingBlockArchiveSource<>(delegate, 10);
        source.beginCycle();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> (java.util.concurrent.Callable<String>) () ->
                            source.readCanonical(7).orElseThrow().block())
                    .toList();
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo("block-7");
        }
        var first = source.endCycle();

        assertThat(reads).hasValue(1);
        assertThat(first.decodedBlocks()).isEqualTo(1);
        assertThat(first.cacheHits()).isEqualTo(3);

        source.beginCycle();
        assertThat(source.readCanonical(7)).isPresent();
        source.endCycle();
        assertThat(reads).hasValue(2);
    }

    @Test
    void canonicalIdentityChecksBypassDecodedBodyCache() {
        AtomicInteger reads = new AtomicInteger();
        var source = new CycleCachingBlockArchiveSource<>(new FixtureSource(reads), 10);
        source.beginCycle();
        source.readCanonical(3);
        assertThat(source.canonicalReference(3)).isPresent();
        source.endCycle();

        assertThat(reads).hasValue(1);
    }

    @Test
    void forwardsExceptionalParentValidationToDelegate() {
        AtomicInteger reads = new AtomicInteger();
        var source = new CycleCachingBlockArchiveSource<>(new FixtureSource(reads), 10);
        var current = new BlockSourceContext<>(7, 7, 0, Instant.EPOCH,
                new byte[] {7}, new byte[] {99}, "block-7");

        assertThat(source.extendsCanonicalParent(new byte[] {42}, current)).isTrue();
    }

    private record FixtureSource(AtomicInteger reads) implements BlockArchiveSource<String> {
        @Override
        public Optional<BlockSourceContext<String>> readCanonical(long blockNumber) {
            reads.incrementAndGet();
            return Optional.of(new BlockSourceContext<>(blockNumber, blockNumber, 0, Instant.EPOCH,
                    new byte[]{(byte) blockNumber}, new byte[0], "block-" + blockNumber));
        }

        @Override
        public Optional<CanonicalBlockReference> canonicalReference(long blockNumber) {
            return Optional.of(new CanonicalBlockReference(blockNumber, blockNumber,
                    new byte[]{(byte) blockNumber}));
        }

        @Override
        public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
            return new ArchiveSourceLease() {
                public UUID leaseId() { return new UUID(0, 1); }
                public Instant expiresAt() { return expiresAt; }
                public ArchiveSourceLease renew(Instant value) { return this; }
                public void close() { }
            };
        }

        @Override
        public long earliestRetainedBody() { return 0; }

        @Override
        public boolean extendsCanonicalParent(byte[] predecessorHash, BlockSourceContext<String> current) {
            return predecessorHash.length == 1 && predecessorHash[0] == 42;
        }
    }
}
