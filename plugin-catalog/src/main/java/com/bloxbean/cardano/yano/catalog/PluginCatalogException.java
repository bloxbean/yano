package com.bloxbean.cardano.yano.catalog;

/**
 * Signals invalid plugin catalog metadata or an inability to read it safely.
 */
public class PluginCatalogException extends RuntimeException {

    /**
     * Creates an exception with a safe diagnostic message.
     *
     * @param message diagnostic message
     */
    public PluginCatalogException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a safe diagnostic message and cause.
     *
     * @param message diagnostic message
     * @param cause underlying parsing, validation, or I/O failure
     */
    public PluginCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
