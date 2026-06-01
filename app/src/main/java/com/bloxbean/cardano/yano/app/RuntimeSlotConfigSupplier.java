package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.ledgerrules.SlotConfigSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
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
    private final AtomicBoolean slotLengthFallbackLogged = new AtomicBoolean();

    RuntimeSlotConfigSupplier(YanoConfig config,
                              LongSupplier resolvedGenesisTimestampSupplier,
                              GenesisConfig genesisConfig) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.resolvedGenesisTimestampSupplier = Objects.requireNonNull(
                resolvedGenesisTimestampSupplier, "resolvedGenesisTimestampSupplier must not be null");
        this.genesisConfig = genesisConfig;
    }

    @Override
    public SlotConfig getSlotConfig() {
        return new SlotConfig(resolveSlotLengthMillis(), 0, resolveZeroTimeMillis());
    }

    long resolveZeroTimeMillis() {
        long configured = config.getGenesisTimestamp();
        if (configured > 0) {
            return requireEpochMillis(configured, "yano.block-producer.genesis-timestamp");
        }

        long resolved = resolvedGenesisTimestampSupplier.getAsLong();
        if (resolved > 0) {
            return requireEpochMillis(resolved, "resolved genesis timestamp");
        }

        if (genesisConfig != null) {
            long systemStart = genesisConfig.getSystemStartEpochMillis();
            if (systemStart > 0) {
                return requireEpochMillis(systemStart, "Shelley systemStart");
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
