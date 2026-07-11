package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyValidationPipelineTest {
    @Test
    void pipelineRunsInOrderAndStopsOnFirstRejection() {
        AtomicInteger calls = new AtomicInteger();
        BodyValidator first = context -> {
            calls.incrementAndGet();
            return BodyValidationResult.accepted("first");
        };
        BodyValidator second = context -> {
            calls.incrementAndGet();
            return BodyValidationResult.rejected("second", "policy", "blocked");
        };
        BodyValidator third = context -> {
            calls.incrementAndGet();
            return BodyValidationResult.accepted("third");
        };

        BodyValidationResult result = BodyValidationPipeline.of(List.of(first, second, third))
                .validate(context());

        assertFalse(result.accepted());
        assertEquals("second", result.validatorId());
        assertEquals("policy", result.stage());
        assertEquals(2, calls.get());
    }

    @Test
    void pipelineRejectsWhenValidatorThrows() {
        BodyValidator throwing = context -> {
            throw new IllegalStateException("boom");
        };

        BodyValidationResult result = BodyValidationPipeline.of(List.of(throwing))
                .validate(context());

        assertFalse(result.accepted());
        assertEquals("validator-error", result.stage());
        assertEquals("boom", result.reason());
    }

    @Test
    void contextDefensivelyCopiesBlockBytes() {
        byte[] bytes = {1, 2, 3};
        BodyValidationContext context = new BodyValidationContext(
                Era.Shelley,
                Block.builder().build(),
                List.of(),
                bytes,
                10,
                1,
                "abcd");

        bytes[0] = 9;
        byte[] returned = context.blockBytes();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, context.blockBytes());
    }

    @Test
    void emptyPipelineAcceptsWithNoneValidator() {
        BodyValidationResult result = BodyValidationPipeline.of(List.of()).validate(context());

        assertTrue(result.accepted());
        assertEquals("none", result.validatorId());
    }

    private static BodyValidationContext context() {
        return new BodyValidationContext(
                Era.Shelley,
                Block.builder().build(),
                List.of(),
                new byte[] {1, 2, 3},
                10,
                1,
                "abcd");
    }
}
