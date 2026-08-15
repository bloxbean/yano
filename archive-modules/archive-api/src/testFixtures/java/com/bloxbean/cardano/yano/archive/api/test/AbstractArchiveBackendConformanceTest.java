package com.bloxbean.cardano.yano.archive.api.test;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.EpochRange;
import com.bloxbean.cardano.yano.archive.api.ArchivePageCursor;
import com.bloxbean.cardano.yano.archive.api.ArchiveQuery;
import com.bloxbean.cardano.yano.archive.api.ArchiveRecord;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared black-box contract that every archive engine must execute. */
public abstract class AbstractArchiveBackendConformanceTest {
    private ArchiveBackend backend;

    protected abstract ArchiveBackend createBackend() throws Exception;

    protected final ArchiveBackend backend() {
        return backend;
    }

    @BeforeEach
    void openBackend() throws Exception {
        backend = createBackend();
    }

    @AfterEach
    void closeBackend() {
        if (backend != null) backend.close();
    }

    @Test
    void closeWithoutCommitAbortsRowsAndReceipt() {
        ArchiveJob job = job(0, 0);
        try (var write = backend.begin(job)) {
            write.append(row(job));
        }
        assertThat(backend.findReceipt(job.jobId())).isEmpty();
        assertThat(backend.coverage(job.dataset()).covers(0)).isFalse();
    }

    @Test
    void retryingCommittedJobReturnsSameReceiptWithoutAdvancingGeneration() {
        ArchiveJob job = job(0, 9);
        var first = commit(job);
        var retry = commit(job);

        assertThat(retry).isEqualTo(first);
        assertThat(backend.findReceipt(job.jobId())).contains(first);
        assertThat(backend.coverage(job.dataset()).covers(0)).isTrue();
        assertThat(backend.coverage(job.dataset()).covers(9)).isTrue();
    }

    @Test
    void readSessionPinsGenerationAcrossLaterCommit() {
        commit(job(0, 9));
        try (var read = backend.openReadSession()) {
            long pinned = read.generation();
            commit(job(10, 19));
            assertThat(read.generation()).isEqualTo(pinned);
            assertThat(backend.coverage(ArchiveDatasetId.TRANSACTION).covers(19)).isTrue();
        }
    }

    @Test
    void repositoriesUsePinnedCoverageBoundFiltersAndOpaqueCursors() {
        ArchiveJob job = job(0, 9);
        byte[] first = job.anchorBlockHash();
        byte[] second = first.clone();
        second[31] ^= 1;
        try (var write = backend.begin(job)) {
            write.append(transactionRow(job, first, 1, 0));
            write.append(transactionRow(job, second, 2, 1));
            write.commit();
        }

        try (var read = backend.openReadSession()) {
            var repository = backend.repositories().records(ArchiveDatasetId.TRANSACTION);
            var firstPage = repository.query(read, new ArchiveQuery(new BlockRange(0, 9), Map.of(),
                    ArchivePageCursor.Order.ASC, 1, Optional.empty()));
            assertThat(firstPage.complete()).isTrue();
            assertThat(firstPage.rows()).hasSize(1);
            assertThat(firstPage.nextCursor()).isPresent();

            var secondPage = repository.query(read, new ArchiveQuery(new BlockRange(0, 9), Map.of(),
                    ArchivePageCursor.Order.ASC, 1, firstPage.nextCursor()));
            assertThat(secondPage.rows()).hasSize(1);
            assertThat((byte[]) secondPage.rows().getFirst().value("tx_hash")).containsExactly(second);

            var point = repository.query(read, new ArchiveQuery(new BlockRange(0, 9), Map.of("tx_hash", first),
                    ArchivePageCursor.Order.ASC, 10, Optional.empty()));
            assertThat(point.complete()).isTrue();
            assertThat(point.rows()).singleElement().extracting(ArchiveRecord::table)
                    .isEqualTo("chain_transaction");
        }
    }

