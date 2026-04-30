package com.bloxbean.cardano.yano.app.api.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationResourceTest {

    @Test
    void failureMessageKeepsPublicMessageStable() {
        IllegalStateException error = new IllegalStateException("Failed to resolve current slot from runtime",
                new IllegalStateException("current slot supplier returned -1"));

        assertEquals("Failed to resolve current slot from runtime",
                EvaluationResource.failureMessage(error));
    }

    @Test
    void failureMessageUnwrapsRuntimeSlotFailureFromEvaluatorWrapper() {
        RuntimeException error = new RuntimeException("Error evaluating transaction",
                new IllegalStateException("Failed to resolve current slot from runtime",
                        new IllegalStateException("current slot supplier returned -1")));

        assertEquals("Failed to resolve current slot from runtime",
                EvaluationResource.failureMessage(error));
    }
}
