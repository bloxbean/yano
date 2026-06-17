package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Runtime-owned devnet toolkit boundary exposed as the optional
 * {@link DevnetControl} role.
 */
public final class DevnetToolkit implements DevnetControl {
    private final LongConsumer rollbackToSlot;
    private final Function<DevnetRollbackTarget, DevnetRollbackResult> rollback;
    private final Function<String, SnapshotInfo> createSnapshot;
    private final Consumer<String> restoreSnapshot;
    private final Function<String, DevnetRestoreResult> restoreSnapshotAndGetTip;
    private final Supplier<List<SnapshotInfo>> listSnapshots;
    private final Consumer<String> deleteSnapshot;
    private final Faucet faucet;
    private final IntFunction<TimeAdvanceResult> advanceSlots;
    private final IntFunction<TimeAdvanceResult> advanceSeconds;
    private final IntToLongFunction shiftGenesisAndStartProducer;
    private final Supplier<TimeAdvanceResult> catchUpToWallClock;

    public DevnetToolkit(LongConsumer rollbackToSlot,
                         Function<DevnetRollbackTarget, DevnetRollbackResult> rollback,
                         Function<String, SnapshotInfo> createSnapshot,
                         Consumer<String> restoreSnapshot,
                         Function<String, DevnetRestoreResult> restoreSnapshotAndGetTip,
                         Supplier<List<SnapshotInfo>> listSnapshots,
                         Consumer<String> deleteSnapshot,
                         Faucet faucet,
                         IntFunction<TimeAdvanceResult> advanceSlots,
                         IntFunction<TimeAdvanceResult> advanceSeconds,
                         IntToLongFunction shiftGenesisAndStartProducer,
                         Supplier<TimeAdvanceResult> catchUpToWallClock) {
        this.rollbackToSlot = Objects.requireNonNull(rollbackToSlot, "rollbackToSlot");
        this.rollback = Objects.requireNonNull(rollback, "rollback");
        this.createSnapshot = Objects.requireNonNull(createSnapshot, "createSnapshot");
        this.restoreSnapshot = Objects.requireNonNull(restoreSnapshot, "restoreSnapshot");
        this.restoreSnapshotAndGetTip = Objects.requireNonNull(restoreSnapshotAndGetTip, "restoreSnapshotAndGetTip");
        this.listSnapshots = Objects.requireNonNull(listSnapshots, "listSnapshots");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
        this.faucet = Objects.requireNonNull(faucet, "faucet");
        this.advanceSlots = Objects.requireNonNull(advanceSlots, "advanceSlots");
        this.advanceSeconds = Objects.requireNonNull(advanceSeconds, "advanceSeconds");
        this.shiftGenesisAndStartProducer = Objects.requireNonNull(
                shiftGenesisAndStartProducer, "shiftGenesisAndStartProducer");
        this.catchUpToWallClock = Objects.requireNonNull(catchUpToWallClock, "catchUpToWallClock");
    }

    @Override
    public void rollbackDevnetToSlot(long targetSlot) {
        rollbackToSlot.accept(targetSlot);
    }

    @Override
    public DevnetRollbackResult rollbackDevnet(DevnetRollbackTarget target) {
        return rollback.apply(target);
    }

    @Override
    public SnapshotInfo createDevnetSnapshot(String name) {
        return createSnapshot.apply(name);
    }

    @Override
    public void restoreDevnetSnapshot(String name) {
        restoreSnapshot.accept(name);
    }

    @Override
    public DevnetRestoreResult restoreDevnetSnapshotAndGetTip(String name) {
        return restoreSnapshotAndGetTip.apply(name);
    }

    @Override
    public List<SnapshotInfo> listDevnetSnapshots() {
        return listSnapshots.get();
    }

    @Override
    public void deleteDevnetSnapshot(String name) {
        deleteSnapshot.accept(name);
    }

    @Override
    public FundResult fundAddress(String address, long lovelace) {
        return faucet.fundAddress(address, lovelace);
    }

    @Override
    public TimeAdvanceResult advanceTimeBySlots(int slots) {
        return advanceSlots.apply(slots);
    }

    @Override
    public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
        return advanceSeconds.apply(seconds);
    }

    @Override
    public long shiftGenesisAndStartProducer(int epochs) {
        return shiftGenesisAndStartProducer.applyAsLong(epochs);
    }

    @Override
    public TimeAdvanceResult catchUpToWallClock() {
        return catchUpToWallClock.get();
    }

    /**
     * Faucet operation supplied by the runtime producer implementation.
     */
    @FunctionalInterface
    public interface Faucet {
        FundResult fundAddress(String address, long lovelace);
    }
}
