package com.bloxbean.cardano.yano.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
