package com.bloxbean.cardano.yano.api.genesis;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Shelley genesis data that can affect ledger state maintenance.
 */
public record ShelleyGenesisBootstrap(Map<String, BigInteger> initialFunds,
                                      BigInteger maxLovelaceSupply,
                                      BigInteger keyDeposit,
                                      BigInteger poolDeposit,
                                      List<GenesisPool> pools,
                                      List<GenesisDelegation> delegations) {
    public ShelleyGenesisBootstrap {
        initialFunds = initialFunds != null ? Map.copyOf(initialFunds) : Map.of();
        maxLovelaceSupply = maxLovelaceSupply != null ? maxLovelaceSupply : BigInteger.ZERO;
        keyDeposit = keyDeposit != null ? keyDeposit : BigInteger.ZERO;
        poolDeposit = poolDeposit != null ? poolDeposit : BigInteger.ZERO;
        pools = pools != null ? List.copyOf(pools) : List.of();
        delegations = delegations != null ? List.copyOf(delegations) : List.of();
    }

    public static ShelleyGenesisBootstrap empty() {
        return new ShelleyGenesisBootstrap(Map.of(), BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, List.of(), List.of());
    }

    public boolean hasStaking() {
        return !pools.isEmpty() || !delegations.isEmpty();
    }

    public BigInteger initialFundsTotal() {
        return initialFunds.values().stream()
                .filter(v -> v != null && v.signum() > 0)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }
}
