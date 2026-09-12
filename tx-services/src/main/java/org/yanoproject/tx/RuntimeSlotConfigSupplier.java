package org.yanoproject.tx;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledgerrules.SlotConfigSupplier;
import org.yanoproject.runtime.blockproducer.GenesisConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves the current node slot timing for transaction validation/evaluation.
 */
final class RuntimeSlotConfigSupplier implements SlotConfigSupplier {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSlotConfigSupplier.class);
    // Epoch seconds for modern Cardano genesis times are around 1_500_000_000.
    // Milliseconds are three orders of magnitude larger; this catches seconds
    // accidentally passed into SlotConfig.zeroTime without constraining slot length.
    private static final long MIN_EPOCH_MILLIS_FOR_SECONDS_DETECTION = 10_000_000_000L;

    private final YanoConfig config;
    private final LongSupplier resolvedGenesisTimestampSupplier;
    private final GenesisConfig genesisConfig;
    private final EpochSlotCalc epochSlotCalc;
    private final AtomicBoolean slotLengthFallbackLogged = new AtomicBoolean();

    RuntimeSlotConfigSupplier(YanoConfig config,
                              LongSupplier resolvedGenesisTimestampSupplier,
                              GenesisConfig genesisConfig,
                              EpochSlotCalc epochSlotCalc) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.resolvedGenesisTimestampSupplier = Objects.requireNonNull(
                resolvedGenesisTimestampSupplier, "resolvedGenesisTimestampSupplier must not be null");
        this.genesisConfig = genesisConfig;
        this.epochSlotCalc = Objects.requireNonNull(epochSlotCalc, "epochSlotCalc must not be null");
    }

    @Override
    public SlotConfig getSlotConfig() {
        return new SlotConfig(resolveSlotLengthMillis(), epochSlotCalc.firstNonByronSlot(), resolveZeroTimeMillis());
    }

    @Override
    public EpochSlotCalc getEpochSlotCalc() {
        return epochSlotCalc;
    }

    boolean canResolveZeroTimeNow() {
        if (config.getGenesisTimestamp() > 0) {
            return true;
        }
        if (resolvedGenesisTimestampSupplier.getAsLong() > 0) {
            return true;
        }
        return genesisConfig != null && (epochSlotCalc.firstNonByronSlot() > 0
                ? genesisConfig.getNetworkStartTimeSeconds() > 0
                : genesisConfig.getSystemStartEpochMillis() > 0);
    }

    long resolveZeroTimeMillis() {
        long networkStartMillis = resolveNetworkStartMillis();
        // Shelley genesis systemStart is the network start, including the Byron period.
        // zeroTime must instead identify zeroSlot, the first post-Byron slot.
        if (epochSlotCalc.firstNonByronSlot() > 0) {
            if (genesisConfig == null) {
                throw new IllegalStateException(
                        "Cannot resolve Shelley transition time without genesis configuration");
            }
            long byronSlotMillis = Math.multiplyExact(genesisConfig.getByronSlotDurationSeconds(), 1_000L);
            return Math.addExact(networkStartMillis,
                    Math.multiplyExact(epochSlotCalc.firstNonByronSlot(), byronSlotMillis));
        }
        return networkStartMillis;
    }

    private long resolveNetworkStartMillis() {
        long configured = config.getGenesisTimestamp();
        if (configured > 0) {
            return requireEpochMillis(configured, YanoPropertyKeys.BlockProducer.GENESIS_TIMESTAMP);
        }

        long resolved = resolvedGenesisTimestampSupplier.getAsLong();
        if (resolved > 0) {
            return requireEpochMillis(resolved, "resolved genesis timestamp");
        }

        if (genesisConfig != null) {
            // Keep millisecond precision on devnets; public Byron networks use Byron startTime.
            long systemStart = epochSlotCalc.firstNonByronSlot() > 0
                    ? Math.multiplyExact(genesisConfig.getNetworkStartTimeSeconds(), 1_000L)
                    : genesisConfig.getSystemStartEpochMillis();
            if (systemStart > 0) {
                return requireEpochMillis(systemStart, "genesis network start");
            }
        }

        throw new IllegalStateException("Cannot resolve SlotConfig zeroTime: no valid genesis timestamp is available");
    }

    int resolveSlotLengthMillis() {
        int configured = config.getSlotLengthMillis();
        if (configured > 0) {
            return configured;
        }

        if (genesisConfig != null
                && genesisConfig.getShelleyGenesisData() != null
                && genesisConfig.getShelleyGenesisData().slotLength() > 0) {
            return (int) (genesisConfig.getShelleyGenesisData().slotLength() * 1000);
        }

        if (slotLengthFallbackLogged.compareAndSet(false, true)) {
            log.info("No valid Shelley slotLength available for transaction SlotConfig; using default slotLengthMillis=1000");
        }
        return 1000;
    }

    private static long requireEpochMillis(long value, String source) {
        if (value < MIN_EPOCH_MILLIS_FOR_SECONDS_DETECTION) {
            throw new IllegalStateException(source + " must be epoch milliseconds, got " + value
                    + " which looks like epoch seconds");
        }
        return value;
    }
}
