package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducer;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;

import java.util.Map;
import java.util.Objects;

/**
 * Owns the selected block-production strategy and producer control operations.
 */
public final class ProducerSubsystem implements Subsystem {
    private BlockProduction activeProduction;

    @Override
    public String name() {
        return "producer";
    }

    public synchronized void install(BlockProduction production) {
        Objects.requireNonNull(production, "production");
        if (activeProduction != null && activeProduction != production) {
            if (activeProduction.isRunning()) {
                throw new IllegalStateException("Cannot replace running producer: " + activeProduction.mode());
            }
        }
        activeProduction = production;
    }

    public void installDevnet(DevnetBlockProducer producer, boolean timeTravel) {
        install(BlockProductions.devnet(
                producer,
                timeTravel ? ProducerMode.DEVNET_TIME_TRAVEL : ProducerMode.DEVNET));
    }

    public void installSlotLeader(BlockProducerService producer) {
        install(BlockProductions.slotLeader(producer));
    }

    public void installSlotLeaderTimeTravel(SlotLeaderTimeTravelBlockProducer producer) {
        install(BlockProductions.slotLeaderTimeTravel(producer));
    }

    public synchronized boolean hasProduction() {
        return activeProduction != null;
    }

    public synchronized ProducerMode modeOrNull() {
        return activeProduction != null ? activeProduction.mode() : null;
    }

    public synchronized boolean hasDevnetProduction() {
        return activeProduction != null
                && (activeProduction.mode() == ProducerMode.DEVNET
                || activeProduction.mode() == ProducerMode.DEVNET_TIME_TRAVEL);
    }

    public synchronized BlockProducerService serviceOrNull() {
        return activeProduction;
    }

    public synchronized BlockProducerService serviceOrThrow(String operation) {
        return requireProduction(operation);
    }

    @Override
    public synchronized void start() {
        if (activeProduction != null && !activeProduction.isRunning()) {
            activeProduction.start();
        }
    }

    public synchronized void startOrThrow(String operation) {
        BlockProduction production = requireProduction(operation);
        if (!production.isRunning()) {
            production.start();
        }
    }

    @Override
    public synchronized void stop() {
        if (activeProduction != null && activeProduction.isRunning()) {
            activeProduction.stop();
        }
    }

    public synchronized void stopOrThrow(String operation) {
        BlockProduction production = requireProduction(operation);
        if (production.isRunning()) {
            production.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public synchronized boolean isRunning() {
        return activeProduction != null && activeProduction.isRunning();
    }

    public synchronized void resetToChainTip() {
        if (activeProduction != null) {
            activeProduction.resetToChainTip();
        }
    }

    public synchronized void resetToChainTipOrThrow(String operation) {
        requireProduction(operation).resetToChainTip();
    }

    public synchronized int produceEmptyBlocksToSlot(long targetSlot, String operation) {
        BlockProduction production = requireProduction(operation);
        return production.produceEmptyBlocksToSlot(targetSlot);
    }

    public synchronized int produceLeaderBlocksToSlot(long targetSlot, String operation) {
        BlockProduction production = requireProduction(operation);
        return production.produceLeaderBlocksToSlot(targetSlot);
    }

    public synchronized long lastCheckedSlot(String operation) {
        return requireProduction(operation).lastCheckedSlot();
    }

    public synchronized int slotLengthMillis(String operation) {
        return requireProduction(operation).slotLengthMillis();
    }

    public synchronized void setForceSequentialSlots(boolean forceSequentialSlots, String operation) {
        requireProduction(operation).setForceSequentialSlots(forceSequentialSlots);
    }

    @Override
    public synchronized SubsystemHealth health() {
        if (activeProduction == null) {
            return SubsystemHealth.up(name());
        }
        return new SubsystemHealth(name(), SubsystemHealth.Status.UP, null, Map.of(
                "mode", activeProduction.mode().name(),
                "running", activeProduction.isRunning()));
    }

    private BlockProduction requireProduction(String operation) {
        if (activeProduction == null) {
            throw new UnsupportedOperationException(operation + " requires an installed block producer");
        }
        return activeProduction;
    }
}