    @Test
    void repeatedAddressDimensionMergesAcrossJobsAndRecomputesAfterInvalidation() {
        byte[] addressKey = new byte[32];
        Arrays.fill(addressKey, (byte) 7);
        ArchiveJob first = utxoJob(0, (byte) 1);
        ArchiveJob second = utxoJob(1, (byte) 2);
        commitUtxo(first, addressKey, (byte) 1);
        commitUtxo(second, addressKey, (byte) 2);

        assertAddress(first.dataset(), new BlockRange(0, 1), addressKey, 0);
        backend.invalidate(ArchiveDatasetId.UTXO_HISTORY, new BlockRange(0, 0));
        assertAddress(first.dataset(), new BlockRange(1, 1), addressKey, 1);
        backend.invalidate(ArchiveDatasetId.UTXO_HISTORY, new BlockRange(1, 1));
        try (var read = backend.openReadSession()) {
            var result = backend.repositories().records(ArchiveDatasetId.UTXO_HISTORY).query(read,
                    new ArchiveQuery(new BlockRange(1, 1), Map.of("__table", "addresses"),
                            ArchivePageCursor.Order.ASC, 10, Optional.empty()));
            assertThat(result.rows()).isEmpty();
        }
    }

    @Test
    void repeatedDatumPayloadAcrossTransactionsRemainsAppendOnly() {
        ArchiveJob job = utxoRangeJob(0, 1, (byte) 11);
        byte[] firstTx = new byte[32];
        byte[] secondTx = new byte[32];
        Arrays.fill(firstTx, (byte) 10);
        Arrays.fill(secondTx, (byte) 11);
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 12);
        byte[] cbor = new byte[] {(byte) 0xd8, 0x79, (byte) 0x80};

        try (var write = backend.begin(job)) {
            write.append(new ArchiveRow("transaction_datums", List.of(firstTx, 0, hash, cbor,
                    job.anchorBlockHash(), 0L, 0L, 0L, 0L, job.jobId())));
            write.append(new ArchiveRow("transaction_datums", List.of(secondTx, 1, hash, cbor,
                    job.anchorBlockHash(), 1L, 10L, 0L, 0L, job.jobId())));
            write.commit();
        }

