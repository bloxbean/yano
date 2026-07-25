package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.Objects;

/** Bounded, storage-neutral result returned by a host-owned local read model. */
public record LocalReadModelResult(
        Status status,
        byte[] payload,
        long indexedHeight,
        long finalizedHeight,
        String coverage,
        String diagnostic
) {
    public static final int MAX_PAYLOAD_BYTES = DomainApiResponse.MAX_BODY_BYTES;

    public enum Status {
        READY,
        CATCHING_UP,
        REBUILDING,
        UNAVAILABLE,
        FAILED
    }

    public LocalReadModelResult {
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(payload, "payload");
        coverage = bounded(coverage, 32, "coverage");
        diagnostic = bounded(
                Objects.requireNonNullElse(diagnostic, ""), 512, "diagnostic");
        if (payload.length > MAX_PAYLOAD_BYTES
                || indexedHeight < 0 || finalizedHeight < 0) {
            throw new IllegalArgumentException("invalid local read-model result");
        }
        payload = payload.clone();
    }

    public static LocalReadModelResult unavailable() {
        return new LocalReadModelResult(
                Status.UNAVAILABLE, new byte[0], 0, 0, "NONE", "");
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    private static String bounded(String value, int maximum, String field) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return value;
    }
}
