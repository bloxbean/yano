package com.bloxbean.cardano.yano.api.plugin.domain;

/** Response media types accepted from a v1 domain API handler. */
public enum DomainApiMediaType {
    JSON("application/json"),
    OCTET_STREAM("application/octet-stream");

    private final String value;

    DomainApiMediaType(String value) {
        this.value = value;
    }

    /** Canonical HTTP media type owned by the host response adapter. */
    public String value() {
        return value;
    }
}
