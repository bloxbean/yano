package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAsset;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAssetManifest;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionDescriptor;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionMountPoint;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionPermission;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionProvider;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiExtensionRegistryTest {
    @Test
    void validatesPublishesAndRemovesOneGeneration() {
        byte[] html = "<html></html>".getBytes();
        byte[] manifest = "{}".getBytes();
        FixtureProvider provider = new FixtureProvider(html, manifest, false);
        UiExtensionRegistry registry = new UiExtensionRegistry(new FixtureProviders(provider));

        registry.resume();

        assertThat(registry.catalog()).singleElement().satisfies(entry -> {
            assertThat(entry.bundleId()).isEqualTo("com.example.product");
            assertThat(entry.entrypointUrl()).contains(entry.assetsDigest());
            assertThat(entry.permissions()).containsExactly("app-chain.status.read");
        });
        var entry = registry.catalog().getFirst();
        assertThat(registry.asset(entry.bundleId(), entry.assetsDigest(), "index.html"))
                .get().extracting(value -> new String(value.bytes())).isEqualTo("<html></html>");
        assertThat(registry.asset(entry.bundleId(), entry.assetsDigest(), "../secret")).isEmpty();

        registry.stop();
        assertThat(registry.catalog()).isEmpty();
        assertThat(registry.asset(entry.bundleId(), entry.assetsDigest(), "index.html")).isEmpty();
    }

    @Test
    void digestMismatchFailsBeforePublication() {
        UiExtensionRegistry registry = new UiExtensionRegistry(
                new FixtureProviders(new FixtureProvider(
                        "<html></html>".getBytes(), "{}".getBytes(), true)));

        assertThatThrownBy(registry::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest mismatch");
        assertThat(registry.catalog()).isEmpty();
    }

    private static final class FixtureProvider implements UiExtensionProvider {
        private final Map<String, byte[]> bytes;
        private final UiExtensionAssetManifest assets;

        private FixtureProvider(byte[] html, byte[] manifest, boolean corruptDigest) {
            bytes = Map.of("index.html", html, "assets-manifest.json", manifest);
            assets = new UiExtensionAssetManifest(1, List.of(
                    asset("index.html", "text/html", html, corruptDigest),
                    asset("assets-manifest.json", "application/json", manifest, false)));
        }

        @Override public String id() { return "com.example.product:history"; }

        @Override
        public UiExtensionDescriptor descriptor() {
            return new UiExtensionDescriptor(1, "history", "History",
                    UiExtensionMountPoint.APP_CHAIN, "index.html", List.of("history-v1"),
                    List.of(UiExtensionPermission.APP_CHAIN_STATUS_READ),
                    "assets-manifest.json");
        }

        @Override public UiExtensionAssetManifest assets() { return assets; }

        @Override public byte[] assetBytes(String normalizedPath) { return bytes.get(normalizedPath).clone(); }
    }

    private static UiExtensionAsset asset(
            String path, String mediaType, byte[] bytes, boolean corruptDigest) {
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        if (corruptDigest) digest = "00".repeat(32);
        return new UiExtensionAsset(path, mediaType, bytes.length, digest);
    }

    private record FixtureProviders(UiExtensionProvider provider) implements PluginProviderRegistry {
        @Override
        public <P> Optional<P> find(Class<P> type, String selector) {
            if (type == UiExtensionProvider.class && provider.id().equals(selector)) {
                return Optional.of(type.cast(provider));
            }
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> type) {
            return type == UiExtensionProvider.class ? List.of(provider.id()) : List.of();
        }

        @Override
        public <P> Optional<String> contributionOwner(Class<P> type, String selector) {
            return type == UiExtensionProvider.class
                    ? Optional.of("com.example.product") : Optional.empty();
        }
    }
}
