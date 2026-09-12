package org.yanoproject.archive.api;

public interface RewardHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.REWARD; }
}
