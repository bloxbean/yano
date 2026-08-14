package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReadModelRegistryTest {
    @Test
    void registrationIsChainScopedAndRemovalReturnsUnavailable() throws Exception {
        try (LocalReadModelRegistry registry = new LocalReadModelRegistry()) {
            AutoCloseable registration = registry.register(
                    "model", "chain-a",
                    (operation, request) -> new LocalReadModelResult(
                            LocalReadModelResult.Status.READY,
                            operation.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            4, 4, "FULL", ""));

            assertThat(registry.query(
                    "model", "chain-a", "status", new byte[0]).status())
                    .isEqualTo(LocalReadModelResult.Status.READY);
            assertThat(registry.query(
                    "model", "chain-b", "status", new byte[0]).status())
                    .isEqualTo(LocalReadModelResult.Status.UNAVAILABLE);
            assertThatThrownBy(() -> registry.register(
                    "model", "chain-a",
                    (operation, request) -> LocalReadModelResult.unavailable()))
                    .isInstanceOf(IllegalStateException.class);

            registration.close();
            assertThat(registry.query(
                    "model", "chain-a", "status", new byte[0]).status())
                    .isEqualTo(LocalReadModelResult.Status.UNAVAILABLE);
        }
    }
}
