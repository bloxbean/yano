package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;

import java.util.List;

public final class StandardBlockDatasets {
    private StandardBlockDatasets() { }

    public static BlockArchiveDataset<ArchiveBlockFacts> transactions() {
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
            public int projectionVersion() { return 1; }
            public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (TransactionFact tx : block.block().transactions()) {
                    sink.accept(new ArchiveRow("chain_transaction", List.of(tx.txHash(), block.blockHash(),
                            block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(),
                            tx.txIndex(), tx.valid(), tx.fee(), job.jobId())));
                }
            }
        };
    }

    public static BlockArchiveDataset<ArchiveBlockFacts> accountEvents() {
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.ACCOUNT_EVENT; }
            public int projectionVersion() { return 1; }
            public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (AccountEventFact event : block.block().accountEvents()) {
                    sink.accept(new ArchiveRow("account_events", java.util.Arrays.asList(event.stakeCredential(),
                            event.credentialType(), event.eventType(), event.txHash(), block.blockHash(),
                            block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(),
                            event.txIndex(), event.eventIndex(), event.poolHash(), event.drepCredential(),
                            event.amount(), job.jobId())));
                }
            }
        };
    }
}
