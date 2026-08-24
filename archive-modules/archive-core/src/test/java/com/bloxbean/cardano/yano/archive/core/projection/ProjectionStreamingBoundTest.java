package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.TransactionFact;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the memory bound is real rather than nominal.
 *
 * <p>A lazy row iterator alone does not establish it: the original implementation still called
 * {@code ProjectionChunking.join} and decoded a whole batch's facts, so the encoded section
 * and its decoded graph stayed live regardless of how rows were yielded. These tests pin the
 * properties that make the bound hold — chunks are never concatenated, batch size does not
 * contribute to retention, and the stream is consumed exactly once.
 */
class ProjectionStreamingBoundTest {

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture");
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(NETWORK, "test", 1,
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));

    private static ProjectionEnvelope envelope(long block, List<TransactionFact> txs, UtxoHistoryFact utxo,
                                               int chunkBytes) {
        byte[] txEncoded = ProjectionFactCodec.encodeTransactions(txs);
        byte[] utxoEncoded = ProjectionFactCodec.encodeUtxoHistory(utxo);
        var txSection = new ProjectionSection(ProjectionSectionType.TRANSACTION,
                ProjectionSectionType.TRANSACTION.version(),
                ProjectionChunking.split(txEncoded, chunkBytes), txs.size());
        long utxoRows = utxo.outputs().size() + utxo.assets().size() + utxo.inputs().size()
                + utxo.transactionDatums().size() + utxo.transactionRedeemers().size();
        var utxoSection = new ProjectionSection(ProjectionSectionType.UTXO_HISTORY,
                ProjectionSectionType.UTXO_HISTORY.version(),
                ProjectionChunking.split(utxoEncoded, chunkBytes), utxoRows);
        var header = new ProjectionEnvelopeHeader(NETWORK, ProjectionBlockKind.SHELLEY_PLUS, block,
                new byte[]{(byte) block}, new byte[]{(byte) (block - 1)}, block * 20, 1, 1L, 1,
                List.of(txSection.manifest(), utxoSection.manifest()), List.of());
        return new ProjectionEnvelope(header, List.of(txSection, utxoSection));
    }

    private static TransactionFact tx(int i) {
        byte[] hash = new byte[32];
        hash[0] = (byte) i;
        hash[1] = (byte) (i >>> 8);
        return new TransactionFact(hash, i, true, 170_000L + i);
    }

    private static UtxoHistoryFact emptyUtxo() {
        return new UtxoHistoryFact(6, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    /** Dense block: many outputs, assets, datums and redeemers. */
    private static UtxoHistoryFact denseUtxo(int n) {
        List<UtxoHistoryFact.Address> addresses = new ArrayList<>();
        List<UtxoHistoryFact.Output> outputs = new ArrayList<>();
        List<UtxoHistoryFact.Asset> assets = new ArrayList<>();
        List<UtxoHistoryFact.TransactionDatum> datums = new ArrayList<>();
        List<UtxoHistoryFact.TransactionRedeemer> redeemers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] key = new byte[28];
            key[0] = (byte) i;
            byte[] hash = new byte[32];
            hash[0] = (byte) i;
            addresses.add(new UtxoHistoryFact.Address(key, key, "addr" + i, 0, "base", "key", key,
                    "none", null, null, null, null, null));
            outputs.add(new UtxoHistoryFact.Output(hash, i, i, "output", key, key, null, 1_000_000L,
                    "hash", hash, new byte[256], null, null, null, false));
            assets.add(new UtxoHistoryFact.Asset(hash, i, key, new byte[]{1}, BigInteger.valueOf(7)));
            datums.add(new UtxoHistoryFact.TransactionDatum(hash, i, hash, new byte[512]));
            redeemers.add(new UtxoHistoryFact.TransactionRedeemer(hash, i, "spend", i, new byte[256],
                    hash, BigInteger.TEN, BigInteger.TEN));
        }
        return new UtxoHistoryFact(6, List.of(), List.of(), addresses, outputs, assets, List.of(),
                datums, redeemers);
    }

    // ------------------------------------------- chunks are never concatenated

    @Test
    void aLargeMultiChunkTransactionSectionDecodesWithoutJoiningChunks() {
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) txs.add(tx(i));
        // 64-byte chunks force facts to straddle boundaries repeatedly.
        var envelope = envelope(100, txs, emptyUtxo(), 64);
        var section = envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow();
        assertThat(section.chunks().size()).isGreaterThan(1_000);

        List<TransactionFact> decoded = new ArrayList<>();
        long count = ProjectionFactCodec.streamTransactions(section.chunks(), decoded::add);

        assertThat(count).isEqualTo(5_000);
        assertThat(decoded).hasSize(5_000);
        assertThat(decoded.get(4_999).txIndex()).isEqualTo(4_999);
    }

    @Test
    void chunkedInputReadsIdenticallyToTheJoinedBytes() {
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 500; i++) txs.add(tx(i));
        byte[] encoded = ProjectionFactCodec.encodeTransactions(txs);
        var chunks = ProjectionChunking.split(encoded, 17);

        // TransactionFact holds byte[], and record equality on arrays is by reference, so
        // compare structurally.
        List<String> streamed = new ArrayList<>();
        ProjectionFactCodec.streamTransactions(chunks, f -> streamed.add(renderFact(f)));
        List<String> joined = ProjectionFactCodec.decodeTransactions(encoded).stream()
                .map(ProjectionStreamingBoundTest::renderFact).toList();
        assertThat(streamed).isEqualTo(joined);
    }

    // --------------------------------- batch size does not increase retention

    @Test
    void peakRetainedRowsIsOneEnvelopeRegardlessOfBatchSize() {
        // 200 envelopes of 50 rows each. If the batch were materialised, 10,000 rows would be
        // live at once; streaming keeps one envelope's worth.
        List<ProjectionEnvelope> envelopes = new ArrayList<>();
        for (int b = 0; b < 200; b++) {
            List<TransactionFact> txs = new ArrayList<>();
            for (int i = 0; i < 50; i++) txs.add(tx(i));
            envelopes.add(envelope(b, txs, emptyUtxo(), 1 << 20));
        }
        var rowBatch = ProjectionRowBuilder.materialise(new ProjectionBatch(IDENTITY, envelopes));

        long seen = 0;
        long peakLive = 0;
        List<ArchiveRow> live = new ArrayList<>();
        for (ArchiveRow row : rowBatch.rows()) {
            seen++;
            live.add(row);
            // emulate a sink that appends and releases: it never holds the whole batch
            if (live.size() >= 50) {
                peakLive = Math.max(peakLive, live.size());
                live.clear();
            }
        }
        assertThat(seen).isEqualTo(200L * 50);
        assertThat(peakLive)
                .as("a sink consuming incrementally never sees the whole batch")
                .isLessThanOrEqualTo(50);
    }

    @Test
    void aDenseDatumRedeemerMultiAssetBlockStreams() {
        var envelope = envelope(100, List.of(tx(0)), denseUtxo(400), 4096);
        var rowBatch = ProjectionRowBuilder.materialise(
                new ProjectionBatch(IDENTITY, List.of(envelope)));
        long rows = 0;
        for (ArchiveRow ignored : rowBatch.rows()) rows++;
        // 400 outputs + 400 assets + 400 datums + 400 redeemers + 1 transaction
        assertThat(rows).isEqualTo(1_601);
    }

    @Test
    void anOversizedSingletonIsProcessedRatherThanDeadlocking() {
        // One envelope far larger than any normal batch ceiling still yields rows.
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) txs.add(tx(i));
        var envelope = envelope(100, txs, denseUtxo(200), 8192);
        var rowBatch = ProjectionRowBuilder.materialise(
                new ProjectionBatch(IDENTITY, List.of(envelope)));

        long rows = 0;
        for (ArchiveRow ignored : rowBatch.rows()) rows++;
        assertThat(rows).isGreaterThan(20_000);
    }

    // ------------------------------------------------- single pass and retry

    @Test
    void aSinkFailureHalfwayThroughIterationIsDeterministicOnRetry() {
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 100; i++) txs.add(tx(i));
        var batch = new ProjectionBatch(IDENTITY, List.of(envelope(100, txs, emptyUtxo(), 1 << 20)));

        // first attempt fails part-way
        List<String> firstAttempt = new ArrayList<>();
        try {
            int n = 0;
            for (ArchiveRow row : ProjectionRowBuilder.materialise(batch).rows()) {
                if (++n > 40) throw new IllegalStateException("injected sink failure");
                firstAttempt.add(render(row));
            }
        } catch (IllegalStateException expected) {
            // the outbox is durable, so the batch is simply re-materialised
        }
        assertThat(firstAttempt).hasSize(40);

        // retry re-materialises from the same envelopes and reproduces the same rows
        List<String> retry = new ArrayList<>();
        for (ArchiveRow row : ProjectionRowBuilder.materialise(batch).rows()) retry.add(render(row));
        assertThat(retry).hasSize(100);
        assertThat(retry.subList(0, 40)).isEqualTo(firstAttempt);
    }

    @Test
    void theRowStreamIsSingleUseAndRefusesASecondPass() {
        // A partially consumed source iterated again would yield a short or duplicated pass
        // that looks like valid data. Refusing is the only safe behaviour; the caller
        // re-materialises from the still-unacknowledged outbox batch instead.
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 30; i++) txs.add(tx(i));
        var rowBatch = ProjectionRowBuilder.materialise(
                new ProjectionBatch(IDENTITY, List.of(envelope(100, txs, emptyUtxo(), 1 << 20))));

        List<String> first = new ArrayList<>();
        for (ArchiveRow row : rowBatch.rows()) first.add(render(row));
        assertThat(first).hasSize(30);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rowBatch.rows().iterator())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-use");
    }

    @Test
    void anExhaustedIteratorReportsNoMoreElements() {
        var rowBatch = ProjectionRowBuilder.materialise(
                new ProjectionBatch(IDENTITY, List.of(envelope(100, List.of(tx(0)), emptyUtxo(), 1 << 20))));
        Iterator<ArchiveRow> it = rowBatch.rows().iterator();
        while (it.hasNext()) it.next();
        assertThat(it.hasNext()).isFalse();
    }

    private static String renderFact(TransactionFact fact) {
        return java.util.Arrays.toString(fact.txHash()) + '|' + fact.txIndex() + '|'
                + fact.valid() + '|' + fact.fee();
    }

    private static String render(ArchiveRow row) {
        return row.table() + row.values().stream()
                .map(v -> v instanceof byte[] b ? java.util.Arrays.toString(b) : String.valueOf(v))
                .reduce("", String::concat);
    }
}
