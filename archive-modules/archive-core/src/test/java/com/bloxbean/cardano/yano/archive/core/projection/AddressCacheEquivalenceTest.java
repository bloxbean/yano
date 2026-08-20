package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionDigest;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The memoisation added to speed up projection must be an optimisation and nothing else.
 *
 * <p>These tests exist because a cache is exactly the kind of change that can silently alter
 * output — through eviction order, aliasing of mutable values, or cross-thread sharing — and
 * the projection's whole contract rests on deterministic, digest-verified bytes.
 */
class AddressCacheEquivalenceTest {

    private static String hex(int b, int len) {
        return String.format("%02x", b).repeat(len);
    }

    private static String shelleyAddress(int paymentFill, int stakeFill) {
        byte[] address = new byte[57];
        address[0] = 0;
        Arrays.fill(address, 1, 29, (byte) paymentFill);
        Arrays.fill(address, 29, 57, (byte) stakeFill);
        return HexUtil.encodeHexString(address);
    }

    /** Many outputs over a mixture of repeated and distinct addresses. */
    private static Block block(int transactions, int distinctAddresses) {
        List<TransactionBody> txs = new ArrayList<>();
        for (int i = 0; i < transactions; i++) {
            var out = TransactionOutput.builder()
                    .address(shelleyAddress(i % distinctAddresses + 1, i % 7 + 1))
                    .amounts(List.of(
                            Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000 + i)).build(),
                            Amount.builder().unit(hex(0xaa, 28) + "01").policyId(hex(0xaa, 28))
                                    .assetName("01").assetNameBytes(new byte[]{1})
                                    .quantity(BigInteger.valueOf(7)).build()))
                    .build();
            txs.add(TransactionBody.builder().txHash(hex(i % 200, 32)).fee(BigInteger.valueOf(170_000 + i))
                    .inputs(new LinkedHashSet<>(List.of(
                            TransactionInput.builder().transactionId(hex((i + 5) % 200, 32)).index(0).build())))
                    .outputs(List.of(out, out)).build());
        }
        return Block.builder().era(Era.Babbage)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(100).slot(2000).prevHash(hex(0x0a, 32)).blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(txs).transactionWitness(List.of()).invalidTransactions(List.of()).build();
    }

    private static BlockSourceContext<Block> context(Block block) {
        return new BlockSourceContext<>(100, 2000, 20, Instant.ofEpochSecond(1_600_002_000L),
                HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)), block);
    }

    private static String digestWithCache(Block block, int maxEntries) {
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        decoder.setAddressCacheMaxEntries(maxEntries);
        byte[] encoded = ProjectionFactCodec.encodeUtxoHistory(decoder.project(context(block)).block());
        return ProjectionDigest.ofChunks(List.of(encoded));
    }

    // ------------------------------------------------------------- equivalence

    @Test
    void cacheEnabledAndDisabledProduceIdenticalDigests() {
        Block dense = block(60, 12);
        String disabled = digestWithCache(dense, 0);
        String enabled = digestWithCache(dense, 4096);
        assertThat(enabled).isEqualTo(disabled);
    }

    @Test
    void everyCacheBoundProducesTheSameDigestIncludingBoundsThatForceSkips() {
        Block dense = block(60, 40);
        // A bound of 1 admits one entry and skips the rest: the uncached path must still be
        // taken for everything else, with identical output.
        String reference = digestWithCache(dense, 0);
        for (int bound : new int[]{0, 1, 2, 5, 39, 40, 41, 4096}) {
            assertThat(digestWithCache(dense, bound))
                    .as("cache bound %d", bound)
                    .isEqualTo(reference);
        }
    }

    @Test
    void repeatedDecodesWithAWarmDecoderStayIdentical() {
        Block dense = block(40, 10);
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        String first = ProjectionDigest.ofChunks(List.of(
                ProjectionFactCodec.encodeUtxoHistory(decoder.project(context(dense)).block())));
        for (int i = 0; i < 20; i++) {
            assertThat(ProjectionDigest.ofChunks(List.of(
                    ProjectionFactCodec.encodeUtxoHistory(decoder.project(context(dense)).block()))))
                    .isEqualTo(first);
        }
    }

    // ------------------------------------------------------------- boundedness

    @Test
    void theCacheIsBoundedAndReportsSkippedAdmissions() {
        Block dense = block(60, 40);
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        decoder.setAddressCacheMaxEntries(3);
        decoder.project(context(dense));

        var stats = decoder.addressCacheStats();
        assertThat(stats.maxEntries()).isEqualTo(3);
        assertThat(stats.admissionsSkipped())
                .as("a 3-entry bound over 40 distinct addresses must skip admissions")
                .isPositive();
    }

    @Test
    void aGenerousBoundSkipsNothingAndHitsOften() {
        Block dense = block(60, 8);
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        decoder.project(context(dense));

        var stats = decoder.addressCacheStats();
        assertThat(stats.admissionsSkipped()).isZero();
        assertThat(stats.hits()).isPositive();
        assertThat(stats.hitRate()).isGreaterThan(0.5);
    }

    @Test
    void countersAccumulateAcrossBlocks() {
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        decoder.project(context(block(20, 5)));
        long afterOne = decoder.addressCacheStats().hits() + decoder.addressCacheStats().misses();
        decoder.project(context(block(20, 5)));
        long afterTwo = decoder.addressCacheStats().hits() + decoder.addressCacheStats().misses();
        assertThat(afterTwo).isGreaterThan(afterOne);
    }

    @Test
    void aNegativeBoundIsRejected() {
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        assertThatThrownBy(() -> decoder.setAddressCacheMaxEntries(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ thread safety

    @Test
    void oneDecoderServingConcurrentThreadsProducesIdenticalDigests() throws Exception {
        // The cache is per-decode-call and thread-confined, so a single decoder instance may
        // legitimately serve several worker threads. This asserts that arrangement is safe
        // and that no thread observes another's partial state.
        Block dense = block(50, 15);
        var decoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        String expected = ProjectionDigest.ofChunks(List.of(
                ProjectionFactCodec.encodeUtxoHistory(decoder.project(context(dense)).block())));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> work = new ArrayList<>();
            for (int i = 0; i < threads * 8; i++) {
                work.add(() -> ProjectionDigest.ofChunks(List.of(
                        ProjectionFactCodec.encodeUtxoHistory(decoder.project(context(dense)).block()))));
            }
            for (Future<String> result : pool.invokeAll(work)) {
                assertThat(result.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
