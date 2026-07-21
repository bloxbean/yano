package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.stock.CompositeStockPresets;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.catalog.PluginIndexCodec;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Produces current JVM catalog provenance for the native-binary smoke test. */
public final class PluginCatalogProvenanceProbe {
    private PluginCatalogProvenanceProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one provenance output path");
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = loader.getResources(PluginIndex.RESOURCE_PATH);
        List<URL> indexes = new ArrayList<>();
        while (resources.hasMoreElements()) {
            indexes.add(resources.nextElement());
        }
        if (indexes.size() != 1) {
            throw new IllegalStateException("Expected exactly one current aggregate index, found "
                    + indexes.size());
        }

        byte[] encodedIndex;
        try (InputStream input = indexes.getFirst().openStream()) {
            encodedIndex = input.readNBytes(PluginIndexCodec.MAX_INDEX_BYTES + 1);
        }
        if (encodedIndex.length > PluginIndexCodec.MAX_INDEX_BYTES) {
            throw new IllegalStateException("Current aggregate index exceeds the codec limit");
        }

        String fingerprint;
        try (PluginRuntimeEnvironment environment =
                     PluginRuntimeEnvironment.packagedClasspath(PluginsOptions.defaults(), loader)) {
            fingerprint = environment.catalog().fingerprint();
        }

        CompositeStateMachine stockComposite = CompositeStockPresets.create(
                new AppStateMachineContext() {
                    @Override public String chainId() { return "conformance-chain"; }
                    @Override public Map<String, String> settings() {
                        return Map.of("machines.composite.preset",
                                CompositeStockPresets.EVIDENCE_V1_GATED);
                    }
                    @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                        return Optional.of(AppTestConsensusProfiles.enabledEffects(128, 16_384));
                    }
                });
        String stockCompositeDigest = "sha256:" + HexFormat.of().formatHex(
                stockComposite.profile().digest());

        String content = "catalogFingerprint=" + fingerprint + "\n"
                + "indexSha256=" + sha256(encodedIndex) + "\n"
                + "compositeProfileDigest=" + stockCompositeDigest + "\n";
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
