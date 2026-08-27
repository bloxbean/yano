package com.bloxbean.cardano.yano.archive.api;

/** Backend failure isolated from core synchronization. */
public class ArchiveStoreException extends RuntimeException {
    public ArchiveStoreException(String message) {
        super(message);
    }

    public ArchiveStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
