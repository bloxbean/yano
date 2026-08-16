package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.address.StakeAddressCodec;

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
                    sink.accept(new ArchiveRow("chain_transaction", java.util.Arrays.asList(tx.txHash(), block.blockHash(),
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
                            event.credentialType(), StakeAddressCodec.encode(job.networkIdentity().networkMagic(),
                                    event.credentialType(), event.stakeCredential()),
                            event.eventType(), event.txHash(), block.blockHash(),
                            block.blockNumber(), block.slot(), block.epoch(), block.blockTime().getEpochSecond(),
                            event.txIndex(), event.eventIndex(), event.poolHash(), event.drepType(), event.drepCredential(),
                            event.amount(), job.jobId())));
                }
            }
        };
    }

    public static BlockArchiveDataset<ArchiveBlockFacts> addressTransactions() {
        return addressTransactions(AddressTransactionSubjects.all());
    }

    public static BlockArchiveDataset<ArchiveBlockFacts> addressTransactions(
            AddressTransactionSubjects selectedSubjects) {
        java.util.Objects.requireNonNull(selectedSubjects, "selectedSubjects");
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }
            public int projectionVersion() { return ArchiveSchemas.schema(dataset()).projectionVersion(); }
            public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (AddressTransactionFact tx : block.block().addressTransactions()) {
                    for (AddressSubject subject : tx.subjects()) {
                        if (!selectedSubjects.includes(subject.subjectType())) continue;
                        sink.accept(new ArchiveRow("address_transactions", java.util.Arrays.asList(subject.subjectType(),
                                subject.subjectKey(), null, null, tx.txHash(), block.blockHash(), block.blockNumber(),
                                block.slot(), block.epoch(), block.blockTime().getEpochSecond(), tx.txIndex(),
                                tx.inputCount(), tx.outputCount(), tx.collateralInputCount(),
                                tx.collateralReturnCount(), job.jobId())));
                    }
                }
            }
        };
    }
}
