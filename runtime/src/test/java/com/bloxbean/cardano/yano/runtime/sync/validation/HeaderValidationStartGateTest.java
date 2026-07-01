package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;
import com.bloxbean.cardano.yano.api.config.UpstreamValidationStartConfig;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderValidationStartGateTest {
    @Test
    void checkpointDefersBeforeAnchorAndDelegatesAfterAnchor() {
        AtomicInteger delegateCalls = new AtomicInteger();
        HeaderValidator delegate = acceptingDelegate(delegateCalls);
        HeaderValidator gated = HeaderValidationStartGate.wrap(
                delegate,
                UpstreamValidationConfig.builder()
                        .level("praos-lite")
                        .start(UpstreamValidationStartConfig.builder()
                                .mode("checkpoint")
                                .slot(100)
                                .hash("abcd")
                                .build())
                        .build(),
                era -> OptionalLong.empty());

        HeaderValidationResult before = gated.validateShelley(header(99, "before"), null);
        HeaderValidationResult atAnchor = gated.validateShelley(header(100, "abcd"), null);

        assertThat(before.accepted()).isTrue();
        assertThat(before.level()).isEqualTo("deferred-checkpoint");
        assertThat(atAnchor.accepted()).isTrue();
        assertThat(atAnchor.level()).isEqualTo("delegate");
        assertThat(delegateCalls.get()).isEqualTo(1);
    }

    @Test
    void checkpointRejectsHashMismatchAtAnchor() {
        HeaderValidator gated = HeaderValidationStartGate.wrap(
                acceptingDelegate(new AtomicInteger()),
                UpstreamValidationConfig.builder()
                        .level("praos-lite")
                        .start(UpstreamValidationStartConfig.builder()
                                .mode("checkpoint")
                                .slot(100)
                                .hash("abcd")
                                .build())
                        .build(),
                era -> OptionalLong.empty());

        HeaderValidationResult result = gated.validateShelley(header(100, "ffff"), null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.stage()).isEqualTo("validation-start");
        assertThat(result.reason()).contains("checkpoint hash mismatch");
    }

    @Test
    void eraModeDefersUntilEraStartSlotIsKnownAndReached() {
        AtomicInteger delegateCalls = new AtomicInteger();
        HeaderValidator gated = HeaderValidationStartGate.wrap(
                acceptingDelegate(delegateCalls),
                UpstreamValidationConfig.builder()
                        .level("praos-lite")
                        .start(UpstreamValidationStartConfig.builder()
                                .mode("era")
                                .era("conway")
                                .build())
                        .build(),
                era -> OptionalLong.of(200));

        HeaderValidationResult before = gated.validateShelley(header(199, "before"), null);
        HeaderValidationResult after = gated.validateShelley(header(200, "after"), null);

        assertThat(before.level()).isEqualTo("deferred-era");
        assertThat(after.level()).isEqualTo("delegate");
        assertThat(delegateCalls.get()).isEqualTo(1);
    }

    private static HeaderValidator acceptingDelegate(AtomicInteger calls) {
        return new HeaderValidator() {
            @Override
            public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
                calls.incrementAndGet();
                return HeaderValidationResult.accepted("delegate");
            }

            @Override
            public HeaderValidationSnapshot snapshot() {
                return new HeaderValidationSnapshot("delegate", calls.get(), 0, null, null);
            }
        };
    }

    private static BlockHeader header(long slot, String hash) {
        return BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(slot)
                        .blockNumber(slot)
                        .blockHash(hash)
                        .build())
                .build();
    }
}
