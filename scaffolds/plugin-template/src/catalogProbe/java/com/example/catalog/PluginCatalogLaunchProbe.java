package com.example.catalog;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/** Process-level conformance probe for the template's deployable plugin JAR. */
public final class PluginCatalogLaunchProbe {
    private static final String BUNDLE_ID = "com.example.appchain.counter";
    private static final String PROVIDER_CLASS =
            "com.example.appchain.CounterStateMachineProvider";
    private static final String MACHINE_ID = "counter";

    private PluginCatalogLaunchProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Expected the plugin JAR path and bundle version");
        }
        Path sourceJar = Path.of(args[0]).toRealPath();
        String expectedVersion = args[1];
        Path pluginDirectory = Files.createTempDirectory(
                "yano-scaffold-catalog-probe-");
        try {
            Files.copy(sourceJar, pluginDirectory.resolve(sourceJar.getFileName()),
                    StandardCopyOption.COPY_ATTRIBUTES);
            PluginsOptions policy = new PluginsOptions(
                    true, false, Set.of(BUNDLE_ID), Set.of(), Map.of());
            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                    policy,
                    PluginLoaderHandle.directory(pluginDirectory,
                            PluginCatalogLaunchProbe.class.getClassLoader()))) {
                if (!environment.selectedBundleIds().equals(Set.of(BUNDLE_ID))) {
                    throw new IllegalStateException(
                            "Catalog did not select exactly the scaffold bundle");
                }
                PluginBundleInfo bundle = environment.catalog().bundles().stream()
                        .filter(candidate -> BUNDLE_ID.equals(candidate.id()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Scaffold bundle is absent from catalog inventory"));
                requireBundleMetadata(bundle, expectedVersion);

                AppStateMachineProvider provider = environment.providers()
                        .find(AppStateMachineProvider.class, MACHINE_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "Catalog cannot resolve the counter provider"));
                AppStateMachine machine = provider.create();
                if (!MACHINE_ID.equals(machine.id())) {
                    throw new IllegalStateException(
                            "Catalog provider constructed the wrong state machine");
                }
            }
        } finally {
            deleteTree(pluginDirectory);
        }
    }

    private static void requireBundleMetadata(
            PluginBundleInfo bundle,
            String expectedVersion
    ) {
        if (!bundle.selected()
                || !expectedVersion.equals(bundle.version())
                || bundle.source() != PluginSourceCategory.DIRECTORY
                || bundle.digestMode() != PluginDigestMode.JAR
                || bundle.contributions().size() != 1
                || !PROVIDER_CLASS.equals(
                        bundle.contributions().getFirst().providerClass())) {
            throw new IllegalStateException(
                    "Scaffold bundle inventory does not match its deployment contract");
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
