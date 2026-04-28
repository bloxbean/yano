package com.bloxbean.cardano.yano.runtime.genesis;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data extracted from a byron-genesis.json file.
 *
 * @param nonAvvmBalances Byron base58 address → lovelace (from nonAvvmBalances section)
 * @param avvmBalances    Byron base58 address → lovelace (converted from avvmDistr section)
 * @param startTime       genesis start time (Unix epoch seconds)
 * @param protocolMagic   protocol magic number
 * @param slotDuration    slot duration in seconds (from blockVersionData.slotDuration ms / 1000)
 * @param k               security parameter from protocolConsts.k (epoch length = k * 10)
 */
public record ByronGenesisData(
        Map<String, BigInteger> nonAvvmBalances,
        Map<String, BigInteger> avvmBalances,
        long startTime,
        long protocolMagic,
        long slotDuration,
        long k
) {
    /**
     * Byron epoch length = k * 10 slots
     */
    public long epochLength() {
        return k * 10;
    }

    /**
     * Combined Byron genesis balances (non-AVVM + AVVM).
     * If the same address appears in both (unlikely but possible), amounts are summed.
     */
    public Map<String, BigInteger> getAllByronBalances() {
        if (avvmBalances.isEmpty()) return nonAvvmBalances;
        if (nonAvvmBalances.isEmpty()) return avvmBalances;

        Map<String, BigInteger> combined = new LinkedHashMap<>(nonAvvmBalances);
        avvmBalances.forEach((addr, amt) ->
                combined.merge(addr, amt, BigInteger::add));
        return Collections.unmodifiableMap(combined);
    }
}
