package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Catalog operations for devnet snapshots.
 */
public final class DevnetSnapshotCatalogService {
    private final BooleanSupplier devMode;
    private final BooleanSupplier devnetProduction;
    private final ChainState chainState;
    private final Supplier<ChainStateSnapshots> snapshotsOrNull;
    private final Supplier<ChainStateSnapshots> snapshotsOrThrow;
    private final Supplier<BlockProducerService> blockProducerService;

    public DevnetSnapshotCatalogService(BooleanSupplier devMode,
                                        BooleanSupplier devnetProduction,
                                        ChainState chainState,
                                        Supplier<ChainStateSnapshots> snapshotsOrNull,
                                        Supplier<ChainStateSnapshots> snapshotsOrThrow,
                                        Supplier<BlockProducerService> blockProducerService) {
        this.devMode = devMode;
        this.devnetProduction = devnetProduction;
        this.chainState = chainState;
        this.snapshotsOrNull = snapshotsOrNull;
        this.snapshotsOrThrow = snapshotsOrThrow;
        this.blockProducerService = blockProducerService;
    }

    public SnapshotInfo create(String name) {
        requireSnapshotCreateAllowed();
        return storeOrThrow().create(name);
    }

    public List<SnapshotInfo> list() {
        ChainStateSnapshots capability = snapshotsOrNull.get();
        if (capability == null) {
            return List.of();
        }
        return store(capability).list();
    }

    public void delete(String name) {
        storeOrThrow().delete(name);
    }

    private void requireSnapshotCreateAllowed() {
        if (!devMode.getAsBoolean()) {
            throw new IllegalStateException("Snapshot requires dev mode (yano.dev-mode=true)");
        }
        if (!devnetProduction.getAsBoolean()) {
            throw new IllegalStateException("Snapshot requires block producer to be running");
        }
    }

    private DevnetSnapshotStore storeOrThrow() {
        return store(snapshotsOrThrow.get());
    }

    private DevnetSnapshotStore store(ChainStateSnapshots capability) {
        return new DevnetSnapshotStore(chainState, capability, blockProducerService.get());
    }
}
