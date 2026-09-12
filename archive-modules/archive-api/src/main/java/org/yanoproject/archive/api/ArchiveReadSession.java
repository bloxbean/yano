package org.yanoproject.archive.api;

/** Request-scoped, generation-consistent read lease. */
public interface ArchiveReadSession extends AutoCloseable {
    long generation();

    @Override
    void close();
}
