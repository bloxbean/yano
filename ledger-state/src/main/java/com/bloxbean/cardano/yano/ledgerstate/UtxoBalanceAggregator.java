package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
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
        Map<CredentialKey, BigInteger> balances = new HashMap<>();
        long[] count = {0};
        long[] skipped = {0};
        long[] pointerResolved = {0};
        long[] pointerFailed = {0};
        long start = System.currentTimeMillis();

        // Use slot-filtered iteration for consistent epoch boundary snapshot
        java.util.function.BiConsumer<String, java.math.BigInteger> processor = (addressStr, lovelace) -> {
            count[0]++;
            if (lovelace == null || lovelace.signum() <= 0) return;

            try {
                Address address = new Address(addressStr);
                AddressType addrType = address.getAddressType();

                // Pointer addresses: resolve via PointerAddressResolver
                if (addrType == AddressType.Ptr) {
                    if (pointerResolver == null) {
                        skipped[0]++;
                        return;
                    }
                    var resolved = resolvePointerAddress(address, pointerResolver);
                    if (resolved != null) {
                        balances.merge(resolved, lovelace, BigInteger::add);
                        pointerResolved[0]++;
                    } else {
                        pointerFailed[0]++;
                        skipped[0]++;
                    }
                    return;
                }

                byte[] delegationHash = address.getDelegationCredentialHash()
                        .orElse(null);

                if (delegationHash == null || delegationHash.length != 28) {
                    skipped[0]++;
                    return;
                }

                int credType = getStakeCredType(address);
                String credHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(delegationHash);

                balances.merge(new CredentialKey(credType, credHash), lovelace, BigInteger::add);
            } catch (Exception e) {
                skipped[0]++;
            }
        };

        if (maxSlot > 0) {
            utxoState.forEachUtxoAtSlot(maxSlot, processor);
        } else {
            utxoState.forEachUtxo(processor);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("UTXO balance aggregation complete: {} UTXOs processed, {} skipped, {} credentials, " +
                        "{} pointer resolved, {} pointer failed, {}ms",
                count[0], skipped[0], balances.size(), pointerResolved[0], pointerFailed[0], elapsed);

        return balances;
    }

    /**
     * Resolve a pointer address to a credential key using the PointerAddressResolver.
     * Uses CCL's PointerAddress class to parse (slot, txIndex, certIndex) from the address,
     * matching Yaci Store's approach in AccountBalanceProcessor.
     */
    private static CredentialKey resolvePointerAddress(Address address,
                                                       PointerAddressResolver resolver) {
        try {
            var ptrAddr = new com.bloxbean.cardano.client.address.PointerAddress(address.getBytes());
            var pointer = ptrAddr.getPointer();
            if (pointer == null) return null;

            var cred = resolver.resolve(pointer.getSlot(), pointer.getTxIndex(), pointer.getCertIndex());
            if (cred == null) return null;

            return new CredentialKey(cred.credType(), cred.credHash());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Determine the stake credential type from a CCL Address.
     * Returns 0 for key hash, 1 for script hash.
     */
    private static int getStakeCredType(Address address) {
        byte header = address.getBytes()[0];
        int typeNibble = (header >> 4) & 0x0F;
        return switch (typeNibble) {
            case 0, 1 -> 0; // StakeKeyHash
            case 2, 3 -> 1; // StakeScriptHash
            case 4 -> 0;    // Pointer + KeyHash payment
            case 5 -> 1;    // Pointer + ScriptHash payment
            case 0x0E -> 0; // Reward key hash
            case 0x0F -> 1; // Reward script hash
            default -> 0;
        };
    }
}
