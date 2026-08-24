package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockDecoder;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 2c: concurrency of the <b>actual</b> CBOR decode path.
 *
 * <p>The Phase 2c benchmark simulates decode cost, and the ordered-prefetch tests
 * use synthetic block objects. This test closes that gap by running genuine
 * serialized Conway block bodies through the real
 * {@link BlockSerializer}/{@link YaciBlockDecoder} path concurrently, and
 * asserting the results are identical to serial decoding.
 *
 * <p>Bodies are produced by {@link DevnetBlockBuilder}, which emits real block
 * CBOR. No live chainstate is opened: both deployments hold theirs, and attaching
 * a second process to a live RocksDB is unsafe.
 *
 * <p><b>Coverage boundary.</b> This test proves the decoder is safe to run on
 * several threads and that decoding is a pure function of its bytes. It does not
 * claim coverage of mainnet-era block shapes — multi-asset, datum, redeemer,
 * collateral and pre-Conway pointer decoding are asserted by the decoder's own
 * suites, not here. What is verified here is decode <em>concurrency</em>, which
 * is the property Phase 2c newly depends on.
 */
class RealBlockDecoderConcurrencyTest {

    private static final int DISTINCT_BLOCKS = 24;
    private static final int ITERATIONS = 40;
    private static final int THREADS = 8;

    private record Fixture(long blockNumber, long slot, byte[] cbor, byte[] blockHash) { }

    /** Builds a chain of genuine serialized Conway blocks. */
    private static List<Fixture> buildFixtures() {
        DevnetBlockBuilder builder = new DevnetBlockBuilder();
        List<Fixture> fixtures = new ArrayList<>();
        byte[] previous = null;
        for (int i = 0; i < DISTINCT_BLOCKS; i++) {
            long number = i + 1;
            long slot = number * 20;
            var built = builder.buildBlock(number, slot, previous, List.of());
            fixtures.add(new Fixture(number, slot, built.blockCbor(), built.blockHash()));
            previous = built.blockHash();
        }
        return fixtures;
    }

    private static YaciBlockDecoder decoder() {
        return new YaciBlockDecoder(slot -> slot / 432_000, slot -> 1_600_000_000L + slot, ignored -> null);
    }

    /** Stable rendering of everything the decode produces that downstream code reads. */
    private static String render(BlockSourceContext<Block> context) {
        Block block = context.block();
        StringBuilder text = new StringBuilder();
        text.append(context.blockNumber()).append('|').append(context.slot()).append('|')
                .append(context.epoch()).append('|').append(context.blockTime().getEpochSecond()).append('|')
                .append(HexFormat.of().formatHex(context.blockHash())).append('|')
                .append(HexFormat.of().formatHex(context.parentHash())).append('|')
                .append(block.getEra()).append('|')
                .append(block.getTransactionBodies() == null ? -1 : block.getTransactionBodies().size()).append('|')
                .append(block.getHeader().getHeaderBody().getBlockNumber()).append('|')
                .append(block.getHeader().getHeaderBody().getSlot());
        return text.toString();
    }

    @Test
    void realBlockBodiesDecodeIdenticallyUnderConcurrency() throws Exception {
        List<Fixture> fixtures = buildFixtures();
        assertThat(fixtures).hasSize(DISTINCT_BLOCKS);
        assertThat(fixtures.getFirst().cbor()).as("genuine serialized block bytes").isNotEmpty();

        YaciBlockDecoder decoder = decoder();

        // Serial reference.
        List<String> reference = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            reference.add(render(decoder.decode(fixture.blockNumber(),
                    new CanonicalBlockReference(fixture.blockNumber(), fixture.slot(), fixture.blockHash()),
                    fixture.cbor())));
        }

        // Concurrent: many iterations over distinct fixtures and repeatedly over
        // the same immutable bytes, which is what surfaces shared decoder state.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<List<String>>> tasks = new ArrayList<>();
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                tasks.add(() -> {
                    List<String> rendered = new ArrayList<>();
                    for (Fixture fixture : fixtures) {
                        rendered.add(render(decoder.decode(fixture.blockNumber(),
                                new CanonicalBlockReference(fixture.blockNumber(), fixture.slot(),
                                        fixture.blockHash()), fixture.cbor())));
                    }
                    return rendered;
                });
            }
            List<Future<List<String>>> results = pool.invokeAll(tasks, 5, TimeUnit.MINUTES);
            for (Future<List<String>> result : results) {
                assertThat(result.get()).as("concurrent decode equals serial decode").isEqualTo(reference);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedConcurrentDecodingOfTheSameBytesIsDeterministic() throws Exception {
        Fixture fixture = buildFixtures().getFirst();
        YaciBlockDecoder decoder = decoder();
        var reference = render(decoder.decode(fixture.blockNumber(),
                new CanonicalBlockReference(fixture.blockNumber(), fixture.slot(), fixture.blockHash()),
                fixture.cbor()));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < ITERATIONS * DISTINCT_BLOCKS; i++) {
                tasks.add(() -> render(decoder.decode(fixture.blockNumber(),
                        new CanonicalBlockReference(fixture.blockNumber(), fixture.slot(), fixture.blockHash()),
                        fixture.cbor())));
            }
            for (Future<String> result : pool.invokeAll(tasks, 5, TimeUnit.MINUTES)) {
                assertThat(result.get()).isEqualTo(reference);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sharedBlockSerializerSingletonIsSafeAcrossThreads() throws Exception {
        List<Fixture> fixtures = buildFixtures();
        List<String> reference = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            Block block = BlockSerializer.INSTANCE.deserialize(fixture.cbor());
            reference.add(block.getHeader().getHeaderBody().getBlockNumber() + ":"
                    + block.getHeader().getHeaderBody().getSlot() + ":" + block.getEra());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<List<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < ITERATIONS; i++) {
                tasks.add(() -> {
                    List<String> rendered = new ArrayList<>();
                    for (Fixture fixture : fixtures) {
                        Block block = BlockSerializer.INSTANCE.deserialize(fixture.cbor());
                        rendered.add(block.getHeader().getHeaderBody().getBlockNumber() + ":"
                                + block.getHeader().getHeaderBody().getSlot() + ":" + block.getEra());
                    }
                    return rendered;
                });
            }
            for (Future<List<String>> result : pool.invokeAll(tasks, 5, TimeUnit.MINUTES)) {
                assertThat(result.get()).isEqualTo(reference);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }
}
