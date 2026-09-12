package org.yanoproject.archive.api;

public interface EpochStakeHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.EPOCH_STAKE; }
}
