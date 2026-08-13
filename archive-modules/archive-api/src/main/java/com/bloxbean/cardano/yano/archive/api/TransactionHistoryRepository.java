package com.bloxbean.cardano.yano.archive.api;

public interface TransactionHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
}
