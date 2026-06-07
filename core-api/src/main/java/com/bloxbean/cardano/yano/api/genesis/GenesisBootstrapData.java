package com.bloxbean.cardano.yano.api.genesis;

import java.util.Locale;

/**
 * Optional genesis-derived data attached to {@code GenesisBlockEvent}.
 */
public record GenesisBootstrapData(String shelleyGenesisHashHex,
                                   ShelleyGenesisBootstrap shelley) {
    public GenesisBootstrapData {
        shelleyGenesisHashHex = normalizeHex(shelleyGenesisHashHex);
        shelley = shelley != null ? shelley : ShelleyGenesisBootstrap.empty();
    }

    public static GenesisBootstrapData empty() {
        return new GenesisBootstrapData(null, ShelleyGenesisBootstrap.empty());
    }

    public boolean isEmpty() {
        return shelleyGenesisHashHex == null && !hasShelleyStaking();
    }

    public boolean hasShelleyStaking() {
        return shelley != null && shelley.hasStaking();
    }

    private static String normalizeHex(String value) {
        if (value == null || value.isBlank()) return null;
        return value.toLowerCase(Locale.ROOT);
    }
}
