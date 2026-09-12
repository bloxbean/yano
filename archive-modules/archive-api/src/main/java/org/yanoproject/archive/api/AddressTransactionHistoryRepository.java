package org.yanoproject.archive.api;

public interface AddressTransactionHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }
}
