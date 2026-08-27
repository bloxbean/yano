package com.bloxbean.cardano.yano.archive.api;

public interface RewardHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.REWARD; }
}
