package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.UpstreamPreset;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionGenesisValues;
import com.bloxbean.cardano.yano.runtime.config.RollbackRetentionPlanner;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class YanoProducerTest {

    @Test
    void rollbackRetentionAdapterHonorsConfigPresenceAndPopulatesGlobals() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW, "4320",
                RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS, "10",
                RollbackRetentionPlanner.ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS, "123"));
        producer.rollbackRetentionEpochs = Optional.of(20);
        producer.utxoRollbackWindow = 4320;
        producer.accountStateEpochBlockDataRetentionLag = 5;
        producer.accountStateSnapshotRetentionEpochs = 10;
        producer.accountHistoryRollbackSafetySlots = Optional.of(123L);
        producer.blockBodyPruneDepth = 2160;

        var settings = producer.resolveRollbackRetentionSettings(
                new RollbackRetentionGenesisValues(432_000, 0.05));

        assertEquals(4320, settings.utxoRollbackWindow());
        assertEquals(21, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(10, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(123L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(864_000, settings.blockBodyPruneDepth());

        var globals = new java.util.HashMap<String, Object>();
        producer.putRollbackRetentionGlobals(globals, settings);

        assertEquals(4320, globals.get(RollbackRetentionPlanner.UTXO_ROLLBACK_WINDOW));
        assertEquals(21, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG));
        assertEquals(10, globals.get(RollbackRetentionPlanner.ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS));
        assertEquals(123L, globals.get(RollbackRetentionPlanner.ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS));
        assertEquals(864_000, globals.get(RollbackRetentionPlanner.BLOCK_BODY_PRUNE_DEPTH));
    }

    @Test
    void upstreamValidationOverrideIsHonoredWithLegacyRemoteConfig() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.VALIDATION_LEVEL, "header-signature",
                YanoPropertyKeys.Upstream.VALIDATION_BODY_LEVEL, "none"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(UpstreamPreset.TRUSTED_SINGLE, upstream.getMode());
        assertEquals("header-signature", upstream.getValidation().normalizedLevel());
        assertEquals("none", upstream.getValidation().normalizedBodyLevel());
    }

    @Test
    void upstreamSelectionRollbackWindowDefaultsToGenesisDerivedSentinel() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "trusted-single"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(0L, upstream.getSelection().getRollbackWindowSlots());
    }

    @Test
    void upstreamSelectionRollbackWindowOverrideIsHonored() {
        var producer = new YanoProducer(Thread.currentThread().getContextClassLoader());
        producer.appConfig = new PresentConfig(Map.of(
                YanoPropertyKeys.Upstream.MODE, "trusted-single",
                YanoPropertyKeys.Upstream.SELECTION_ROLLBACK_WINDOW_SLOTS, "123"));

        var upstream = producer.parseUpstreamConfig();

        assertNotNull(upstream);
        assertEquals(123L, upstream.getSelection().getRollbackWindowSlots());
    }

    private record PresentConfig(Map<String, String> values) implements Config {
        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new IllegalArgumentException("Missing config property: " + propertyName));
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = values.get(propertyName);
            if (value == null) {
                return Optional.empty();
            }
            if (propertyType == String.class) {
                return Optional.of(propertyType.cast(value));
            }
            if (propertyType == Long.class) {
                return Optional.of(propertyType.cast(Long.valueOf(value)));
            }
            throw new UnsupportedOperationException("Unsupported config type: " + propertyType.getName());
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return java.util.List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("Cannot unwrap to " + type.getName());
        }
    }
}
