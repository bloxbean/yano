package com.bloxbean.cardano.yano.archive.api;

public interface EpochStakeHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.EPOCH_STAKE; }
}
