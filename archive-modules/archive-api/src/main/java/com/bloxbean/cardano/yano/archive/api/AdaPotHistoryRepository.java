package com.bloxbean.cardano.yano.archive.api;

public interface AdaPotHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.ADA_POT; }
}
