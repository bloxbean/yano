package org.yanoproject.api.config;

import java.util.Locale;

/** Selects runtime composition without changing the existing Cardano default. */
public enum RuntimeMode {
    CARDANO, APP_LEDGER;

    public static RuntimeMode parse(String value) {
        return switch (value == null ? "cardano" : value.trim().toLowerCase(Locale.ROOT)) {
            case "cardano" -> CARDANO;
            case "app-ledger" -> APP_LEDGER;
            default -> throw new IllegalArgumentException("yano.mode must be cardano or app-ledger");
        };
    }
}
