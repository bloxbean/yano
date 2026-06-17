package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisParser;
import com.bloxbean.cardano.yano.runtime.producer.ProducerStartupPlan;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Devnet shifted-genesis startup operation used by past-time-travel producers.
 */
@Slf4j
public final class DevnetGenesisShiftService {
    /**
     * Runtime callbacks needed to persist shifted genesis values and start the
     * deferred producer.
     */
    public interface Actions {
        GenesisConfig genesisConfig();

        void setConfigGenesisTimestamp(long timestampMillis);

        String shelleyGenesisFile();

        void applyShiftedGenesis(GenesisConfig genesisConfig);

        boolean isFreshStart();

        void setResolvedGenesisTimestamp(long timestampMillis);

        void initSlotTimeCalculator();

        void setConwayEraStartIfFreshStart(boolean freshStart);

        void storeGenesisUtxosIfNeeded(boolean freshStart);

        void startSlotLeaderTimeTravel(boolean freshStart);

        void startDevnetTimeTravel(boolean freshStart);
    }

    private final BooleanSupplier pastTimeTravelMode;
    private final Supplier<ProducerStartupPlan> startupPlan;
    private final BooleanSupplier productionInstalled;
    private final LongSupplier currentTimeMillis;
    private final Actions actions;

    public DevnetGenesisShiftService(BooleanSupplier pastTimeTravelMode,
                                     Supplier<ProducerStartupPlan> startupPlan,
                                     BooleanSupplier productionInstalled,
                                     LongSupplier currentTimeMillis,
                                     Actions actions) {
        this.pastTimeTravelMode = Objects.requireNonNull(pastTimeTravelMode, "pastTimeTravelMode");
        this.startupPlan = Objects.requireNonNull(startupPlan, "startupPlan");
        this.productionInstalled = Objects.requireNonNull(productionInstalled, "productionInstalled");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public long shiftGenesisAndStartProducer(int epochs) {
        if (!pastTimeTravelMode.getAsBoolean()) {
            throw new IllegalStateException("shiftGenesisAndStartProducer requires past-time-travel-mode=true");
        }

        ProducerStartupPlan plan = startupPlan.get();
        if (!plan.deferredUntilGenesisShift()) {
            throw new IllegalStateException(
                    "shiftGenesisAndStartProducer requires a deferred past-time-travel producer plan");
        }
        if (productionInstalled.getAsBoolean()) {
            throw new IllegalStateException("Block producer already started — shift can only be called once");
        }
        if (epochs <= 0) {
            throw new IllegalArgumentException("Epochs must be positive, got: " + epochs);
        }

        GenesisConfig genesisConfig = actions.genesisConfig();
        ShelleyGenesisData shelleyData = genesisConfig != null ? genesisConfig.getShelleyGenesisData() : null;
        if (shelleyData == null) {
            throw new IllegalStateException("Shelley genesis data required for epoch shift");
        }

        long shiftMillis = computeEpochShiftMillis(epochs, shelleyData);
        long shifted = currentTimeMillis.getAsLong() - shiftMillis;
        String shiftedSystemStart = Instant.ofEpochMilli(shifted)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        long shiftedMillis = Instant.parse(shiftedSystemStart).toEpochMilli();
        actions.setConfigGenesisTimestamp(shiftedMillis);
        log.info("Past time travel: shifted genesis timestamp back by {}ms for {} epochs", shiftMillis, epochs);

        persistShiftedSystemStart(shiftedSystemStart);

        GenesisConfig shiftedGenesis = genesisConfig.withSystemStart(shiftedSystemStart);
        actions.applyShiftedGenesis(shiftedGenesis);

        boolean freshStart = actions.isFreshStart();
        long resolvedGenesisTimestamp = shiftedGenesis.resolveAndPersistGenesisTimestamp(
                shiftedMillis, freshStart, actions.shelleyGenesisFile());
        actions.setResolvedGenesisTimestamp(resolvedGenesisTimestamp);
        actions.initSlotTimeCalculator();

        actions.setConwayEraStartIfFreshStart(freshStart);
        actions.storeGenesisUtxosIfNeeded(freshStart);

        switch (plan.mode()) {
            case SLOT_LEADER_TIME_TRAVEL -> actions.startSlotLeaderTimeTravel(freshStart);
            case DEVNET_TIME_TRAVEL -> actions.startDevnetTimeTravel(freshStart);
            default -> throw new IllegalStateException(
                    "Unsupported deferred producer startup mode: " + plan.mode());
        }

        log.info("Past time travel: block producer started after {}ms genesis shift ({} epochs)",
                shiftMillis, epochs);
        return shiftMillis;
    }

    private void persistShiftedSystemStart(String shiftedSystemStart) {
        String shelleyGenesisFile = actions.shelleyGenesisFile();
        if (shelleyGenesisFile == null || shelleyGenesisFile.isBlank()) {
            return;
        }

        try {
            ShelleyGenesisParser.updateSystemStart(new File(shelleyGenesisFile), shiftedSystemStart);
            log.info("Persisted shifted systemStart={} to {}", shiftedSystemStart, shelleyGenesisFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to persist shifted genesis timestamp to "
                    + shelleyGenesisFile + ": " + e.getMessage(), e);
        }
    }

    private static long computeEpochShiftMillis(int epochs, ShelleyGenesisData shelleyData) {
        long epochLengthSlots = shelleyData.epochLength();
        double slotLengthSec = shelleyData.slotLength();
        return (long) (epochs * epochLengthSlots * slotLengthSec * 1000);
    }
}
