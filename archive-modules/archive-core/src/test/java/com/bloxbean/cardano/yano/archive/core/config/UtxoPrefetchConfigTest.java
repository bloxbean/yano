package com.bloxbean.cardano.yano.archive.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-038 Phase 2c: prefetch settings are opt-in, literal and validated. */
class UtxoPrefetchConfigTest {

    @Test
    void defaultIsSerialAndOptIn() {
        UtxoPrefetchConfig defaults = UtxoPrefetchConfig.disabled();
        assertThat(defaults.enabled()).as("serial until mainnet validation").isFalse();
        assertThat(defaults.parallelism()).isEqualTo(1);
        assertThat(defaults.maxInFlightBlocks()).isPositive();
        assertThat(defaults.maxInFlightBytes()).isGreaterThanOrEqualTo(defaults.estimatedBytesPerBlock());
        assertThat(defaults.drainTimeout()).isPositive();
    }

    @Test
    void acceptsAnExplicitLiteralParallelism() {
        var config = new UtxoPrefetchConfig(true, 2, 8, 64L * 1024 * 1024, 2L * 1024 * 1024,
                Duration.ofSeconds(120));
        assertThat(config.enabled()).isTrue();
        assertThat(config.parallelism()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveParallelism() {
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 0, 8, 4096, 1024, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parallelism must be positive");
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 1, 0, 4096, 1024, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-in-flight-blocks");
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 1, 8, 4096, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("estimated-bytes-per-block");
    }

    @Test
    void rejectsABudgetThatCannotAdmitOneBlock() {
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 1, 8, 512, 1024, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one block");
    }

    @Test
    void rejectsNonPositiveDrainTimeout() {
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 1, 8, 4096, 1024, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drain-timeout");
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 1, 8, 4096, 1024, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("drain-timeout");
    }

    @Test
    void rejectsParallelismExceedingTheInFlightBound() {
        assertThatThrownBy(() -> new UtxoPrefetchConfig(true, 8, 4, 64 * 1024, 1024, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed max-in-flight-blocks");
    }
}
