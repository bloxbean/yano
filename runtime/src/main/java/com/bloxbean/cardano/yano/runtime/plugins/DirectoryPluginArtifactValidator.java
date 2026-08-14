package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.catalog.BundleContribution;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves provider types from one captured directory artifact at a time.
 *
 * <p>This preserves ServiceLoader/type/origin validation for every installed
 * artifact without ever combining an unselected artifact with the executable
 * loader. Provider constructors and static initializers are not invoked.</p>
 *
 * <p>The supplied parent is trusted application code. Parent-first API identity
 * is intentional and is not class/resource isolation from that parent.</p>
 */
final class DirectoryPluginArtifactValidator {

    private static final int MAX_PROVIDER_DIAGNOSTIC_IDENTITIES = 2;
    private static final int MAX_PROVIDER_DIAGNOSTIC_IDENTITY_LENGTH = 96;

    private static final Set<String> SUPPORTED_SERVICE_RESOURCES =
            java.util.Arrays.stream(ContributionKind.values())
                    .map(kind -> "META-INF/services/" + kind.serviceType().getName())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private DirectoryPluginArtifactValidator() {
    }

    static void validate(
            Path artifact,
            PluginIndex index,
            ClassLoader parent,
            DeploymentValidationBudget deploymentBudget
    ) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(deploymentBudget, "deploymentBudget");

        Map<ContributionKind, Set<String>> expected = expectedProviders(index);
        try (ArtifactValidationLoader loader = new ArtifactValidationLoader(
                artifact.toUri().toURL(), parent)) {
            PluginThreadContext.run(loader, () -> validateInContext(
                    artifact, expected, loader, deploymentBudget));
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Directory plugin artifact provider types could not be validated", failure);
        }
    }

    private static Map<ContributionKind, Set<String>> expectedProviders(PluginIndex index) {
        Map<ContributionKind, Set<String>> expected = new EnumMap<>(ContributionKind.class);
        for (ContributionKind kind : ContributionKind.values()) {
            expected.put(kind, new TreeSet<>());
        }
        index.bundles().forEach(bundle -> {
            for (BundleContribution contribution : bundle.manifest().contributions()) {
                expected.get(contribution.kind()).add(contribution.provider());
            }
        });
        index.legacyProviders().forEach(provider ->
                expected.get(provider.kind()).add(provider.provider()));
        Map<ContributionKind, Set<String>> snapshot = new EnumMap<>(ContributionKind.class);
        expected.forEach((kind, providers) -> snapshot.put(kind, Set.copyOf(providers)));
        return Collections.unmodifiableMap(snapshot);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateInContext(
            Path artifact,
            Map<ContributionKind, Set<String>> expected,
            ArtifactValidationLoader loader,
            DeploymentValidationBudget deploymentBudget
    ) {
        int artifactDiscovered = 0;
        for (ContributionKind kind : ContributionKind.values()) {
            Set<String> actual = new HashSet<>();
            Iterator<? extends ServiceLoader.Provider<?>> providers =
                    (Iterator) ServiceLoader.load(
                            (Class) kind.serviceType(), loader).stream().iterator();
            while (providers.hasNext()) {
                if (artifactDiscovered == PluginCatalogBuilder.MAX_DISCOVERED_PROVIDERS) {
                    throw new IllegalStateException(
                            "Directory artifact provider discovery exceeds the per-artifact limit of "
                                    + PluginCatalogBuilder.MAX_DISCOVERED_PROVIDERS);
                }
                deploymentBudget.recordProvider();
                artifactDiscovered++;
                ServiceLoader.Provider<?> provider = Objects.requireNonNull(
                        providers.next(), "ServiceLoader returned a null provider handle");
                Class<?> providerType = Objects.requireNonNull(
                        provider.type(), "ServiceLoader provider returned a null type");
                verifyOrigin(artifact, kind, providerType);
                if (!actual.add(providerType.getName())) {
                    throw new IllegalStateException("Directory artifact repeats ServiceLoader provider '"
                            + providerType.getName() + "' for " + kind.manifestKey());
                }
            }
            if (!actual.equals(expected.get(kind))) {
                Set<String> missing = new TreeSet<>(expected.get(kind));
                missing.removeAll(actual);
                Set<String> unexpected = new TreeSet<>(actual);
                unexpected.removeAll(expected.get(kind));
                throw new IllegalStateException("Directory artifact provider-type correlation failed for "
                        + kind.manifestKey() + "; missing=" + providerSummary(missing)
                        + ", unexpected=" + providerSummary(unexpected));
            }
        }
    }

    /** One shared bound for isolated provider-type traversal across a deployment. */
    static final class DeploymentValidationBudget {
        private final int maximumProviders;
        private int discoveredProviders;

        DeploymentValidationBudget(int maximumProviders) {
            if (maximumProviders < 1) {
                throw new IllegalArgumentException("maximumProviders must be positive");
            }
            this.maximumProviders = maximumProviders;
        }

        void recordProvider() {
            if (discoveredProviders == maximumProviders) {
                throw new IllegalStateException(
                        "Directory provider type validation exceeds the deployment-wide limit of "
                                + maximumProviders + " across captured artifacts");
            }
            discoveredProviders++;
        }

        int discoveredProviders() {
            return discoveredProviders;
        }
    }

    private static String providerSummary(Set<String> providers) {
        StringBuilder summary = new StringBuilder("{count=")
                .append(providers.size()).append(", first=[");
        int shown = 0;
        for (String provider : providers) {
            if (shown == MAX_PROVIDER_DIAGNOSTIC_IDENTITIES) {
                break;
            }
            if (shown++ > 0) {
                summary.append(", ");
            }
            int retained = Math.min(provider.length(),
                    MAX_PROVIDER_DIAGNOSTIC_IDENTITY_LENGTH);
            summary.append(provider, 0, retained);
            if (retained < provider.length()) {
                summary.append("...");
            }
        }
        return summary.append("]}").toString();
    }

    private static void verifyOrigin(
            Path expectedArtifact,
            ContributionKind kind,
            Class<?> providerType
    ) {
        try {
            var codeSource = providerType.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new IllegalStateException("Directory plugin provider '"
                        + providerType.getName() + "' for " + kind.manifestKey()
                        + " has no verifiable code source");
            }
            URI location = codeSource.getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())
                    || !expectedArtifact.equals(Path.of(location).toRealPath())) {
                throw new IllegalStateException("Directory plugin provider '"
                        + providerType.getName() + "' for " + kind.manifestKey()
                        + " was not resolved from its captured artifact; parent-first shadowing "
                        + "is not allowed and cross-artifact resolution is not allowed");
            }
        } catch (URISyntaxException | IOException failure) {
            throw new IllegalStateException("Directory plugin provider '"
                    + providerType.getName() + "' code source could not be verified", failure);
        }
    }

    /** Hide parent service descriptors while retaining parent-first API identity. */
    private static final class ArtifactValidationLoader extends URLClassLoader {
        private ArtifactValidationLoader(URL artifact, ClassLoader parent) {
            super(new URL[]{artifact}, parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (SUPPORTED_SERVICE_RESOURCES.contains(name)) {
                return findResources(name);
            }
            return super.getResources(name);
        }
    }
}
