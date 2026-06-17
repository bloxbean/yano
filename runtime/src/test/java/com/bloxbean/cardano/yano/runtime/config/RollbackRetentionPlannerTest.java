package com.bloxbean.cardano.yano.runtime.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackRetentionPlannerTest {

    @Test
    void absentUmbrellaKeepsExistingLeafValues() {
        var settings = RollbackRetentionPlanner.resolve(
                Optional.empty(),
                0,
                0,
                4320,
                false,
                5,
                false,
                50,
                false,
                Optional.empty(),
                false,
                0);

        assertFalse(settings.umbrellaEnabled());
        assertEquals(4320, settings.utxoRollbackWindow());
        assertEquals(5, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(50, settings.accountStateSnapshotRetentionEpochs());
        assertTrue(settings.accountHistoryRollbackSafetySlots().isEmpty());
        assertEquals(0, settings.blockBodyPruneDepth());
    }

    @Test
    void umbrellaFillsMissingLeafValuesFromEpochLength() {
        var settings = RollbackRetentionPlanner.resolve(
                Optional.of(20),
                432000,
                0.05,
                4320,
                false,
                5,
                false,
                50,
                false,
                Optional.empty(),
                false,
                0);

        assertTrue(settings.umbrellaEnabled());
        assertEquals(8_640_000, settings.slotWindow());
        assertEquals(8_640_000, settings.utxoRollbackWindow());
        assertEquals(21, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(50, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(8_640_000L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(0, settings.blockBodyPruneDepth());
    }

    @Test
    void explicitLeafValuesArePreservedButUnsafeBlockBodyPruneDepthIsRaised() {
        var settings = RollbackRetentionPlanner.resolve(
                Optional.of(20),
                432000,
                0.05,
                7_776_000,
                true,
                40,
                true,
                120,
                true,
                Optional.of(7_776_000L),
                true,
                2160);

        assertTrue(settings.umbrellaEnabled());
        assertEquals(7_776_000, settings.utxoRollbackWindow());
        assertEquals(40, settings.accountStateEpochBlockDataRetentionLag());
        assertEquals(120, settings.accountStateSnapshotRetentionEpochs());
        assertEquals(7_776_000L, settings.accountHistoryRollbackSafetySlots().orElseThrow());
        assertEquals(864_000, settings.blockBodyPruneDepth());
    }

    @Test
    void negativeEpochsAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RollbackRetentionPlanner.resolve(
                        Optional.of(-1),
                        432000,
                        0.05,
                        4320,
                        false,
                        5,
                        false,
                        50,
                        false,
                        Optional.empty(),
                        false,
                        0));

        assertEquals("yano.rollback-retention-epochs must be >= 0", error.getMessage());
    }

    @Test
    void minimumBlockBodyPruneDepthUsesActiveSlotsCoeffAndSafetyMultiplier() {
        assertEquals(216_000,
                RollbackRetentionPlanner.computeMinimumBlockBodyPruneDepth(5, 432_000, 0.05));
    }

    @Test
    void minimumBlockBodyPruneDepthFallsBackToEpochLengthWhenActiveSlotsCoeffInvalid() {
        assertEquals(864_000,
                RollbackRetentionPlanner.computeMinimumBlockBodyPruneDepth(1, 432_000, 0));
    }
}
