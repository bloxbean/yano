package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialId;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates per-stake-credential lovelace balances by iterating all unspent UTXOs.
 * Uses CCL {@link Address} to extract stake credential from each UTXO address.
 * <p>
 * This is Amaru's approach: full UTXO scan at epoch boundary, no secondary index.
 * ~30-60s on mainnet SSD, once per 5 days.
 */
public class UtxoBalanceAggregator {
    private static final Logger log = LoggerFactory.getLogger(UtxoBalanceAggregator.class);

    /**
     * Credential key for aggregation: "credType:credHash".
     */
    public record CredentialKey(int credType, String credHash) {}

    /**
     * Iterate all UTXOs and aggregate lovelace by stake credential.
     *
     * @param utxoState the UTXO store to iterate
     * @return map from credential key to total lovelace balance
     */
    public Map<CredentialKey, BigInteger> aggregateBalances(UtxoState utxoState) {
        return aggregateBalances(utxoState, null, -1);
    }

    /**
     * Iterate all UTXOs and aggregate lovelace by stake credential,
     * resolving pointer addresses using the provided resolver.
     *
     * @param utxoState       the UTXO store to iterate
     * @param pointerResolver optional resolver for pointer addresses (may be null)
     * @param maxSlot         only include UTXOs with slot ≤ maxSlot (-1 = no filter)
     * @return map from credential key to total lovelace balance
     */
    public Map<CredentialKey, BigInteger> aggregateBalances(UtxoState utxoState,
                                                            PointerAddressResolver pointerResolver,
                                                            long maxSlot) {
        return aggregateBalances(utxoState, pointerResolver, maxSlot, false);
    }

    /**
     * Aggregate only pre-Conway pointer-address stake. The maintained stake-balance
     * index already contains every non-pointer credential, so this scan keeps only
     * the normally tiny pointer overlay instead of rebuilding the network-sized map.
     */
    public Map<CredentialKey, BigInteger> aggregatePointerBalances(
            UtxoState utxoState, PointerAddressResolver pointerResolver, long maxSlot) {
        if (pointerResolver == null) {
            throw new IllegalStateException(
                    "Pointer resolver is required for a pre-Conway stake overlay");
        }
        return aggregateBalances(utxoState, pointerResolver, maxSlot, true);
    }

    private Map<CredentialKey, BigInteger> aggregateBalances(UtxoState utxoState,
                                                              PointerAddressResolver pointerResolver,
                                                              long maxSlot,
                                                              boolean pointerOnly) {
        Map<CredentialKey, BigInteger> balances = new HashMap<>();
        long[] count = {0};
        long[] skipped = {0};
        long[] pointerResolved = {0};
        long[] pointerFailed = {0};
        long[] byronSkipped = {0};
        long start = System.currentTimeMillis();

        // Use slot-filtered iteration for consistent epoch boundary snapshot
        java.util.function.BiConsumer<String, java.math.BigInteger> processor = (addressStr, lovelace) -> {
            count[0]++;
            if (lovelace == null || lovelace.signum() <= 0) return;

            Address address = StakeCredentialExtractor.parseAddressOrNull(addressStr);
            if (address == null) {
                skipped[0]++;
                byronSkipped[0]++;
                return;
            }

            AddressType addrType = address.getAddressType();
            if (pointerOnly && addrType != AddressType.Ptr) return;
            if (addrType == AddressType.Ptr && pointerResolver == null) {
                // Conway and later exclude pointer stake from snapshots.
                skipped[0]++;
                return;
            }

            CredentialKey credKey = extractCredential(address, addressStr, pointerResolver);
            if (credKey == null) {
                skipped[0]++;
                if (addrType == AddressType.Ptr) {
                    pointerFailed[0]++;
                }
                return;
            }

            if (addrType == AddressType.Ptr) {
                pointerResolved[0]++;
            }
            balances.merge(credKey, lovelace, BigInteger::add);
        };

        if (maxSlot > 0) {
            utxoState.forEachUtxoAtSlot(maxSlot, processor);
        } else {
            utxoState.forEachUtxo(processor);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("{} UTXO balance aggregation complete: {} UTXOs processed, {} skipped, {} credentials, " +
                        "{} pointer resolved, {} pointer failed, {} Byron/no-stake skipped, {}ms",
                pointerOnly ? "Pointer" : "Full", count[0], skipped[0], balances.size(),
                pointerResolved[0], pointerFailed[0], byronSkipped[0], elapsed);

        return balances;
    }

    /**
     * Extract the stake credential delegated to by a payment-owning address.
     *
     * Returns null when the address is not a payment-owning address (Reward/stake)
     * or when it is a payment-owning address with no delegation (Byron, Enterprise,
     * unresolved Pointer).
     *
     * Used by UTxO balance aggregation and genesis initialFunds seeding.
     *
     * @return credential key, or null
     */
    public CredentialKey extractCredential(String addressStr, PointerAddressResolver pointerResolver) {
        Address address = StakeCredentialExtractor.parseAddressOrNull(addressStr);
        return address != null ? extractCredential(address, addressStr, pointerResolver) : null;
    }

    private CredentialKey extractCredential(Address address, String addressStr, PointerAddressResolver pointerResolver) {
        AddressType addrType = address.getAddressType();
        if (addrType == AddressType.Byron) {
            return null;     // payment-owning, no stake credential
        }
        if (addrType == AddressType.Reward) {
            return null;     // not payment-owning at all
        }

        if (addrType == AddressType.Ptr) {
            if (pointerResolver == null) return null;
            return resolvePointerAddress(address, addressStr, pointerResolver);
        }

        StakeCredentialId credential = StakeCredentialExtractor.extractDelegationCredential(address);
        if (credential == null) return null;
        return new CredentialKey(credential.credentialType(),
                HexUtil.encodeHexString(credential.credentialHash()));
    }

    /**
     * Resolve a pointer address to a credential key using the PointerAddressResolver.
     * Uses CCL's PointerAddress class to parse (slot, txIndex, certIndex) from the address,
     * matching Yaci Store's approach in AccountBalanceProcessor.
     */
    private static CredentialKey resolvePointerAddress(Address address,
                                                       String addressStr,
                                                       PointerAddressResolver resolver) {
        try {
            var ptrAddr = new com.bloxbean.cardano.client.address.PointerAddress(address.getBytes());
            var pointer = ptrAddr.getPointer();
            if (pointer == null) {
                throw new IllegalStateException("Pointer address has no pointer: " + addressStr);
            }

            var cred = resolver.resolve(pointer.getSlot(), pointer.getTxIndex(), pointer.getCertIndex());
            if (cred == null) {
                return null;
            }

            return new CredentialKey(cred.credType(), cred.credHash());
        } catch (Exception e) {
            log.debug("Failed to resolve pointer address credential: {}", addressStr, e);
            return null;
        }
    }

}
