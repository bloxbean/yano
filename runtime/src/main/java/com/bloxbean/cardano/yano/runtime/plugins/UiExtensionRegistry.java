package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAsset;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAssetResponse;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionCatalogEntry;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionDescriptor;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionGateway;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionPermission;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionProvider;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Eagerly validates and snapshots active plugin UI assets into host-owned memory. */
public final class UiExtensionRegistry implements UiExtensionGateway, AutoCloseable {
    private final PluginProviderRegistry providers;
    private volatile Publication publication = Publication.empty();
    private boolean closed;

    public UiExtensionRegistry(PluginRuntimeEnvironment environment) {
        this(Objects.requireNonNull(environment, "environment").providers());
    }

    UiExtensionRegistry(PluginProviderRegistry providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public synchronized void resume() {
        if (closed) throw new IllegalStateException("UI extension registry is closed");
        Map<AssetKey, UiExtensionAssetResponse> assets = new LinkedHashMap<>();
        List<UiExtensionCatalogEntry> catalog = new ArrayList<>();
        for (String selector : providers.names(UiExtensionProvider.class)) {
            UiExtensionProvider provider = providers.require(UiExtensionProvider.class, selector);
            String bundleId = providers.contributionOwner(UiExtensionProvider.class, selector)
                    .orElseThrow(() -> new IllegalStateException(
                            "ui-extension contributions require a manifested owner"));
            UiExtensionDescriptor descriptor = provider.descriptor();
            if (!selector.equals(provider.id())) {
                throw new IllegalStateException("ui-extension selector changed after catalog activation");
            }
            var manifest = provider.assets();
            UiExtensionAsset entrypoint = manifest.require(descriptor.entrypoint());
            if (!"text/html".equals(entrypoint.mediaType())) {
                throw new IllegalStateException("ui-extension entrypoint must be text/html");
            }
            manifest.require(descriptor.assetsManifest());
            List<UiExtensionAsset> ordered = manifest.assets().stream()
                    .sorted(Comparator.comparing(UiExtensionAsset::path)).toList();
            String digest = assetSetDigest(ordered);
            for (UiExtensionAsset asset : ordered) {
                byte[] bytes = provider.assetBytes(asset.path());
                if (bytes.length != asset.size() || !asset.sha256Hex().equals(sha256(bytes))) {
                    throw new IllegalStateException("ui-extension asset size or digest mismatch");
                }
                AssetKey key = new AssetKey(bundleId, digest, asset.path());
                if (assets.put(key, new UiExtensionAssetResponse(
                        asset.mediaType(), asset.sha256Hex(), bytes)) != null) {
                    throw new IllegalStateException("duplicate UI asset namespace");
                }
            }
            String base = "/ui-plugins/" + bundleId + "/" + digest + "/";
            catalog.add(new UiExtensionCatalogEntry(
                    bundleId, descriptor.extensionId(), descriptor.title(),
                    descriptor.mountPoint().id(), descriptor.uiApiVersion(), digest,
                    base + descriptor.entrypoint(), descriptor.requiredCapabilities(),
                    descriptor.permissions().stream().map(UiExtensionPermission::id).toList()));
        }
        catalog.sort(Comparator.comparing(UiExtensionCatalogEntry::bundleId)
                .thenComparing(UiExtensionCatalogEntry::extensionId));
        publication = new Publication(List.copyOf(catalog), Map.copyOf(assets));
    }

    public synchronized void stop() {
        publication = Publication.empty();
    }

    @Override
    public List<UiExtensionCatalogEntry> catalog() {
        return publication.catalog();
    }

    @Override
    public Optional<UiExtensionAssetResponse> asset(
            String bundleId, String assetsDigest, String normalizedPath) {
        if (bundleId == null || assetsDigest == null || normalizedPath == null
                || normalizedPath.startsWith("/") || normalizedPath.contains("..")
                || normalizedPath.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        UiExtensionAssetResponse value = publication.assets().get(
                new AssetKey(bundleId, assetsDigest, normalizedPath));
        return Optional.ofNullable(value);
    }

    @Override
    public synchronized void close() {
        stop();
        closed = true;
    }

    private static String assetSetDigest(List<UiExtensionAsset> assets) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(buffer)) {
                output.writeInt(1);
                for (UiExtensionAsset asset : assets) {
                    writeString(output, asset.path());
                    writeString(output, asset.mediaType());
                    output.writeLong(asset.size());
                    writeString(output, asset.sha256Hex());
                }
            }
            return sha256(buffer.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("could not encode UI asset inventory", impossible);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record AssetKey(String bundleId, String assetsDigest, String path) { }

    private record Publication(
            List<UiExtensionCatalogEntry> catalog,
            Map<AssetKey, UiExtensionAssetResponse> assets
    ) {
        private static Publication empty() { return new Publication(List.of(), Map.of()); }
    }
}
