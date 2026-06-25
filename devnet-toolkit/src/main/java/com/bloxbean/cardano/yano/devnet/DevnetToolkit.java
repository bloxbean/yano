package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetChainMutation;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetFundingAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetProducerExtensions;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntime;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetSnapshotAccess;

import java.util.List;
import java.util.Objects;

/**
 * Optional {@link DevnetControl} adapter backed by runtime-owned devnet SPI
 * ports.
 */
public final class DevnetToolkit implements DevnetControl {
    private final DevnetChainMutation chainMutation;
    private final DevnetSnapshotAccess snapshots;
    private final DevnetFundingAccess funding;
    private final DevnetProducerExtensions producerExtensions;

    /**
     * Creates a toolkit adapter from the runtime devnet SPI aggregate.
     *
     * @param runtime devnet runtime ports
     * @return devnet control adapter
     */
    public static DevnetToolkit from(DevnetRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new DevnetToolkit(
                runtime.chainMutation(),
                runtime.snapshots(),
                runtime.funding(),
                runtime.producerExtensions());
    }

    /**
     * Creates a toolkit adapter from the specific ports required by
     * {@link DevnetControl}.
     *
     * @param chainMutation rollback capability
     * @param snapshots snapshot capability
     * @param funding faucet capability
     * @param producerExtensions producer/time-control capability
     */
    public DevnetToolkit(DevnetChainMutation chainMutation,
                         DevnetSnapshotAccess snapshots,
                         DevnetFundingAccess funding,
                         DevnetProducerExtensions producerExtensions) {
        this.chainMutation = Objects.requireNonNull(chainMutation, "chainMutation");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.funding = Objects.requireNonNull(funding, "funding");
        this.producerExtensions = Objects.requireNonNull(producerExtensions, "producerExtensions");
    }

    @Override
    public void rollbackDevnetToSlot(long targetSlot) {
        chainMutation.rollback(DevnetRollbackTarget.slot(targetSlot));
    }

    @Override
    public DevnetRollbackResult rollbackDevnet(DevnetRollbackTarget target) {
        return chainMutation.rollback(target);
    }

    @Override
    public SnapshotInfo createDevnetSnapshot(String name) {
        return snapshots.create(name);
    }

    @Override
    public void restoreDevnetSnapshot(String name) {
        snapshots.restore(name);
    }

    @Override
    public DevnetRestoreResult restoreDevnetSnapshotAndGetTip(String name) {
        return snapshots.restore(name);
    }

    @Override
    public List<SnapshotInfo> listDevnetSnapshots() {
        return snapshots.list();
    }

    @Override
    public void deleteDevnetSnapshot(String name) {
        snapshots.delete(name);
    }

    @Override
    public FundResult fundAddress(String address, long lovelace) {
        return funding.fundAddress(address, lovelace);
    }

    @Override
    public TimeAdvanceResult advanceTimeBySlots(int slots) {
        return producerExtensions.advanceBySlots(slots);
    }

    @Override
    public TimeAdvanceResult advanceTimeUntilSlot(long targetSlot) {
        return producerExtensions.advanceUntilSlot(targetSlot);
    }

    @Override
    public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
        return producerExtensions.advanceBySeconds(seconds);
    }

    @Override
    public long shiftGenesisAndStartProducer(int epochs) {
        return producerExtensions.shiftGenesisAndStartProducer(epochs);
    }

    @Override
    public TimeAdvanceResult catchUpToWallClock() {
        return producerExtensions.catchUpToWallClock();
    }
}
