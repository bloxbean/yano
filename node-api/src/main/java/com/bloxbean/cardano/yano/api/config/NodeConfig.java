package com.bloxbean.cardano.yano.api.config;

/**
 * Base interface for node configuration.
 * Implementations provide specific configuration options for different node types.
 */
public interface NodeConfig {

    /**
     * Validate the configuration.
     *
     * @throws IllegalArgumentException if the configuration is invalid
     */
    void validate();

    /**
     * Check if client mode is enabled (syncing with remote nodes)
     */
    boolean isClientEnabled();

    /**
     * Check if server mode is enabled (serving other clients)
     */
    boolean isServerEnabled();

    /**
     * Get the protocol magic number for the target network
     */
    long getProtocolMagic();

    /**
     * Number of slots per epoch (Shelley+). Must be loaded from shelley-genesis.json.
     */
    long getEpochLength();

    /**
     * Byron era slots per epoch (= k * 10). Must be loaded from Byron genesis or derived from Shelley securityParam.
     */
    long getByronSlotsPerEpoch();

    /**
     * First non-Byron era start slot for epoch/slot conversion.
     * 0 = no Byron era (preview, sanchonet, devnets). Mainnet: 4492800. Preprod: 86400.
     * Must be resolved from known-network rules, era metadata, or explicit config.
     */
    long getFirstNonByronSlot();

    /**
     * Check if dev mode is enabled.
     * Dev mode enables devnet-only features (rollback, snapshot, faucet, time advance, genesis download).
     */
    default boolean isDevMode() {
        return false;
    }
}
