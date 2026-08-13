package com.bloxbean.cardano.yano.archive.api.test;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared black-box contract that every archive engine must execute. */
public abstract class AbstractArchiveBackendConformanceTest {
    private ArchiveBackend backend;

    protected abstract ArchiveBackend createBackend() throws Exception;

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
                ArchiveDatasetId.TRANSACTION, 1, new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "fixture-v1");
    }

    private static ArchiveRow row(ArchiveJob job) {
        return new ArchiveRow("chain_transaction", List.of(
                new byte[32], job.anchorBlockHash(), job.range().endInclusive(), job.anchorSlot(),
                0L, 0L, 0, true, 0L, job.jobId()));
    }
}
