package com.bloxbean.cardano.yano.ledgerrules.impl;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AikenTxEvaluatorTest {

    @Test
    void evaluateUsesDynamicSlotConfigSupplierPerCall() {
        var zeroTime = new AtomicLong(1_780_000_000_000L);
        var evaluator = new AikenTxEvaluator(
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
}
