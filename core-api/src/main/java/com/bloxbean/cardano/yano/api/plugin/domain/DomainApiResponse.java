package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.Objects;

/** Immutable response returned to the host-owned domain API adapter. */
public record DomainApiResponse(int status, DomainApiMediaType mediaType, byte[] body) {
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    public DomainApiResponse {
        if (status == 401 || status == 403 || status == 407 || status == 429) {
            throw new IllegalArgumentException(
                    "status is reserved for host-owned authentication, authorization, or admission");
        }
        if (!isAllowedStatus(status)) {
            throw new IllegalArgumentException(
                    "status is not in the domain API allow-list; the host owns 5xx and all other responses");
        }
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) {
            throw new DomainApiException(
                    DomainApiException.Code.RESULT_TOO_LARGE,
                    "Domain API response exceeds the host size limit");
        }
        body = body.clone();
    }

    private static boolean isAllowedStatus(int status) {
        return status == 200
                || status == 400
                || status == 404
                || status == 409
                || status == 410
                || status == 422;
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Redacts response bytes. */
    @Override
    public String toString() {
        return "DomainApiResponse[status=" + status
                + ", mediaType=" + mediaType
                + ", bodyBytes=" + body.length + "]";
    }
}
