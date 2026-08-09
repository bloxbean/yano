package com.bloxbean.cardano.yano.api.plugin.ui;

/** Closed console navigation locations for plugin UI contributions. */
public enum UiExtensionMountPoint {
    APP_CHAIN("app-chain");

    private final String id;

    UiExtensionMountPoint(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
