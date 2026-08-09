package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionPermission;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryUiExtensionProviderTest {
    @Test
    void packagesAClosedCapabilityAwareBridgeOnlyConsole() throws Exception {
        var provider = new CardanoHistoryUiExtensionProvider();
        var descriptor = provider.descriptor();

        assertThat(descriptor.requiredCapabilities()).containsExactly("l1-epoch-params-v1");
        assertThat(descriptor.permissions()).containsExactly(
                UiExtensionPermission.APP_CHAIN_STATUS_READ,
                UiExtensionPermission.APP_CHAIN_DOMAIN_READ,
                UiExtensionPermission.APP_CHAIN_PROOF_READ,
                UiExtensionPermission.APP_CHAIN_ANCHOR_READ,
                UiExtensionPermission.FILE_IMPORT,
                UiExtensionPermission.FILE_EXPORT);
        assertThat(provider.assets().assets()).extracting(asset -> asset.path())
                .containsExactlyInAnyOrder("index.html", "assets/app.css",
                        "assets/app.js", "assets/mpf-verifier.js", "assets-manifest.json");
        for (var asset : provider.assets().assets()) {
            byte[] bytes = provider.assetBytes(asset.path());
            assertThat(bytes).hasSize((int) asset.size());
            assertThat(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(asset.sha256Hex());
        }

        String javascript = new String(provider.assetBytes("assets/app.js"),
                StandardCharsets.UTF_8);
        assertThat(javascript).contains("parent.postMessage", "app-chain.domain",
                "app-chain.proof", "app-chain.anchor")
                .doesNotContain("fetch(", "XMLHttpRequest", "WebSocket(", "http://", "https://");
        String verifier = new String(provider.assetBytes("assets/mpf-verifier.js"),
                StandardCharsets.UTF_8);
        assertThat(verifier).contains("verifyInclusion", "verifyBundle", "mpf-blake2b256-v1")
                .doesNotContain("fetch(", "XMLHttpRequest", "WebSocket(", "http://", "https://");
        String html = new String(provider.assetBytes("index.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("data-capability=\"l1-epoch-stake-v1\"",
                "data-capability=\"l1-epoch-governance-v1\"", "id=\"import\"");
        assertThat(javascript).contains("value.key", "value.committedHeight", "value.profile",
                "querySelectorAll('[data-capability]')");
        assertThatThrownBy(() -> provider.assetBytes("../application.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
