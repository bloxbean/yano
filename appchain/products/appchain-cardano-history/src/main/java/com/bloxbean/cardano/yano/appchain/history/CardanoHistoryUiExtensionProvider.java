package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAsset;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionAssetManifest;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionDescriptor;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionMountPoint;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionPermission;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sandboxed Cardano History console shipped inside the product bundle. */
public final class CardanoHistoryUiExtensionProvider implements UiExtensionProvider {
    private static final String ROOT = "META-INF/yano/ui/";
    private static final Map<String, String> MEDIA = Map.of(
            "index.html", "text/html",
            "assets/app.css", "text/css",
            "assets/app.js", "application/javascript",
            "assets/mpf-verifier.js", "application/javascript",
            "assets-manifest.json", "application/json");
    private final Map<String, byte[]> bytes;
    private final UiExtensionAssetManifest assets;

    public CardanoHistoryUiExtensionProvider() {
        LinkedHashMap<String, byte[]> loaded = new LinkedHashMap<>();
        MEDIA.keySet().stream().sorted().forEach(path -> loaded.put(path, resource(path)));
        bytes = Map.copyOf(loaded);
        assets = new UiExtensionAssetManifest(1, bytes.entrySet().stream()
                .map(entry -> new UiExtensionAsset(entry.getKey(), MEDIA.get(entry.getKey()),
                        entry.getValue().length, sha256(entry.getValue())))
                .toList());
    }

    @Override
    public String id() {
        return CardanoHistoryProduct.BUNDLE_ID;
    }

    @Override
    public UiExtensionDescriptor descriptor() {
        return new UiExtensionDescriptor(1, "cardano-history", "Cardano History",
                UiExtensionMountPoint.APP_CHAIN, "index.html",
                List.of("l1-epoch-params-v1"),
                List.of(UiExtensionPermission.APP_CHAIN_STATUS_READ,
                        UiExtensionPermission.APP_CHAIN_DOMAIN_READ,
                        UiExtensionPermission.APP_CHAIN_PROOF_READ,
                        UiExtensionPermission.APP_CHAIN_ANCHOR_READ,
                        UiExtensionPermission.FILE_IMPORT,
                        UiExtensionPermission.FILE_EXPORT),
                "assets-manifest.json");
    }

    @Override
    public UiExtensionAssetManifest assets() {
        return assets;
    }

    @Override
    public byte[] assetBytes(String normalizedPath) {
        byte[] value = bytes.get(normalizedPath);
        if (value == null) throw new IllegalArgumentException("undeclared Cardano History UI asset");
        return value.clone();
    }

    private static byte[] resource(String path) {
        try (InputStream input = CardanoHistoryUiExtensionProvider.class.getClassLoader()
                .getResourceAsStream(ROOT + path)) {
            if (input == null) throw new IllegalStateException("missing Cardano History UI asset: " + path);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read Cardano History UI asset: " + path, failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
