package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardBlockDatasetsTest {
    @Test
    void phaseTwoInvalidTransactionIsStillProjectedWithValidityFalse() {
        byte[] hash = {1};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.TRANSACTION, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, hash, 10, hash), "v1");
        var block = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH, hash, new byte[] {0},
                new ArchiveBlockFacts(List.of(new TransactionFact(new byte[] {2}, 0, false, 42)), List.of()));
        List<ArchiveRow> rows = new ArrayList<>();
        StandardBlockDatasets.transactions().derive(job, block, rows::add);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values().get(7)).isEqualTo(false);
        assertThat(rows.getFirst().values().get(9)).isEqualTo(job.jobId());
    }

    @Test
    void transactionWithUnavailableFeeIsProjectedWithNullFee() {
        byte[] hash = {1};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.TRANSACTION, 2, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, hash, 10, hash), "v2");
        var block = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH, hash, new byte[] {0},
                new ArchiveBlockFacts(List.of(new TransactionFact(new byte[] {2}, 0, false, (Long) null)),
                        List.of()));
        List<ArchiveRow> rows = new ArrayList<>();

        StandardBlockDatasets.transactions().derive(job, block, rows::add);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values().get(8)).isNull();
    }
}
