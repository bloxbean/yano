package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

record EutxoBridgeConfig(
        boolean enabled,
        String chainId,
        String observerId,
        String vaultAddress,
        String vaultScriptHash,
        boolean withdrawalsEnabled,
        String confirmationObserverId,
        String withdrawalAddress,
        long bridgeEpoch,
        BigInteger maximumWithdrawalLovelace,
        int maximumPendingWithdrawals,
        boolean withdrawalsPaused
) {
    static EutxoBridgeConfig disabled() {
        return new EutxoBridgeConfig(
                false, "", "", "", "", false, "", "", 0,
                BigInteger.ONE, 1, true);
    }

    static EutxoBridgeConfig from(String chainId, Map<String, String> settings) {
        String observerId = settings.get("machines.eutxo.bridge.observer-id");
        if (observerId == null || observerId.isBlank()) {
            return disabled();
        }
        String withdrawalAddress = settings.get(
                "machines.eutxo.bridge.withdrawal-address");
        boolean withdrawalsEnabled =
                withdrawalAddress != null && !withdrawalAddress.isBlank();
        return new EutxoBridgeConfig(
                true,
                required(chainId, "chain id"),
                required(observerId, "bridge observer id"),
                required(settings.get("machines.eutxo.bridge.vault-address"),
                        "bridge vault address"),
                required(settings.get("machines.eutxo.bridge.vault-script-hash"),
                        "bridge vault script hash"),
                withdrawalsEnabled,
                withdrawalsEnabled
                        ? required(settings.get(
                        "machines.eutxo.bridge.confirmation-observer-id"),
                        "withdrawal confirmation observer id") : "",
                withdrawalsEnabled ? required(withdrawalAddress, "withdrawal address") : "",
                withdrawalsEnabled ? nonNegativeLong(settings.getOrDefault(
                        "machines.eutxo.bridge.epoch", "0"), "bridge epoch") : 0,
                withdrawalsEnabled ? positive(settings.getOrDefault(
                        "machines.eutxo.bridge.max-withdrawal-lovelace",
                        "45000000000000000"), "maximum withdrawal lovelace") : BigInteger.ONE,
                withdrawalsEnabled ? positiveInt(settings.getOrDefault(
                        "machines.eutxo.bridge.max-pending-withdrawals", "1024"),
                        "maximum pending withdrawals") : 1,
                !withdrawalsEnabled || booleanValue(settings.getOrDefault(
                        "machines.eutxo.bridge.withdrawals-paused", "false"),
                        "withdrawals paused"));
    }

    EutxoBridgeConfig {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(observerId, "observerId");
        Objects.requireNonNull(vaultAddress, "vaultAddress");
        Objects.requireNonNull(vaultScriptHash, "vaultScriptHash");
        Objects.requireNonNull(confirmationObserverId, "confirmationObserverId");
        Objects.requireNonNull(withdrawalAddress, "withdrawalAddress");
        Objects.requireNonNull(maximumWithdrawalLovelace, "maximumWithdrawalLovelace");
    }

    String topic() {
        return com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation.TOPIC_PREFIX
                + observerId;
    }

    String confirmationTopic() {
        return com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation.TOPIC_PREFIX
                + confirmationObserverId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static BigInteger positive(String value, String field) {
        try {
            BigInteger parsed = new BigInteger(required(value, field));
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static int positiveInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(required(value, field));
            if (parsed <= 0 || parsed > 100_000) {
                throw new IllegalArgumentException(field + " must be in 1-100000");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static long nonNegativeLong(String value, String field) {
        try {
            long parsed = Long.parseLong(required(value, field));
            if (parsed < 0) {
                throw new IllegalArgumentException(field + " cannot be negative");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static boolean booleanValue(String value, String field) {
        String normalized = required(value, field).toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }
}