        assertThat(backend.coverage(ArchiveDatasetId.UTXO_HISTORY).covers(0)).isTrue();
        assertThat(backend.coverage(ArchiveDatasetId.UTXO_HISTORY).covers(1)).isTrue();
    }

    @Test
    void repeatedPointerAddressAcceptsJdbcNumericRepresentations() {
        byte[] addressKey = new byte[32];
        Arrays.fill(addressKey, (byte) 31);
        commitPointerUtxo(utxoJob(0, (byte) 31), addressKey, (byte) 31);
        commitPointerUtxo(utxoJob(1, (byte) 32), addressKey, (byte) 32);

        assertAddress(ArchiveDatasetId.UTXO_HISTORY, new BlockRange(0, 1), addressKey, 0);
    }

    @Test
    void epochRollbackUsesBoundarySlotEvenInsideSameEpoch() {
        ArchiveJob first = epochJob(7, 100, (byte) 21);
        ArchiveJob replacement = epochJob(8, 200, (byte) 22);
        commitAdaPot(first);
        commitAdaPot(replacement);

        assertThat(backend.invalidateEpochJobsAfterSlot(ArchiveDatasetId.ADA_POT, 150)).isEqualTo(1);
        assertThat(backend.coverage(ArchiveDatasetId.ADA_POT).covers(7)).isTrue();
        assertThat(backend.coverage(ArchiveDatasetId.ADA_POT).covers(8)).isFalse();
    }

    private void assertAddress(ArchiveDatasetId dataset, BlockRange range, byte[] addressKey, long firstSeen) {
        try (var read = backend.openReadSession()) {
            var result = backend.repositories().records(dataset).query(read,
                    new ArchiveQuery(range, Map.of("__table", "addresses", "address_key", addressKey),
                            ArchivePageCursor.Order.ASC, 10, Optional.empty()));
            assertThat(result.rows()).singleElement()
                    .extracting(row -> ((Number) row.value("first_seen_block_number")).longValue())
                    .isEqualTo(firstSeen);
        }
    }

    private void commitUtxo(ArchiveJob job, byte[] addressKey, byte txMarker) {
        byte[] txHash = new byte[32];
        Arrays.fill(txHash, txMarker);
        try (var write = backend.begin(job)) {
            write.append(new ArchiveRow("addresses", Arrays.asList(addressKey, new byte[] {9}, "addr_test",
                    0, "enterprise", "key", new byte[] {3}, "none", null, null,
                    null, null, null, job.range().startInclusive(), job.anchorSlot(), 0L)));
            // A batch may observe the same address in several blocks/outputs;
            // the physical dimension still has exactly one canonical row.
            write.append(new ArchiveRow("addresses", Arrays.asList(addressKey, new byte[] {9}, "addr_test",
                    0, "enterprise", "key", new byte[] {3}, "none", null, null,
                    null, null, null, job.range().startInclusive(), job.anchorSlot(), 0L)));
            write.append(new ArchiveRow("transaction_outputs", Arrays.asList(txHash, 0, 0, "ordinary",
                    addressKey, new byte[] {3}, null, 10L, "none", null, null, null, null, null, false,
                    job.anchorBlockHash(), job.range().startInclusive(), job.anchorSlot(), 0L, 0L, job.jobId())));
            write.commit();
        }
    }

    private void commitPointerUtxo(ArchiveJob job, byte[] addressKey, byte txMarker) {
        byte[] txHash = new byte[32];
        Arrays.fill(txHash, txMarker);
        try (var write = backend.begin(job)) {
            write.append(new ArchiveRow("addresses", Arrays.asList(addressKey, new byte[] {9}, "addr_pointer",
                    0, "ptr", "key", new byte[] {3}, "pointer", null, null,
                    42L, 1, 2, job.range().startInclusive(), job.anchorSlot(), 0L)));
            write.append(new ArchiveRow("transaction_outputs", Arrays.asList(txHash, 0, 0, "ordinary",
                    addressKey, new byte[] {3}, new byte[28], 10L, "none", null, null, null, null, null, false,
                    job.anchorBlockHash(), job.range().startInclusive(), job.anchorSlot(), 0L, 0L, job.jobId())));
            write.commit();
        }
    }

    private static ArchiveJob utxoJob(long block, byte marker) {
        return utxoRangeJob(block, block, marker);
    }

    private static ArchiveJob utxoRangeJob(long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.UTXO_HISTORY,
                ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).projectionVersion(), new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "fixture-v1");
    }

    private static ArchiveJob epochJob(long epoch, long boundarySlot, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.ADA_POT, 1, new EpochRange(epoch, epoch),
                new ArchiveRangeAnchor(boundarySlot, hash, boundarySlot, hash), "fixture-v1");
    }

    private void commitAdaPot(ArchiveJob job) {
        try (var write = backend.begin(job)) {
            write.append(new ArchiveRow("ada_pots", List.of(job.range().startInclusive(), 1L, 2L, 3L,
                    4L, 5L, 6L, 7L, 8L, job.anchorBlockHash(), job.range().startInclusive() * 10,
                    job.anchorSlot(), 1_700_000_000L, job.sourceStateVersion(), job.jobId())));
            write.commit();
        }
    }

    private com.bloxbean.cardano.yano.archive.api.ArchiveReceipt commit(ArchiveJob job) {
        try (var write = backend.begin(job)) {
            write.append(row(job));
            return write.commit();
        }
    }

    private static ArchiveJob job(long from, long to) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) (to + 1));
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.TRANSACTION,
                ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).projectionVersion(), new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "fixture-v1");
    }

    private static ArchiveRow row(ArchiveJob job) {
        return new ArchiveRow("chain_transaction", List.of(
                job.anchorBlockHash(), job.anchorBlockHash(), job.range().endInclusive(), job.anchorSlot(),
                0L, 0L, 0, true, 0L, job.jobId()));
    }

    private static ArchiveRow transactionRow(ArchiveJob job, byte[] txHash, long blockNumber, int txIndex) {
        return new ArchiveRow("chain_transaction", List.of(
                txHash, job.anchorBlockHash(), blockNumber, blockNumber * 10,
                0L, 0L, txIndex, true, 0L, job.jobId()));
    }
}
