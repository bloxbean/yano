package com.bloxbean.cardano.yano.app;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YaciNodeProducerTest {

    @Test
    void resolveRuntimeCurrentSlotReturnsNonNegativeSlot() {
        assertEquals(42L, YaciNodeProducer.resolveRuntimeCurrentSlot(() -> 42L));
    }

    @Test
    void resolveRuntimeCurrentSlotRejectsNegativeSlot() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> YaciNodeProducer.resolveRuntimeCurrentSlot(() -> -1L));

        assertEquals("Failed to resolve current slot from runtime", error.getMessage());
        assertEquals("current slot supplier returned -1", error.getCause().getMessage());
    }

    @Test
    void rollbackRetentionAbsentKeepsExistingLeafValues() {
        var settings = YaciNodeProducer.resolveRollbackRetentionSettings(
                Optional.empty(),
                0,
                4320,
                false,
                5,
                false,
                50,
                false,
                Optional.empty(),
                false,
                0,
                false);

        assertFalse(settings.umbrellaEnabled());
        assertEquals(4320, settings.utxoRollbackWindow());
        assertEquals(5, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(50, settings.accountStateSnapshotRetentionEpochs());
        assertTrue(settings.accountHistoryRollbackSafetySlots().isEmpty());
        assertEquals(0, settings.blockBodyPruneDepth());
    }

    @Test
    void rollbackRetentionFillsMissingLeafValuesFromEpochLength() {
        var settings = YaciNodeProducer.resolveRollbackRetentionSettings(
                Optional.of(20),
                432000,
                4320,
                false,
                5,
                false,
                50,
                false,
                Optional.empty(),
                false,
                0,
                false);

        assertTrue(settings.umbrellaEnabled());
        assertEquals(8_640_000, settings.slotWindow());
        assertEquals(8_640_000, settings.utxoRollbackWindow());
        assertEquals(21, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(50, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(8_640_000L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(0, settings.blockBodyPruneDepth());
    }

    @Test
    void rollbackRetentionDoesNotOverrideExplicitLeafValues() {
        var settings = YaciNodeProducer.resolveRollbackRetentionSettings(
                Optional.of(20),
                432000,
                7_776_000,
                true,
                40,
                true,
                120,
                true,
                Optional.of(7_776_000L),
                true,
                2160,
                true);

        assertTrue(settings.umbrellaEnabled());
        assertEquals(7_776_000, settings.utxoRollbackWindow());
        assertEquals(40, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(120, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(7_776_000L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(2160, settings.blockBodyPruneDepth());
    }

    @Test
    void rollbackRetentionRejectsNegativeEpochs() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> YaciNodeProducer.resolveRollbackRetentionSettings(
                        Optional.of(-1),
                        432000,
                        4320,
                        false,
                        5,
                        false,
                        50,
                        false,
                        Optional.empty(),
                        false,
                        0,
                        false));

        assertEquals("yaci.node.rollback-retention-epochs must be >= 0", error.getMessage());
    }
}
