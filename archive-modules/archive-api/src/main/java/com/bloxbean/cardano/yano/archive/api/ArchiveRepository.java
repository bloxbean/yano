package com.bloxbean.cardano.yano.archive.api;

/** A repository is scoped to an explicit request read session. */
public interface ArchiveRepository<T> {
    ArchiveDatasetId dataset();

    ArchiveQueryResult<T> query(ArchiveReadSession session, ArchiveQuery query);
}
