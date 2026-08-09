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
                UiExtensionPermission.APP_CHAIN_ANCHOR_READ);
        assertThat(provider.assets().assets()).extracting(asset -> asset.path())
                .containsExactlyInAnyOrder("index.html", "assets/app.css",
                        "assets/app.js", "assets-manifest.json");
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
        assertThatThrownBy(() -> provider.assetBytes("../application.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
