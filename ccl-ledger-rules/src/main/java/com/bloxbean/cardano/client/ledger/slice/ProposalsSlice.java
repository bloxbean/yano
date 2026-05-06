package com.bloxbean.cardano.client.ledger.slice;

/**
 * Provides access to governance proposal state during validation.
 * <p>
 * Used by governance validation rules (proposal submission, voting).
 * Yaci provides a concrete implementation backed by its ledger state storage.
 */
public interface ProposalsSlice {

    /**
     * Check if a governance action ID exists (was previously enacted or is pending).
     *
     * @param txHash the transaction hash of the governance action (hex-encoded)
     * @param index  the governance action index within the transaction
     * @return true if the governance action exists
     */
    boolean exists(String txHash, int index);

    /**
     * Check if a governance action is currently active/votable.
     * Defaults to {@link #exists(String, int)} for simple in-memory slices.
     *
     * @param txHash the transaction hash of the governance action (hex-encoded)
     * @param index  the governance action index within the transaction
     * @return true if the governance action is active and can receive votes
     */
    default boolean isActive(String txHash, int index) {
        return exists(txHash, index);
    }

    /**
     * Check if a governance action is active at the supplied epoch.
     * Providers that do not track epoch-specific status can delegate to
     * {@link #isActive(String, int)}.
     */
    default boolean isActive(String txHash, int index, long currentEpoch) {
        return isActive(txHash, index);
    }

    /**
     * Get the type of a governance action.
     *
     * @param txHash the transaction hash (hex-encoded)
     * @param index  the governance action index
     * @return the action type name, or null if not found
     */
    String getActionType(String txHash, int index);
}
