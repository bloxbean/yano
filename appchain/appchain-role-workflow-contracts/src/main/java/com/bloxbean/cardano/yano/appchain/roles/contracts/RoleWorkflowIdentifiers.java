package com.bloxbean.cardano.yano.appchain.roles.contracts;

import java.nio.charset.StandardCharsets;

/** Canonical v1 identifiers shared by clients and deterministic components. */
public final class RoleWorkflowIdentifiers {
    private RoleWorkflowIdentifiers() {
    }

    public static String id(String value, String field) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,62}")
                || value.getBytes(StandardCharsets.US_ASCII).length
                > RoleWorkflowLimits.MAX_IDENTIFIER_BYTES) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.INVALID_PAYLOAD);
        }
        return value;
    }

    public static String role(String value) {
        return id(value, "role");
    }

    public static String payloadDomain(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9.-]{0,63}")
                || value.getBytes(StandardCharsets.US_ASCII).length
                > RoleWorkflowLimits.MAX_PAYLOAD_DOMAIN_BYTES) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.INVALID_PAYLOAD);
        }
        return value;
    }
}
