package com.bloxbean.cardano.yano.runtime.devnet.spi;

import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.config.YanoConfig;

/**
 * Assembly-time aggregate for devnet-safe runtime ports.
 *
 * <p>This is an internal Yano module SPI, not a public user API. Toolkit
 * operation classes should receive the narrow port they need rather than this
 * whole aggregate.</p>
 */
public interface DevnetRuntime {
    /**
     * Runtime configuration for the assembled node.
     *
     * @return runtime configuration
     */
    YanoConfig config();

    /**
     * Maintenance gate facade for exclusive devnet mutations and guarded reads.
     *
     * @return runtime maintenance facade
     */
    RuntimeMaintenance maintenance();

    /**
     * Reused chain read capability.
     *
     * @return read-only block capability
     */
    ChainBlockReader chainBlocks();

    /**
     * Reused producer lifecycle controls.
     *
     * @return producer control role
     */
    ProducerControl producerControl();

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

    /**
     * Genesis metadata capability.
     *
     * @return genesis files and parsed runtime genesis values
     */
    DevnetGenesisAccess genesis();

    /**
     * Slot/time query capability.
     *
     * @return chronology operations
     */
    DevnetChronologyAccess chronology();
}
