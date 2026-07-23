package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import java.util.Map;
import java.util.Objects;

record EutxoBridgeConfig(
        boolean enabled,
        String chainId,
        String observerId,
        String vaultAddress,
        String vaultScriptHash
) {
    static EutxoBridgeConfig disabled() {
        return new EutxoBridgeConfig(false, "", "", "", "");
    }

    static EutxoBridgeConfig from(String chainId, Map<String, String> settings) {
        String observerId = settings.get("machines.eutxo.bridge.observer-id");
        if (observerId == null || observerId.isBlank()) {
            return disabled();
        }
        return new EutxoBridgeConfig(
                true,
                required(chainId, "chain id"),
                required(observerId, "bridge observer id"),
                required(settings.get("machines.eutxo.bridge.vault-address"),
                        "bridge vault address"),
                required(settings.get("machines.eutxo.bridge.vault-script-hash"),
                        "bridge vault script hash"));
    }

    EutxoBridgeConfig {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(observerId, "observerId");
        Objects.requireNonNull(vaultAddress, "vaultAddress");
        Objects.requireNonNull(vaultScriptHash, "vaultScriptHash");
    }

    String topic() {
        return com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation.TOPIC_PREFIX
                + observerId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
