package org.yanoproject.archive.api;

public interface AccountEventHistoryRepository<T> extends ArchiveRepository<T> {
    @Override
    default ArchiveDatasetId dataset() { return ArchiveDatasetId.ACCOUNT_EVENT; }
}
