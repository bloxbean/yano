package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochSourceContractTest {
    @Test
    void durableJobCopiesBoundaryHashAndRequiresEpochDataset() {
        byte[] hash = {1, 2, 3};
        var identity = new ArchiveNetworkIdentity(1, "genesis");
        var job = new EpochArchiveJob(UUID.randomUUID(), identity, ArchiveDatasetId.EPOCH_STAKE, 1,
                12, 500, hash, "state-v1", "epoch-stake/12", Instant.EPOCH);
        hash[0] = 9;
        assertThat(job.boundaryBlockHash()).containsExactly(1, 2, 3);

        assertThatThrownBy(() -> new EpochArchiveJob(UUID.randomUUID(), identity, ArchiveDatasetId.TRANSACTION,
                1, 12, 500, hash, "state-v1", "invalid", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch dataset");
    }
}
