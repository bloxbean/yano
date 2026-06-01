package com.bloxbean.cardano.yano.ledgerrules.impl;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JulcTxEvaluatorTest {

    @Test
    void evaluateUsesDynamicSlotConfigSupplierPerCall() {
        var zeroTime = new AtomicLong(1_780_000_000_000L);
        var evaluator = new JulcTxEvaluator(
                ProtocolParams::new,
                null,
                () -> {
                    throw new IllegalStateException("zeroTime " + zeroTime.get());
                });

        var first = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));
        zeroTime.set(1_780_000_001_000L);
        var second = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("zeroTime 1780000000000", first.getMessage());
        assertEquals("zeroTime 1780000001000", second.getMessage());
    }

    @Test
    void julcSlotConfigAdapterPreservesUnitsAndOrder() {
        var ccl = new SlotConfig(2_000, 42, 1_780_000_000_000L);

        var julc = JulcTxEvaluator.toJulcSlotConfig(ccl);

        assertEquals(42L, julc.zeroSlot());
        assertEquals(1_780_000_000_000L, julc.zeroSlotPosixMs());
        assertEquals(2_000L, julc.slotLengthMs());
    }
}
