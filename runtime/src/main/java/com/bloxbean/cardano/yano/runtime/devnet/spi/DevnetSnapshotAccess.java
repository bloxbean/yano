package com.bloxbean.cardano.yano.runtime.devnet.spi;

import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;

import java.util.List;

/**
 * Snapshot catalog and restore port backed by runtime-owned storage handling.
 */
public interface DevnetSnapshotAccess {
    /**
     * Creates a named devnet snapshot.
     *
     * @param name snapshot name
     * @return snapshot metadata
     */
    SnapshotInfo create(String name);

    /**
     * Restores a named snapshot.
     *
     * @param name snapshot name
     * @return restored chain tip
     */
    DevnetRestoreResult restore(String name);

    /**
     * Lists available devnet snapshots.
     *
     * @return snapshot metadata
     */
    List<SnapshotInfo> list();

    /**
     * Deletes a named devnet snapshot.
     *
     * @param name snapshot name
     */
    void delete(String name);
}
