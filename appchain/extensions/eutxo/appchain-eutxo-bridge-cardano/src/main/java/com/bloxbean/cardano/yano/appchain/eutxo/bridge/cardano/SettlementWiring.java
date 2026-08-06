package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-UTXO-009 SP-M6: parsed {@code effects.executors.eutxo-settlement.*}
 * configuration for the wired settlement stack. The SAME config block is
 * present on every member (chain config is shared); {@code owner=true} on
 * exactly ONE node designates the settlement executor / co-sign leader
 * (single-owner pinning) — every other node only answers co-sign requests.
 */
record SettlementWiring(
        boolean owner,
        String vaultAddress,
        String shardAddress,
        String rootAddress,
        String rootUnit,
        String shardThreadPolicyIdHex,
        String operatorAddress,
        byte[] operatorSecretSeed,
        String vaultScriptHex,
        String shardScriptHex,
        long ttlSlots,
        Duration roundTimeout
) {
    SettlementWiring {
        vaultAddress = required(vaultAddress, "vault-address");
        shardAddress = required(shardAddress, "shard-address");
        rootAddress = required(rootAddress, "root-address");
        rootUnit = requiredHex(rootUnit, "root-unit", -1);
        shardThreadPolicyIdHex =
                requiredHex(shardThreadPolicyIdHex, "shard-thread-policy-id", 28);
        operatorAddress = required(operatorAddress, "operator-address");
        operatorSecretSeed = Objects.requireNonNull(
                operatorSecretSeed, "operator seed").clone();
        if (operatorSecretSeed.length != 32) {
            throw new IllegalArgumentException(
                    "operator-seed must be a 32-byte Ed25519 seed (hex)");
        }
        vaultScriptHex = requiredHex(vaultScriptHex, "vault-script", -1);
        shardScriptHex = requiredHex(shardScriptHex, "shard-script", -1);
        if (ttlSlots <= 0) {
            throw new IllegalArgumentException("ttl-slots must be positive");
        }
        Objects.requireNonNull(roundTimeout, "roundTimeout");
    }

    /**
     * Parse the scheme sub-map. Required keys: {@code vault-address},
     * {@code shard-address}, {@code root-address}, {@code root-unit}
     * (policy+name hex), {@code shard-thread-policy-id},
     * {@code operator-address}, {@code operator-seed} (32-byte hex; funds
     * fees/collateral). Optional: {@code owner} (default false),
     * {@code ttl-slots} (default 7200), {@code round-timeout-ms}
     * (default 30000). {@code vault-script}/{@code shard-script} carry the
     * PARAMETERIZED validators (double-CBOR hex from the bootstrap plan) the
     * settle transaction attaches.
     */
    static SettlementWiring parse(Map<String, String> config) {
        return new SettlementWiring(
                Boolean.parseBoolean(config.getOrDefault("owner", "false")),
                config.get("vault-address"),
                config.get("shard-address"),
                config.get("root-address"),
                config.get("root-unit"),
                config.get("shard-thread-policy-id"),
                config.get("operator-address"),
                parseHex(config.get("operator-seed"), "operator-seed"),
                config.get("vault-script"),
                config.get("shard-script"),
                Long.parseLong(config.getOrDefault("ttl-slots", "7200")),
                Duration.ofMillis(Long.parseLong(
                        config.getOrDefault("round-timeout-ms", "30000"))));
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "effects.executors.eutxo-settlement." + key + " is required");
        }
        return value.trim();
    }

    private static String requiredHex(String value, String key, int bytes) {
        String normalized = required(value, key)
                .toLowerCase(java.util.Locale.ROOT);
        byte[] decoded = parseHex(normalized, key);
        if (bytes > 0 && decoded.length != bytes) {
            throw new IllegalArgumentException(
                    "effects.executors.eutxo-settlement." + key
                            + " must be " + bytes + " bytes");
        }
        return normalized;
    }

    private static byte[] parseHex(String value, String key) {
        try {
            return HexFormat.of().parseHex(required(value, key));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "effects.executors.eutxo-settlement." + key
                            + " must be canonical hex", failure);
        }
    }

    @Override
    public byte[] operatorSecretSeed() {
        return operatorSecretSeed.clone();
    }
}
