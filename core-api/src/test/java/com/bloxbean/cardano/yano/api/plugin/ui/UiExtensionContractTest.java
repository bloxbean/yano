package com.bloxbean.cardano.yano.api.plugin.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiExtensionContractTest {
    private static final String DIGEST = "ab".repeat(32);

    @Test
    void acceptsTheFrozenV1CardanoHistoryShape() {
        var descriptor = new UiExtensionDescriptor(1, "cardano-history", "Cardano History",
                UiExtensionMountPoint.APP_CHAIN, "index.html",
                List.of("l1-epoch-params-v1"),
                List.of(UiExtensionPermission.APP_CHAIN_STATUS_READ,
                        UiExtensionPermission.APP_CHAIN_DOMAIN_READ,
                        UiExtensionPermission.APP_CHAIN_PROOF_READ,
                        UiExtensionPermission.APP_CHAIN_ANCHOR_READ),
                "assets-manifest.json");
        var manifest = new UiExtensionAssetManifest(1, List.of(
                new UiExtensionAsset("index.html", "text/html", 10, DIGEST),
                new UiExtensionAsset("assets/app.js", "application/javascript", 20, DIGEST)));

        assertThat(descriptor.uiApiVersion()).isEqualTo(1);
        assertThat(manifest.require(descriptor.entrypoint()).mediaType()).isEqualTo("text/html");
    }

    @Test
    void rejectsTraversalUnknownMediaAndDuplicatePaths() {
        assertThatThrownBy(() -> new UiExtensionAsset("../secret", "text/html", 1, DIGEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UiExtensionAsset("app.bin", "application/octet-stream", 1, DIGEST))
                .isInstanceOf(IllegalArgumentException.class);
        UiExtensionAsset asset = new UiExtensionAsset("index.html", "text/html", 1, DIGEST);
        assertThatThrownBy(() -> new UiExtensionAssetManifest(1, List.of(asset, asset)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permissionsAreClosedAndReadOnly() {
        assertThat(UiExtensionPermission.fromId("app-chain.proof.read"))
                .isEqualTo(UiExtensionPermission.APP_CHAIN_PROOF_READ);
        assertThatThrownBy(() -> UiExtensionPermission.fromId("app-chain.admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
