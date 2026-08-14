package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.source.EpochSourcePage;

import java.util.List;
import java.util.function.Consumer;

public final class StandardEpochDatasets {
    private StandardEpochDatasets() { }

    public static EpochArchiveDataset<EpochStakeFact> epochStake() {
        return dataset(ArchiveDatasetId.EPOCH_STAKE, (job, page, fact) -> new ArchiveRow("epoch_stakes", List.of(
                page.job().epoch(), fact.credentialType(), fact.stakeCredential(), fact.poolHash(), fact.amount(),
                page.job().boundaryBlockHash(), page.job().boundaryBlockNumber(), page.job().boundarySlot(),
                page.job().boundaryBlockTime(), page.job().sourceStateVersion(), job.jobId())));
    }

    public static EpochArchiveDataset<DrepDistributionFact> drepDistribution() {
        return dataset(ArchiveDatasetId.DREP_DISTRIBUTION, (job, page, fact) -> new ArchiveRow("drep_distributions",
                java.util.Arrays.asList(page.job().epoch(), fact.drepType(), fact.credential(), fact.amount(),
                        fact.storedExpiry(), fact.dormantEpochs(), fact.effectiveExpiry(), fact.active(),
                        page.job().boundaryBlockHash(), page.job().boundaryBlockNumber(), page.job().boundarySlot(),
                        page.job().boundaryBlockTime(), page.job().sourceStateVersion(), job.jobId())));
    }

    public static EpochArchiveDataset<AdaPotFact> adaPot() {
        return dataset(ArchiveDatasetId.ADA_POT, (job, page, fact) -> new ArchiveRow("ada_pots", List.of(
                page.job().epoch(), fact.treasury(), fact.reserves(), fact.deposits(), fact.fees(),
                fact.distributed(), fact.undistributed(), fact.rewardsPot(), fact.poolRewardsPot(),
                page.job().boundaryBlockHash(), page.job().boundaryBlockNumber(), page.job().boundarySlot(),
                page.job().boundaryBlockTime(), page.job().sourceStateVersion(), job.jobId())));
    }

    public static EpochArchiveDataset<GovernanceProposalStatusFact> governanceProposalStatus() {
        return dataset(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS, (job, page, fact) ->
                new ArchiveRow("governance_proposal_statuses", java.util.Arrays.asList(page.job().epoch(),
                        fact.txHash(), fact.governanceActionIndex(), fact.actionType(), fact.observationPhase(),
                        fact.statusCode(), fact.decisionReason(), fact.deposit(), fact.returnAddress(),
                        fact.submittedEpoch(), fact.expiresAfterEpoch(), page.job().boundaryBlockHash(),
                        page.job().boundaryBlockNumber(), page.job().boundarySlot(), page.job().boundaryBlockTime(),
                        page.job().sourceStateVersion(), job.jobId())));
    }

    public static EpochArchiveDataset<RewardFact> rewards() {
        return dataset(ArchiveDatasetId.REWARD, (job, page, fact) -> new ArchiveRow("rewards",
                java.util.Arrays.asList(fact.stakeCredential(), fact.credentialType(), fact.poolHash(),
                        fact.rewardType(), fact.earnedEpoch(), fact.spendableEpoch(), fact.amount(), fact.sourceId(),
                        page.job().boundaryBlockHash(), page.job().boundaryBlockNumber(), page.job().boundarySlot(),
                        page.job().boundaryBlockTime(), job.jobId())));
    }

    private static <T> EpochArchiveDataset<T> dataset(ArchiveDatasetId id, RowFactory<T> rows) {
        return new EpochArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return id; }
            public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }
            public void derive(ArchiveJob job, EpochSourcePage<T> page, Consumer<ArchiveRow> sink) {
                page.rows().forEach(fact -> sink.accept(rows.row(job, page, fact)));
            }
        };
    }

    @FunctionalInterface private interface RowFactory<T> {
        ArchiveRow row(ArchiveJob job, EpochSourcePage<T> page, T fact);
    }
}
