package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;

import java.util.List;

public final class StandardBlockDatasets {
    private StandardBlockDatasets() { }

    public static BlockArchiveDataset<ArchiveBlockFacts> transactions() {
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
            public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }
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
            public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }
            public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (AccountEventFact event : block.block().accountEvents()) {
                    sink.accept(new ArchiveRow("account_events", java.util.Arrays.asList(event.stakeCredential(),
                            event.credentialType(), event.eventType(), event.txHash(), block.blockHash(),
                            block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(),
                            event.txIndex(), event.eventIndex(), event.poolHash(), event.drepType(), event.drepCredential(),
                            event.amount(), job.jobId())));
                }
            }
        };
    }

    public static BlockArchiveDataset<ArchiveBlockFacts> addressTransactions() {
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }
            public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }
            public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (AddressTransactionFact tx : block.block().addressTransactions()) {
                    for (AddressSubject subject : tx.subjects()) {
                        sink.accept(new ArchiveRow("address_transactions", List.of(subject.subjectType(),
                                subject.subjectKey(), tx.txHash(), block.blockHash(), block.blockNumber(),
                                block.slot(), block.epoch(), block.blockTime().getEpochSecond(), tx.txIndex(),
                                tx.inputCount(), tx.outputCount(), tx.collateralInputCount(),
                                tx.collateralReturnCount(), job.jobId())));
                    }
                }
            }
        };
    }
}
