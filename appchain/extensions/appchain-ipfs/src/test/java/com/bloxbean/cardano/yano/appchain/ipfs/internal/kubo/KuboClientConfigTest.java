package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KuboClientConfigTest {
    @Test
    void normalizesAnOriginAndRedactsEndpointAndBearerFromDiagnostics() {
        KuboClientConfig config = new KuboClientConfig(
                URI.create("HTTP://LOCALHOST:5001/"),
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(1),
                Optional.of("secret.token-123"));

        assertThat(config.apiEndpoint()).isEqualTo(URI.create("http://localhost:5001"));
        assertThat(config.bearerToken()).contains("secret.token-123");
        assertThat(config.toString())
                .contains("apiEndpoint=<redacted>", "bearerToken=<redacted>")
                .doesNotContain("localhost", "5001", "secret.token-123");
    }

    @Test
    void rejectsEndpointFeaturesThatCouldSelectAnArbitraryRpcUrl() {
        List<String> invalid = List.of(
                "ftp://localhost:5001",
                "http://user@localhost:5001",
                "http://localhost:5001/api/v0",
                "http://localhost:5001?arg=other",
                "http://localhost:5001#fragment",
                "http://localhost:0",
                "relative");

        for (String endpoint : invalid) {
            assertThatIllegalArgumentException().isThrownBy(() -> config(endpoint));
        }
    }

    @Test
    void rejectsUnboundedOrNonPositiveTimeouts() {
        for (Duration timeout : List.of(
                Duration.ZERO, Duration.ofNanos(-1), Duration.ofMinutes(11))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new KuboClientConfig(
                    URI.create("http://localhost:5001"), timeout,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Optional.empty()));
        }
    }

    @Test
    void rejectsBlankControlBearingOrOversizedBearerTokens() {
        for (String token : List.of("", "has space", "line\r\nbreak", "x".repeat(2_049))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new KuboClientConfig(
                    URI.create("http://localhost:5001"), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Optional.of(token)));
        }
    }

    private static KuboClientConfig config(String endpoint) {
        return new KuboClientConfig(URI.create(endpoint), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Optional.empty());
    }
}
