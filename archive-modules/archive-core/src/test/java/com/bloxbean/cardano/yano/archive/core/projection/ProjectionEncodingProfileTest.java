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
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockArchiveDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attributes the projection hot-path cost per stage, so optimisation targets the stage that
 * actually dominates rather than the one that looks expensive.
 *
 * <p>Not a pass/fail gate — it prints a breakdown and asserts only that every stage ran. The
 * authoritative regression number is the isolated alternating history-off/on node benchmark;
 * this exists to explain that number and to attribute changes to it.
 *
 * <p>Reference point from the isolated benchmark: 2,410 blk/s without history and 2,070 with,
 * i.e. roughly <strong>68 microseconds per block</strong> of projection cost, against a 5%
 * budget of about 21 microseconds.
 */
class ProjectionEncodingProfileTest {

    private static final int WARMUP = 200;
    private static final int ITERATIONS = 2_000;

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

    /** A block shaped like a dense Babbage/Conway block: 40 transactions, multi-asset outputs. */
    private static Block denseBlock() {
        List<TransactionBody> txs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            List<Amount> amounts = new ArrayList<>();
            amounts.add(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000 + i)).build());
            amounts.add(Amount.builder().unit(hex(0xaa, 28) + "01").policyId(hex(0xaa, 28))
                    .assetName("01").assetNameBytes(new byte[]{1}).quantity(BigInteger.valueOf(7)).build());
            var out = TransactionOutput.builder().address(shelleyAddress(i % 20 + 1, i % 15 + 1))
                    .amounts(amounts).build();
            txs.add(TransactionBody.builder().txHash(hex(i % 200, 32)).fee(BigInteger.valueOf(170_000 + i))
                    .inputs(new LinkedHashSet<>(List.of(
                            TransactionInput.builder().transactionId(hex((i + 5) % 200, 32)).index(0).build(),
                            TransactionInput.builder().transactionId(hex((i + 9) % 200, 32)).index(1).build())))
                    .outputs(List.of(out, out)).build());
        }
        return Block.builder().era(Era.Babbage)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(100).slot(2000).prevHash(hex(0x0a, 32)).blockHash(hex(0x0b, 32)).build()).build())
                .transactionBodies(txs).transactionWitness(List.of()).invalidTransactions(List.of()).build();
    }

    private static long timed(int iterations, Runnable work) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) work.run();
        return (System.nanoTime() - start) / iterations;
    }

    @Test
    void attributeProjectionCostPerStage() {
        Block block = denseBlock();
        var context = new BlockSourceContext<>(100L, 2000L, 20L, Instant.ofEpochSecond(1_600_002_000L),
                HexUtil.decodeHexString(hex(0x0b, 32)), HexUtil.decodeHexString(hex(0x0a, 32)), block);

        var txDecoder = new YaciBlockArchiveDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);
        var utxoDecoder = new YaciUtxoHistoryDecoder(slot -> slot / 100, slot -> 1_600_000_000L + slot);

        // Warm up every stage together so JIT state is comparable across measurements.
        for (int i = 0; i < WARMUP; i++) {
            var t = txDecoder.project(context).block().transactions();
            var u = utxoDecoder.project(context).block();
            byte[] te = ProjectionFactCodec.encodeTransactions(t);
            byte[] ue = ProjectionFactCodec.encodeUtxoHistory(u);
            new ProjectionSection(ProjectionSectionType.TRANSACTION, 2,
                    ProjectionChunking.split(te, ProjectionChunking.DEFAULT_CHUNK_BYTES), t.size());
            new ProjectionSection(ProjectionSectionType.UTXO_HISTORY, 5,
                    ProjectionChunking.split(ue, ProjectionChunking.DEFAULT_CHUNK_BYTES), 1);
        }

        long txProject = timed(ITERATIONS, () -> txDecoder.project(context).block().transactions());
        long utxoProject = timed(ITERATIONS, () -> utxoDecoder.project(context).block());

        var txFacts = txDecoder.project(context).block().transactions();
        var utxoFact = utxoDecoder.project(context).block();

        long txEncode = timed(ITERATIONS, () -> ProjectionFactCodec.encodeTransactions(txFacts));
        long utxoEncode = timed(ITERATIONS, () -> ProjectionFactCodec.encodeUtxoHistory(utxoFact));

        byte[] txEncoded = ProjectionFactCodec.encodeTransactions(txFacts);
        byte[] utxoEncoded = ProjectionFactCodec.encodeUtxoHistory(utxoFact);

        long chunking = timed(ITERATIONS, () -> {
            ProjectionChunking.split(txEncoded, ProjectionChunking.DEFAULT_CHUNK_BYTES);
            ProjectionChunking.split(utxoEncoded, ProjectionChunking.DEFAULT_CHUNK_BYTES);
        });

        var txChunks = ProjectionChunking.split(txEncoded, ProjectionChunking.DEFAULT_CHUNK_BYTES);
        var utxoChunks = ProjectionChunking.split(utxoEncoded, ProjectionChunking.DEFAULT_CHUNK_BYTES);

        // Section construction copies every chunk defensively.
        long sectionBuild = timed(ITERATIONS, () -> {
            new ProjectionSection(ProjectionSectionType.TRANSACTION, 2, txChunks, txFacts.size());
            new ProjectionSection(ProjectionSectionType.UTXO_HISTORY, 5, utxoChunks, 1);
        });

        var txSection = new ProjectionSection(ProjectionSectionType.TRANSACTION, 2, txChunks, txFacts.size());
        var utxoSection = new ProjectionSection(ProjectionSectionType.UTXO_HISTORY, 5, utxoChunks, 1);

        // manifest() digests the payload; the accessor copies it again on the write path.
        long manifest = timed(ITERATIONS, () -> {
            txSection.manifest();
            utxoSection.manifest();
        });
        long accessorCopy = timed(ITERATIONS, () -> {
            txSection.chunks();
            utxoSection.chunks();
        });

        long total = txProject + utxoProject + txEncode + utxoEncode + chunking + sectionBuild
                + manifest + accessorCopy;

        System.out.printf("%n=== ADR-039 projection cost attribution (dense 40-tx block) ===%n");
        System.out.printf("  payload: transaction=%d B, utxo-history=%d B, total=%d B%n",
                txEncoded.length, utxoEncoded.length, txEncoded.length + utxoEncoded.length);
        record Stage(String name, long nanos) { }
        List<Stage> stages = List.of(
                new Stage("derive transaction facts", txProject),
                new Stage("derive utxo-history facts", utxoProject),
                new Stage("encode transaction", txEncode),
                new Stage("encode utxo-history", utxoEncode),
                new Stage("chunk split (copy)", chunking),
                new Stage("section construct (copy)", sectionBuild),
                new Stage("manifest + digest", manifest),
                new Stage("chunks() accessor (copy)", accessorCopy));
        for (Stage stage : stages) {
            System.out.printf("  %-28s %8.1f us  %5.1f%%%n", stage.name(),
                    stage.nanos() / 1000.0, 100.0 * stage.nanos() / total);
        }
        System.out.printf("  %-28s %8.1f us%n", "TOTAL", total / 1000.0);
        System.out.printf("  isolated-benchmark budget:   ~68 us/block observed, ~21 us/block for a 5%% target%n%n");

        assertThat(total).isPositive();
        assertThat(txEncoded.length).isPositive();
        assertThat(utxoEncoded.length).isPositive();
    }
}
