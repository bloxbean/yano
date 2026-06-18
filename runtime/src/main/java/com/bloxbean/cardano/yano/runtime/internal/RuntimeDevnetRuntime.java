package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.FundingRequest;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetChainMutation;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetFundingAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetProducerExtensions;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetRuntime;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetSnapshotAccess;
import com.bloxbean.cardano.yano.runtime.producer.ProducerMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Runtime-owned implementation of devnet SPI ports exposed to optional Yano
 * modules.
 */
final class RuntimeDevnetRuntime implements DevnetRuntime {
    private final DevnetChainMutation chainMutation;
    private final DevnetProducerExtensions producerExtensions;
    private final DevnetFundingAccess funding;
    private final DevnetSnapshotAccess snapshots;

    private RuntimeDevnetRuntime(DevnetChainMutation chainMutation,
                                 DevnetProducerExtensions producerExtensions,
                                 DevnetFundingAccess funding,
                                 DevnetSnapshotAccess snapshots) {
        this.chainMutation = Objects.requireNonNull(chainMutation, "chainMutation");
        this.producerExtensions = Objects.requireNonNull(producerExtensions, "producerExtensions");
        this.funding = Objects.requireNonNull(funding, "funding");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    static RuntimeDevnetRuntime create(DevnetChainMutation chainMutation,
                                       BooleanSupplier producerAvailable,
                                       Supplier<ProducerMode> producerMode,
                                       IntFunction<TimeAdvanceResult> advanceBySlots,
                                       LongFunction<TimeAdvanceResult> advanceUntilSlot,
                                       IntFunction<TimeAdvanceResult> advanceBySeconds,
                                       Supplier<TimeAdvanceResult> catchUpToWallClock,
                                       IntToLongFunction shiftGenesisAndStartProducer,
                                       FundAddress fundAddress,
                                       Function<String, SnapshotInfo> createSnapshot,
                                       Function<String, DevnetRestoreResult> restoreSnapshot,
                                       Supplier<List<SnapshotInfo>> listSnapshots,
                                       Consumer<String> deleteSnapshot) {
        return new RuntimeDevnetRuntime(
                chainMutation,
                new RuntimeDevnetProducerExtensions(
                        producerAvailable,
                        producerMode,
                        advanceBySlots,
                        advanceUntilSlot,
                        advanceBySeconds,
                        catchUpToWallClock,
                        shiftGenesisAndStartProducer),
                new RuntimeDevnetFundingAccess(fundAddress),
                new RuntimeDevnetSnapshotAccess(
                        createSnapshot,
                        restoreSnapshot,
                        listSnapshots,
                        deleteSnapshot));
    }

    @Override
    public DevnetChainMutation chainMutation() {
        return chainMutation;
    }

    @Override
    public DevnetProducerExtensions producerExtensions() {
        return producerExtensions;
    }

    @Override
    public DevnetFundingAccess funding() {
        return funding;
    }

    @Override
    public DevnetSnapshotAccess snapshots() {
        return snapshots;
    }

    @FunctionalInterface
    interface FundAddress {
        FundResult fund(String address, long lovelace);
    }

    private static final class RuntimeDevnetProducerExtensions implements DevnetProducerExtensions {
        private final BooleanSupplier available;
        private final Supplier<ProducerMode> mode;
        private final IntFunction<TimeAdvanceResult> advanceBySlots;
        private final LongFunction<TimeAdvanceResult> advanceUntilSlot;
        private final IntFunction<TimeAdvanceResult> advanceBySeconds;
        private final Supplier<TimeAdvanceResult> catchUpToWallClock;
        private final IntToLongFunction shiftGenesisAndStartProducer;

        private RuntimeDevnetProducerExtensions(BooleanSupplier available,
                                                Supplier<ProducerMode> mode,
                                                IntFunction<TimeAdvanceResult> advanceBySlots,
                                                LongFunction<TimeAdvanceResult> advanceUntilSlot,
                                                IntFunction<TimeAdvanceResult> advanceBySeconds,
                                                Supplier<TimeAdvanceResult> catchUpToWallClock,
                                                IntToLongFunction shiftGenesisAndStartProducer) {
            this.available = Objects.requireNonNull(available, "available");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.advanceBySlots = Objects.requireNonNull(advanceBySlots, "advanceBySlots");
            this.advanceUntilSlot = Objects.requireNonNull(advanceUntilSlot, "advanceUntilSlot");
            this.advanceBySeconds = Objects.requireNonNull(advanceBySeconds, "advanceBySeconds");
            this.catchUpToWallClock = Objects.requireNonNull(catchUpToWallClock, "catchUpToWallClock");
            this.shiftGenesisAndStartProducer = Objects.requireNonNull(
                    shiftGenesisAndStartProducer,
                    "shiftGenesisAndStartProducer");
        }

        @Override
        public boolean isAvailable() {
            return available.getAsBoolean();
        }

        @Override
        public Optional<ProducerMode> mode() {
            return Optional.ofNullable(mode.get());
        }

        @Override
        public TimeAdvanceResult advanceBySlots(int slots) {
            return advanceBySlots.apply(slots);
        }

        @Override
        public TimeAdvanceResult advanceUntilSlot(long targetSlot) {
            return advanceUntilSlot.apply(targetSlot);
        }

        @Override
        public TimeAdvanceResult advanceBySeconds(int seconds) {
            return advanceBySeconds.apply(seconds);
        }

        @Override
        public TimeAdvanceResult catchUpToWallClock() {
            return catchUpToWallClock.get();
        }

        @Override
        public long shiftGenesisAndStartProducer(int epochs) {
            return shiftGenesisAndStartProducer.applyAsLong(epochs);
        }
    }

    private static final class RuntimeDevnetFundingAccess implements DevnetFundingAccess {
        private final FundAddress fundAddress;

        private RuntimeDevnetFundingAccess(FundAddress fundAddress) {
            this.fundAddress = Objects.requireNonNull(fundAddress, "fundAddress");
        }

        @Override
        public FundResult fundAddress(String address, long lovelace) {
            return fundAddress.fund(address, lovelace);
        }

        @Override
        public List<FundResult> fundAddresses(List<FundingRequest> requests) {
            Objects.requireNonNull(requests, "requests");
            return requests.stream()
                    .map(request -> fundAddress(request.address(), request.lovelace()))
                    .toList();
        }
    }

    private static final class RuntimeDevnetSnapshotAccess implements DevnetSnapshotAccess {
        private final Function<String, SnapshotInfo> create;
        private final Function<String, DevnetRestoreResult> restore;
        private final Supplier<List<SnapshotInfo>> list;
        private final Consumer<String> delete;

        private RuntimeDevnetSnapshotAccess(Function<String, SnapshotInfo> create,
                                            Function<String, DevnetRestoreResult> restore,
                                            Supplier<List<SnapshotInfo>> list,
                                            Consumer<String> delete) {
            this.create = Objects.requireNonNull(create, "create");
            this.restore = Objects.requireNonNull(restore, "restore");
            this.list = Objects.requireNonNull(list, "list");
            this.delete = Objects.requireNonNull(delete, "delete");
        }

        @Override
        public SnapshotInfo create(String name) {
            return create.apply(name);
        }

        @Override
        public DevnetRestoreResult restore(String name) {
            return restore.apply(name);
        }

        @Override
        public List<SnapshotInfo> list() {
            return list.get();
        }

        @Override
        public void delete(String name) {
            delete.accept(name);
        }
    }
}
