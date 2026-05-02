package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Public interface for UTXO storage and queries.
 * Implementations live in runtime (e.g., DefaultUtxoStore).
 */
public interface UtxoState {

    /**
     * Return current unspent UTXOs for a bech32 or hex address.
     * Pagination is 1-based; pageSize must be > 0.
     */
    List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize);

    /**
     * Return current unspent UTXOs for a payment credential (28-byte hash in hex),
     * or an address (bech32/hex) from which the payment credential is derived.
     * Pagination is 1-based; pageSize must be > 0.
     */
    List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize);

    /**
     * Return a specific UTXO by outpoint if it is currently unspent.
     */
    Optional<Utxo> getUtxo(Outpoint outpoint);

    /**
     * Convenience outpoint lookup.
     */
    default Optional<Utxo> getUtxo(String txHash, int index) {
        return getUtxo(new Outpoint(txHash, index));
    }

    /**
     * Return all outputs (spent and unspent) for a given transaction hash.
     * Used by tx-utxos endpoint to resolve transaction outputs.
     */
    default List<Utxo> getOutputsByTxHash(String txHash) {
        return List.of();
    }

    /**
     * Look up a UTXO by outpoint across both spent and unspent stores.
     * Returns the UTXO if found in either, or empty if not found.
     */
    default Optional<Utxo> getUtxoSpentOrUnspent(Outpoint outpoint) {
        return getUtxo(outpoint);
    }

    /**
     * Return the script reference CBOR (hex) for a given script hash, or empty if not found.
     */
    default Optional<byte[]> getScriptRefBytesByHash(String scriptHashHex) {
        return Optional.empty();
    }

    /**
     * Iterate over all unspent UTXOs.
     * The consumer receives (address, lovelace) for each UTXO.
     * Used for epoch-boundary stake distribution aggregation.
     *
     * @param consumer receives (bech32 address, lovelace amount) per UTXO
     */
    default void forEachUtxo(java.util.function.BiConsumer<String, java.math.BigInteger> consumer) {
        // Default no-op — implementations override
    }

    /**
     * Iterate over unspent UTXOs created at or before {@code maxSlot},
     * using a consistent point-in-time snapshot of the UTXO store.
     * <p>
     * This provides a deterministic view even if other threads are
     * concurrently modifying the UTXO store (e.g., during fast-sync).
     * Only UTXOs whose creation slot is ≤ maxSlot are included.
     *
     * @param maxSlot  only include UTXOs created at or before this slot
     * @param consumer receives (bech32 address, lovelace amount) per UTXO
     */
    default void forEachUtxoAtSlot(long maxSlot, java.util.function.BiConsumer<String, java.math.BigInteger> consumer) {
        // Default: delegate to unfiltered version (backward compatibility)
        forEachUtxo(consumer);
    }

    /**
     * Iterate UTXO delta log entries within a slot range.
     * Each delta contains the created and spent outpoints for one block.
     * Used for incremental balance aggregation at epoch boundaries.
     *
     * @param startSlot  inclusive start slot
     * @param endSlot    exclusive end slot
     * @param consumer   receives (txHash, index, address, lovelace, isCreated) for each UTXO change.
     *                   isCreated=true means UTXO was created, false means spent.
     */
    default void forEachUtxoDeltaInSlotRange(long startSlot, long endSlot,
                                              UtxoDeltaConsumer consumer) {
        // Default: no-op (implementations without delta support)
    }

    /**
     * Consumer for UTXO delta entries.
     */
    @FunctionalInterface
    interface UtxoDeltaConsumer {
        void accept(String address, java.math.BigInteger lovelace, boolean isCreated);
    }

    /**
     * Whether UTXO state is enabled and actively maintained.
     */
    boolean isEnabled();

    /**
     * Whether the live stake-credential UTXO balance aggregate is enabled.
     */
    default boolean isStakeBalanceIndexEnabled() {
        return false;
    }

    /**
     * Whether the live stake-credential balance aggregate is complete for the
     * current UTXO store. Existing stores upgraded from a version without the
     * aggregate may require a rebuild before this returns true.
     */
    default boolean isStakeBalanceIndexReady() {
        return false;
    }

    /**
     * Return the current unspent lovelace controlled by a stake credential.
     * This is UTXO-only and does not include withdrawable rewards.
     */
    default Optional<BigInteger> getUtxoBalanceByStakeCredential(int credType, String credentialHash) {
        return Optional.empty();
    }
}
