package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable identity that prevents one derived index being opened for another chain. */
public record IndexIdentity(
        String network,
        String chainId,
        String stateMachineId,
        String ledgerProfileDigest,
        String stateGenesisId,
        int bridgeAbi,
        String validityProfileDigest
) {
    public IndexIdentity {
        network = bounded(network, "network", 64);
        chainId = bounded(chainId, "chainId", 120);
        stateMachineId = bounded(stateMachineId, "stateMachineId", 120);
        ledgerProfileDigest = digest(ledgerProfileDigest, "ledgerProfileDigest");
        stateGenesisId = digest(stateGenesisId, "stateGenesisId");
        if (bridgeAbi < 0) {
            throw new IllegalArgumentException("bridgeAbi cannot be negative");
        }
        validityProfileDigest = Objects.requireNonNullElse(
                validityProfileDigest, "").trim();
        if (!validityProfileDigest.isEmpty()) {
            validityProfileDigest = digest(
                    validityProfileDigest, "validityProfileDigest");
        }
    }

    public String digest() {
        String canonical = network + "\n" + chainId + "\n" + stateMachineId
                + "\n" + ledgerProfileDigest + "\n" + stateGenesisId
                + "\n" + bridgeAbi + "\n"
                + validityProfileDigest;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }

    private static String digest(String value, String field) {
        String normalized = bounded(value, field, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return normalized;
    }
}
