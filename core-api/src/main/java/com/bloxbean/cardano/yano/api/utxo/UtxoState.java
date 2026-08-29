package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

    /** Consistent snapshot of complete live UTXO records for derived resolvers. */
    default long forEachUtxoRecord(java.util.function.Consumer<com.bloxbean.cardano.yano.api.utxo.model.Utxo> consumer) {
        // Implementations without a complete store cannot seed a live resolver.
        return -1;
    }

    /**
     * Iterate over UTXOs that were unspent at {@code maxSlot}, using a
     * consistent point-in-time snapshot of the UTXO store.
     * <p>
     * This provides a deterministic view even if other threads are
     * concurrently modifying the UTXO store (e.g., during fast-sync or
     * epoch-boundary crash recovery). Implementations whose durable tip is
     * newer than {@code maxSlot} must restore outputs spent after the target
     * slot, or fail closed when the required history is unavailable.
     *
     * @param maxSlot  ledger slot at which each returned output must be unspent
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

    /**
     * Open the complete live stake-balance index at an exact canonical
     * coordinate. Disabled, filtered or unready stores return empty. A store
     * that is ready but cannot prove the coordinate fails closed with
     * {@link StakeBalanceConsistencyException}.
     */
    default Optional<StakeBalanceView> openStakeBalanceView(CanonicalBlockReference expectedCoordinate) {
        return Optional.empty();
    }

    /**
     * Prepare the optional pointer-address UTXO index at an exact boundary
     * coordinate. Implementations may perform an explicitly configured,
     * bounded one-time backfill. Unavailable indexes must remain fail-closed
     * and use the historical scan.
     */
    default PointerIndexPreparation preparePointerIndex(
            CanonicalBlockReference expectedCoordinate, long maxCreationSlot) {
        return preparePointerIndex(expectedCoordinate, maxCreationSlot, ignored -> { });
    }

    /**
     * Prepare the pointer index and stream pointer rows observed by a one-time
     * backfill to the caller. The index covers the full advertised store
     * coordinate; the observer receives only rows whose creation slot is at or
     * before {@code maxCreationSlot}. The observer is unused when no backfill runs.
     */
    default PointerIndexPreparation preparePointerIndex(
            CanonicalBlockReference expectedCoordinate,
            long maxCreationSlot,
            Consumer<PointerUtxo> backfillObserver) {
        return PointerIndexPreparation.unavailable();
    }

    /** Whether the temporary pointer-index shadow scan is enabled. */
    default boolean isPointerIndexShadowScanEnabled() {
        return false;
    }
}
