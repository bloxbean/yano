package com.example.catalog;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginApiVersion;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;
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
    private static final String DOMAIN_PROVIDER_CLASS =
            "com.example.appchain.CounterDomainApiProvider";
    private static final String HEALTH_PROVIDER_CLASS =
            "com.example.appchain.CounterHealthProvider";
    private static final String METRICS_PROVIDER_CLASS =
            "com.example.appchain.CounterMetricsProvider";
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
                if (environment.catalog().pluginApiMajor()
                        != PluginApiVersion.CURRENT_MAJOR
                        || environment.catalog().pluginApiLevel()
                        != PluginApiVersion.CURRENT_LEVEL) {
                    throw new IllegalStateException(
                            "Catalog host API major/level does not match the scaffold contract");
                }
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

                DomainApiProvider domainProvider = environment.providers()
                        .find(DomainApiProvider.class, BUNDLE_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "Catalog cannot resolve the domain API provider"));
                try (DomainApi domainApi = domainProvider.create(
                        new DomainApiContext(Map.of(), unavailableQueries()))) {
                    if (!BUNDLE_ID.equals(domainProvider.id())
                            || domainApi.routes().size() != 4) {
                        throw new IllegalStateException(
                                "Catalog provider constructed an invalid domain API");
                    }
                }

                PluginHealthProvider healthProvider = environment.providers()
                        .find(PluginHealthProvider.class, BUNDLE_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "Catalog cannot resolve the health provider"));
                try (PluginHealthSource health = healthProvider.create(
                        new PluginHealthContext(BUNDLE_ID, Map.of()))) {
                    if (health.checks().size() != 1
                            || health.snapshot().reports().getFirst().status()
                            != PluginHealthStatus.UP) {
                        throw new IllegalStateException(
                                "Catalog provider constructed invalid health telemetry");
                    }
                }

                PluginMetricsProvider metricsProvider = environment.providers()
                        .find(PluginMetricsProvider.class, BUNDLE_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "Catalog cannot resolve the metrics provider"));
                try (PluginMetricsSource metrics = metricsProvider.create(
                        new PluginMetricsContext(BUNDLE_ID, Map.of()))) {
                    if (metrics.descriptors().size() != 1
                            || metrics.snapshot().values().size() != 1) {
                        throw new IllegalStateException(
                                "Catalog provider constructed invalid custom metrics");
                    }
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
                || bundle.contributions().size() != 4
                || bundle.contributions().stream().noneMatch(contribution ->
                        PROVIDER_CLASS.equals(contribution.providerClass()))
                || bundle.contributions().stream().noneMatch(contribution ->
                        DOMAIN_PROVIDER_CLASS.equals(contribution.providerClass()))
                || bundle.contributions().stream().noneMatch(contribution ->
                        HEALTH_PROVIDER_CLASS.equals(contribution.providerClass()))
                || bundle.contributions().stream().noneMatch(contribution ->
                        METRICS_PROVIDER_CLASS.equals(contribution.providerClass()))) {
            throw new IllegalStateException(
                    "Scaffold bundle inventory does not match its deployment contract");
        }
    }

    private static DomainQueryService unavailableQueries() {
        return new DomainQueryService() {
            @Override
            public java.util.List<String> chainIds() {
                return java.util.List.of();
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                throw new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                        "catalog probe has no app chain");
            }
        };
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
