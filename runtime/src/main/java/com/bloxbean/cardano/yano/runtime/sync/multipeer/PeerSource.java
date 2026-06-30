package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.Locale;

public enum PeerSource {
    STATIC_UPSTREAM,
    LOCAL_ROOT,
    PUBLIC_ROOT,
    BOOTSTRAP,
    GOSSIP,
    LEDGER,
    INBOUND;

    public static PeerSource from(String value) {
        if (value == null || value.isBlank()) {
            return GOSSIP;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "static-upstream", "configured", "legacy-remote" -> STATIC_UPSTREAM;
            case "local-root", "localroot" -> LOCAL_ROOT;
            case "public-root", "publicroot" -> PUBLIC_ROOT;
            case "bootstrap", "peer-snapshot", "snapshot" -> BOOTSTRAP;
            case "ledger", "ledger-peer" -> LEDGER;
            case "inbound" -> INBOUND;
            case "peer-sharing", "gossip", "discovered" -> GOSSIP;
            default -> GOSSIP;
        };
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
