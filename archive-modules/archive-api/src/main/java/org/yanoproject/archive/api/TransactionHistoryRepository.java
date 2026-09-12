package org.yanoproject.archive.api;

public interface TransactionHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
}
