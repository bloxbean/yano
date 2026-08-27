package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.source.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RewardDatasetTest {
    @Test
    void archiveOnlyRewardsUseStableSourceIdsWithoutBoundedSequenceNumbers() {
        byte[] hash = {1};
        var source = new EpochArchiveJob(UUID.randomUUID(), new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.REWARD, 1, 20, 100, 200, 1_700_000_000L,
                hash, "reward-v1", "rewards/20", Instant.EPOCH);
        ArchiveJob job = ArchiveJob.deterministic(source.networkIdentity(), ArchiveDatasetId.REWARD, 1,
                new EpochRange(20, 20), new ArchiveRangeAnchor(200, hash, 200, hash), "reward-v1");
        List<ArchiveRow> rows = new ArrayList<>();
        StandardEpochDatasets.rewards().derive(job, new EpochSourcePage<>(source, List.of(
                new RewardFact(new byte[] {2}, "key", null, "proposal_deposit_refund", 19, 21, 10,
                        "proposal:abc#0"),
                new RewardFact(new byte[] {3}, "key", null, "treasury_withdrawal", 20, 21, 20,
                        "treasury:abc#1")), 10, Optional.empty()), rows::add);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.values().get(4))
                .containsExactly("proposal_deposit_refund", "treasury_withdrawal");
        assertThat(rows).extracting(row -> row.values().get(8))
                .containsExactly("proposal:abc#0", "treasury:abc#1");
    }
}
