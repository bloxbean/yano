package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusBasedTransactionEvaluatorTest {

    private final SlotConfig slotConfig = new SlotConfig(1000, 0, 0);

    @Test
    void runtimeEvaluatorRejectsNegativeCurrentSlot() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new AssertionError("protocol parameters should not be requested");
                },
                null, slotConfig, 0, () -> -1L);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("Failed to resolve current slot from runtime", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("current slot supplier returned -1"));
    }

    @Test
    void runtimeEvaluatorRejectsCurrentSlotSupplierFailure() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new AssertionError("protocol parameters should not be requested");
                },
                null, slotConfig, 0, () -> {
            throw new IllegalStateException("tip unavailable");
        });

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("Failed to resolve current slot from runtime", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("tip unavailable"));
    }

    @Test
    void runtimeEvaluatorPassesResolvedSlotToProtocolParamsSupplier() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new IllegalStateException("resolved slot " + slot);
                },
                null, slotConfig, 0, () -> 123L);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("resolved slot 123", ex.getMessage());
    }

    @Test
    void evaluatorWithoutRuntimeSupplierKeepsLegacySlotZero() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new IllegalStateException("resolved slot " + slot);
                },
                null, slotConfig, 0, null);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("resolved slot 0", ex.getMessage());
    }
}
