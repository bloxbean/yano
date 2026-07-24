package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable secret-free CLI result. */
public record EutxoDemoResult(
        String status,
        Map<String, Object> fields
) {
    public EutxoDemoResult {
        if (status == null || !status.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid result status");
        }
        fields = fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
    }

    public static EutxoDemoResult of(String status, Map<String, Object> fields) {
        return new EutxoDemoResult(status, fields);
    }
}
