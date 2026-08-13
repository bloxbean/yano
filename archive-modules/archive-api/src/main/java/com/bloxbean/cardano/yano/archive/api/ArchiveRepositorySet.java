package com.bloxbean.cardano.yano.archive.api;

/** Typed repository registry; unsupported datasets are absent rather than emulated. */
public interface ArchiveRepositorySet {
    <T> ArchiveRepository<T> repository(ArchiveDatasetId dataset, Class<T> rowType);
}
