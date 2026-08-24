package com.bloxbean.cardano.yano.archive.api;

public interface AddressTransactionHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }
}
