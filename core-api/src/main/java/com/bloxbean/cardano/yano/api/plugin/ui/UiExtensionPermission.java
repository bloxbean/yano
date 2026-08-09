package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.Arrays;

/** Closed, read-only permissions available to a sandboxed plugin UI. */
public enum UiExtensionPermission {
    APP_CHAIN_STATUS_READ("app-chain.status.read"),
    APP_CHAIN_DOMAIN_READ("app-chain.domain.read"),
    APP_CHAIN_QUERY_READ("app-chain.query.read"),
    APP_CHAIN_PROOF_READ("app-chain.proof.read"),
    APP_CHAIN_ANCHOR_READ("app-chain.anchor.read"),
    FILE_IMPORT("file.import"),
    FILE_EXPORT("file.export");

    private final String id;

    UiExtensionPermission(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static UiExtensionPermission fromId(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported UI permission"));
    }
}
