package com.bloxbean.cardano.yano.runtime.chain;

/**
 * Creates and restores storage-level snapshots for devnet tooling.
 */
public interface ChainStateSnapshots {
    void createSnapshot(String snapshotPath);

    void restoreFromSnapshot(String snapshotPath);

    String getDbPath();
}
