package com.bloxbean.cardano.yano.api.genesis;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record GenesisPool(String poolHash,
                          String vrfKeyHash,
                          BigInteger pledge,
                          BigInteger cost,
                          BigInteger marginNumerator,
                          BigInteger marginDenominator,
                          String rewardAccount,
                          Set<String> owners,
                          List<GenesisRelay> relays,
                          String metadataUrl,
                          String metadataHash) {
    public GenesisPool {
        poolHash = normalizeHex(poolHash);
        vrfKeyHash = normalizeHex(vrfKeyHash);
        pledge = pledge != null ? pledge : BigInteger.ZERO;
        cost = cost != null ? cost : BigInteger.ZERO;
        marginNumerator = marginNumerator != null ? marginNumerator : BigInteger.ZERO;
        marginDenominator = marginDenominator != null && marginDenominator.signum() > 0
                ? marginDenominator : BigInteger.ONE;
        rewardAccount = normalizeHex(rewardAccount);
        owners = owners != null ? Set.copyOf(owners) : Set.of();
        relays = relays != null ? List.copyOf(relays) : List.of();
        metadataUrl = blankToNull(metadataUrl);
        metadataHash = normalizeHex(metadataHash);
    }

    private static String normalizeHex(String value) {
        if (value == null || value.isBlank()) return null;
        return value.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
