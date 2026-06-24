package com.bloxbean.cardano.yano.runtime.devnet.spi;

/**
 * Assembly-time aggregate for devnet-safe runtime ports.
 *
 * <p>This is an internal Yano module SPI, not a public user API. Toolkit
 * operation classes should receive the narrow port they need rather than this
 * whole aggregate. The aggregate intentionally exposes only ports required by
 * current devnet-toolkit and testkit consumers; new ports should be added only
 * when a concrete consumer needs them.</p>
 */
public interface DevnetRuntime {
    /**
     * Devnet-only chain mutation capability.
     *
     * @return rollback and chain-shortening operations
     */
    DevnetChainMutation chainMutation();

    /**
     * Devnet producer/time-travel extensions.
     *
     * @return producer extension operations
     */
    DevnetProducerExtensions producerExtensions();

    /**
     * Devnet funding capability.
     *
     * @return faucet operations
     */
    DevnetFundingAccess funding();

    /**
     * Devnet snapshot capability.
     *
     * @return snapshot catalog and restore operations
     */
    DevnetSnapshotAccess snapshots();
}
