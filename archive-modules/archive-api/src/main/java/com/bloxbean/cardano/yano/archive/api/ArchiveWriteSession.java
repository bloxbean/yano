package com.bloxbean.cardano.yano.archive.api;

/** One backend transaction. Closing without commit must abort all writes. */
public interface ArchiveWriteSession extends AutoCloseable {
    void append(ArchiveRow row);

    ArchiveReceipt commit();

    @Override
    void close();
}
