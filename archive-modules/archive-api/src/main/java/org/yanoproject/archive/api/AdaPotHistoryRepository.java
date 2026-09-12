package org.yanoproject.archive.api;

public interface AdaPotHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.ADA_POT; }
}
