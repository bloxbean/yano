package org.yanoproject.runtime.plugins;

import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import org.yanoproject.api.appchain.effects.AppEffectExecutorFactory;
import org.yanoproject.api.appchain.l1view.L1EpochObserverProvider;
import org.yanoproject.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.api.appchain.sequencer.SequencerModeProvider;
import org.yanoproject.api.appchain.signer.SignerProviderFactory;
import org.yanoproject.api.appchain.sink.FinalizedStreamSinkFactory;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.api.plugin.domain.LocalReadModelProvider;
import org.yanoproject.api.plugin.operations.PluginHealthProvider;
import org.yanoproject.api.plugin.operations.PluginMetricsProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Isolated JVM provider-activation check used by the generated bundle matrix. */
class RuntimePluginBundleActivationTest {
    private static final List<Class<?>> PROVIDER_TYPES = List.of(
            AppStateMachineProvider.class,
            AuthenticatedMapValueValidatorFactory.class,
            SequencerModeProvider.class,
            L1EpochObserverProvider.class,
            L1ObserverProvider.class,
            SignerProviderFactory.class,
            AppEffectExecutorFactory.class,
            FinalizedStreamSinkFactory.class,
            DomainApiProvider.class,
            LocalReadModelProvider.class,
            PluginHealthProvider.class,
            PluginMetricsProvider.class);

    @Test
    void activatesEveryManifestedProviderFromOneIsolatedBundle() throws Exception {
        assumeTrue(System.getProperty("yano.bundle.paths") != null,
                "run through a generated runtime-bundle activation task");
        List<Path> bundles = java.util.Arrays.stream(
                        System.getProperty("yano.bundle.paths").split(
                                java.util.regex.Pattern.quote(
                                        System.getProperty("path.separator"))))
                .map(Path::of)
                .toList();
        String bundleId = System.getProperty("yano.bundle.id");
        Set<String> expectedBundleIds = Set.of(
                System.getProperty("yano.bundle.expected-ids").split(","));
        assertThat(bundles).isNotEmpty().allMatch(Files::isRegularFile);
        assertThat(bundleId).isNotBlank();

        Path directory = Files.createTempDirectory("yano-bundle-activation-");
        try {
            for (int index = 0; index < bundles.size(); index++) {
                Files.copy(bundles.get(index), directory.resolve("plugin-" + index + ".jar"));
            }
            PluginsOptions options = new PluginsOptions(
                    true, false, expectedBundleIds, Set.of(), Map.of());
            try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                    options,
                    PluginLoaderHandle.directory(directory,
                            RuntimePluginBundleActivationTest.class.getClassLoader()))) {
                assertThat(environment.selectedBundleIds())
                        .containsExactlyInAnyOrderElementsOf(expectedBundleIds);
                assertThat(environment.catalog().bundles())
                        .extracting(bundleDescriptor -> bundleDescriptor.id())
                        .contains(bundleId);
                int activated = 0;
                for (Class<?> providerType : PROVIDER_TYPES) {
                    activated += activate(environment, providerType);
                }
                assertThat(activated).as("manifested provider count").isPositive();
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
            }
        }
    }

    private static <P> int activate(
            PluginRuntimeEnvironment environment,
            Class<P> providerType
    ) {
        int count = 0;
        for (String name : environment.providers().names(providerType)) {
            assertThat(environment.providers().find(providerType, name))
                    .as("%s:%s", providerType.getSimpleName(), name)
                    .isPresent();
            count++;
        }
        return count;
    }
}
