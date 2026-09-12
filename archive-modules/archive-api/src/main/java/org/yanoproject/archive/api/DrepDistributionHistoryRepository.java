package org.yanoproject.archive.api;

public interface DrepDistributionHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.DREP_DISTRIBUTION; }
}
