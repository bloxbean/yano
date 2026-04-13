package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Map;

/**
 * Enhances delegation snapshots with UTXO-derived stake amounts.
 * <p>
 * At epoch boundary, iterates all UTXOs to compute per-credential lovelace balances,
 * then merges with existing delegation snapshot to produce a full stake distribution.
 * <p>
 * This is disabled by default. When enabled, the epoch delegation snapshot value
 * changes from {0: poolHash} to {0: poolHash, 1: amount}.
 */
public class EpochStakeSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(EpochStakeSnapshotService.class);

    private final UtxoBalanceAggregator aggregator;
    private volatile boolean enabled;

    public EpochStakeSnapshotService(boolean enabled) {
        this.aggregator = new UtxoBalanceAggregator();
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Aggregate UTXO balances by stake credential.
     * Called at epoch boundary before snapshot creation.
     *
     * @param utxoState       the UTXO store
     * @param pointerResolver optional resolver for pointer addresses
     * @return map from credential key to lovelace balance, or empty map if disabled
     */
    public Map<UtxoBalanceAggregator.CredentialKey, BigInteger> aggregateStakeBalances(
            UtxoState utxoState, PointerAddressResolver pointerResolver) {
        if (!enabled) return Map.of();
        return aggregator.aggregateBalances(utxoState, pointerResolver, -1);
    }

    /**
     * Aggregate UTXO balances by stake credential, only including UTXOs created at or before maxSlot.
     * Uses a RocksDB snapshot for consistent point-in-time view.
     *
     * @param utxoState       the UTXO store
     * @param pointerResolver optional resolver for pointer addresses
     * @param maxSlot         only include UTXOs with slot ≤ maxSlot (-1 = no filter)
     * @return map from credential key to lovelace balance, or empty map if disabled
     */
    public Map<UtxoBalanceAggregator.CredentialKey, BigInteger> aggregateStakeBalances(
            UtxoState utxoState, PointerAddressResolver pointerResolver, long maxSlot) {
        if (!enabled) return Map.of();
        return aggregator.aggregateBalances(utxoState, pointerResolver, maxSlot);
    }

    /**
     * Incremental balance aggregation: start from previous epoch's snapshot balances,
     * apply UTXO deltas for the current epoch's slot range.
     * Falls back to full-scan if previous snapshot is empty or delta iteration fails.
     *
     * @param utxoState         the UTXO store (must support delta iteration)
     * @param pointerResolver   optional resolver for pointer addresses
     * @param previousSnapshot  the previous epoch's delegation snapshot (credential → deleg+amount)
     * @param epochStartSlot    inclusive start slot of the current epoch
     * @param epochEndSlot      exclusive end slot (= start of next epoch)
     * @param maxSlot           max UTXO creation slot for full-scan fallback
     * @return map from credential key to lovelace balance
     */
    public Map<UtxoBalanceAggregator.CredentialKey, BigInteger> aggregateStakeBalancesIncremental(
            UtxoState utxoState,
            PointerAddressResolver pointerResolver,
            Map<String, AccountStateCborCodec.EpochDelegSnapshot> previousSnapshot,
            long epochStartSlot, long epochEndSlot, long maxSlot) {
        if (!enabled) return Map.of();

        if (previousSnapshot == null || previousSnapshot.isEmpty()) {
            log.info("No previous snapshot available — falling back to full-scan");
            return aggregator.aggregateBalances(utxoState, pointerResolver, maxSlot);
        }

        long start = System.currentTimeMillis();

        // 1. Seed balances from previous snapshot
        Map<UtxoBalanceAggregator.CredentialKey, BigInteger> balances = new java.util.HashMap<>();
        for (var entry : previousSnapshot.entrySet()) {
            String credKey = entry.getKey();
            BigInteger amount = entry.getValue().amount();
            if (amount == null || amount.signum() <= 0) continue;

            String[] parts = credKey.split(":", 2);
            if (parts.length == 2) {
                balances.put(new UtxoBalanceAggregator.CredentialKey(
                        Integer.parseInt(parts[0]), parts[1]), amount);
            }
        }
        int seedSize = balances.size();

        // 2. Apply UTXO deltas for the epoch
        long[] created = {0};
        long[] spent = {0};
        long[] skipped = {0};

        utxoState.forEachUtxoDeltaInSlotRange(epochStartSlot, epochEndSlot, (address, lovelace, isCreated) -> {
            try {
                var credKey = aggregator.extractCredential(address, pointerResolver);
                if (credKey == null) { skipped[0]++; return; }

                if (isCreated) {
                    balances.merge(credKey, lovelace, BigInteger::add);
                    created[0]++;
                } else {
                    balances.merge(credKey, lovelace.negate(), BigInteger::add);
                    spent[0]++;
                }
            } catch (Exception e) {
                skipped[0]++;
            }
        });

        // 3. Remove zero/negative balance entries
        balances.values().removeIf(v -> v.signum() <= 0);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Incremental balance aggregation complete: seed={} credentials, +{} created, -{} spent, " +
                        "{} skipped, result={} credentials, {}ms",
                seedSize, created[0], spent[0], skipped[0], balances.size(), elapsed);

        return balances;
    }
}
