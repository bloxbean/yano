package com.bloxbean.cardano.yano.archive.api;

/** A complete archive batch is valid but too large for the backend's configured resource budget. */
public final class ArchiveBatchCapacityException extends ArchiveStoreException {
    public ArchiveBatchCapacityException(String message, Throwable cause) {
        super(message, cause);
    }
}
