package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAssetResponse;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionCatalogEntry;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionGateway;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiExtensionResourceTest {
    @Test
    void servesOnlyGatewayValidatedAssetsWithHardeningHeaders() {
        UiExtensionResource resource = new UiExtensionResource();
        resource.extensions = new FixtureGateway();

        try (var response = resource.asset("com.example.product", "ab".repeat(32), "index.html")) {
            assertThat(response.getMediaType().toString()).isEqualTo("text/html");
            assertThat(response.getHeaderString("Cache-Control")).contains("immutable");
            assertThat(response.getHeaderString("Content-Security-Policy"))
                    .contains("connect-src 'none'");
            assertThat(response.getHeaderString("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        assertThatThrownBy(() -> resource.asset(
                "com.example.product", "ab".repeat(32), "../secret"))
                .isInstanceOf(NotFoundException.class);
    }

    private static final class FixtureGateway implements UiExtensionGateway {
        @Override public List<UiExtensionCatalogEntry> catalog() { return List.of(); }

        @Override
        public Optional<UiExtensionAssetResponse> asset(
                String bundleId, String assetsDigest, String normalizedPath) {
            if ("index.html".equals(normalizedPath)) {
                return Optional.of(new UiExtensionAssetResponse(
                        "text/html", "ab".repeat(32), "ok".getBytes()));
            }
            return Optional.empty();
        }
    }
}
