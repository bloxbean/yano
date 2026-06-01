package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    void runtimeEvaluatorUsesDynamicSlotConfigSupplierPerCall() {
        var zeroTime = new AtomicLong(1_780_000_000_000L);
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> new ProtocolParams(),
                null,
                () -> {
                    throw new IllegalStateException("zeroTime " + zeroTime.get());
                },
                0,
                () -> 123L);

        var first = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));
        zeroTime.set(1_780_000_001_000L);
        var second = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("zeroTime 1780000000000", first.getMessage());
        assertEquals("zeroTime 1780000001000", second.getMessage());
    }

    @Test
    void scalusSlotConfigAdapterPreservesUnitsAndOrder() {
        var ccl = new SlotConfig(2_000, 42, 1_780_000_000_000L);

        var scalus = SlotConfigAdapters.toScalus(ccl);

        assertEquals(1_780_000_000_000L, scalus.zeroTime());
        assertEquals(42L, scalus.zeroSlot());
        assertEquals(2_000L, scalus.slotLength());
    }
}
