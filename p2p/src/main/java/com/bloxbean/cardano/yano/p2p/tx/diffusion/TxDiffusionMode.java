package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import java.util.Locale;

public enum TxDiffusionMode {
    DISABLED("disabled"),
    LOCAL_SUBMIT_ONLY("local-submit-only"),
    TRUSTED_HOT("trusted-hot"),
    ALL_HOT("all-hot");

    private final String configValue;

    TxDiffusionMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean enabled() {
        return this != DISABLED;
    }

    public boolean localSubmitAllowed(PeerClass peerClass) {
        if (this == DISABLED || peerClass == null) {
            return false;
        }
        return peerClass == PeerClass.ACTIVE_SELECTED || peerClass == PeerClass.TRUSTED_HOT;
    }

    public boolean networkIngressAllowed(PeerClass peerClass) {
        if (peerClass == null) {
            return false;
        }
        return switch (this) {
            case DISABLED, LOCAL_SUBMIT_ONLY -> false;
            case TRUSTED_HOT -> peerClass.trusted();
            case ALL_HOT -> true;
        };
    }

    public static TxDiffusionMode fromConfig(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "local-submit-only" -> LOCAL_SUBMIT_ONLY;
            case "trusted-hot" -> TRUSTED_HOT;
            case "all-hot" -> ALL_HOT;
            default -> DISABLED;
        };
    }
}
