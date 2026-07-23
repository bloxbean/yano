package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginApiVersion;
import com.bloxbean.cardano.yano.api.plugin.PluginCapability;
import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.catalog.BundleContribution;
import com.bloxbean.cardano.yano.catalog.BundleDependency;
import com.bloxbean.cardano.yano.catalog.BundleManifest;
import com.bloxbean.cardano.yano.catalog.CatalogDigests;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.IndexedBundle;
import com.bloxbean.cardano.yano.catalog.IndexedLegacyProvider;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.catalog.SemVersion;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Validates policy/graph/provider correlation and publishes one immutable catalog. */
final class PluginCatalogBuilder {
    static final int PLUGIN_API_MAJOR = PluginApiVersion.CURRENT_MAJOR;
    static final int PLUGIN_API_LEVEL = PluginApiVersion.CURRENT_LEVEL;
    static final int MAX_CYCLE_DIAGNOSTIC_LENGTH = 512;
    private static final int MAX_CYCLE_ID_DISPLAY_LENGTH = 64;
    private static final String DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX =
            "Plugin bundle dependency cycle: ";
    private static final int MAX_LEGACY_ID_LENGTH = 160;
    private static final int MAX_LEGACY_SELECTOR_LENGTH = 128;
    private static final int MAX_LEGACY_VERSION_LENGTH = SemVersion.MAX_LENGTH;
    private static final int MAX_LEGACY_DEPENDENCIES = 256;
    /** Legacy compatibility hashes class bytes only; keep hostile resources bounded. */
    static final int MAX_LEGACY_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ZERO_PROGRESS_READS = 16;
    /** Bound the combined ServiceLoader traversal across every contribution kind. */
    static final int MAX_DISCOVERED_PROVIDERS = PluginIndex.MAX_PROVIDERS;
    private static final Logger log = LoggerFactory.getLogger(PluginCatalogBuilder.class);

    BuildResult build(PluginsOptions options,
                      ClassLoader loader,
                      List<CatalogInput> inputs) {
        return build(options, loader, inputs, true);
    }

    BuildResult build(PluginsOptions options,
                      ClassLoader loader,
                      List<CatalogInput> inputs,
                      boolean allowUnindexedLegacyProviders) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(inputs, "inputs");
        return PluginThreadContext.call(loader, () -> buildInPluginContext(
                options, loader, inputs, allowUnindexedLegacyProviders));
    }

    private BuildResult buildInPluginContext(PluginsOptions options,
                                             ClassLoader loader,
                                             List<CatalogInput> inputs,
                                             boolean allowUnindexedLegacyProviders) {
        if (!options.enabled()) {
            return emptyResult(loader);
        }

        validateProviderBudget(inputs, MAX_DISCOVERED_PROVIDERS);
        validateManifestCompatibility(inputs);
        Map<ProviderKey, ServiceLoader.Provider<?>> handles = discoverProviderHandles(loader);
        // Manifest declaration uniqueness is a catalog property, independent
        // of whether policy makes the provider executable on the terminal
        // loader. Keep it distinct from claimed, which tracks executable
        // ServiceLoader handles only.
        Set<ProviderKey> declaredManifestProviders = new HashSet<>();
        Set<ProviderKey> claimed = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        Map<String, IndexedLegacy> indexedLegacy = new TreeMap<>();
        List<Object> eagerLegacyInstances = new ArrayList<>();

        try {
            for (CatalogInput input : inputs) {
                for (IndexedBundle indexed : input.index().bundles()) {
                    BundleManifest manifest = indexed.manifest();
                    validateReservedNames(manifest);
                    boolean executableDirectoryBundle = input.source()
                            != PluginSourceCategory.DIRECTORY
                            || selectionStatus(options, manifest.id())
                            == PluginSelectionStatus.SELECTED;
                    List<CandidateContribution> contributions = new ArrayList<>();
                    for (BundleContribution contribution : manifest.contributions()) {
                        ProviderKey key = new ProviderKey(
                                contribution.kind(), contribution.provider());
                        if (!declaredManifestProviders.add(key)) {
                            throw new IllegalStateException("Provider '"
                                    + contribution.provider()
                                    + "' is declared by more than one manifest contribution");
                        }
                        String canonicalKey = key.kind() + "\u0000" + key.providerClass();
                        if (indexedLegacy.containsKey(canonicalKey)) {
                            throw new IllegalStateException("Provider '"
                                    + contribution.provider()
                                    + "' is represented as both manifested and legacy "
                                    + "across plugin catalog inputs");
                        }
                        Supplier<?> supplier;
                        if (executableDirectoryBundle) {
                            ServiceLoader.Provider<?> provider = handles.get(key);
                            if (provider == null) {
                                throw new IllegalStateException("Manifested provider '"
                                        + contribution.provider() + "' for bundle '" + manifest.id()
                                        + "' has no matching ServiceLoader entry");
                            }
                            verifyProviderOrigin(key, provider, input);
                            if (!claimed.add(key)) {
                                throw new IllegalStateException("Provider '"
                                        + contribution.provider()
                                        + "' is declared by more than one catalog input");
                            }
                            supplier = provider::get;
                        } else {
                            // The isolated directory-artifact validator already
                            // proved ServiceLoader type/origin correlation. The
                            // terminal shared loader deliberately has no URL for
                            // this policy-filtered artifact.
                            supplier = unselectedDirectoryProviderSupplier(
                                    manifest.id(), contribution.provider());
                        }
                        contributions.add(new CandidateContribution(contribution.kind(),
                                contribution.name(), contribution.provider(), supplier,
                                manifest, null));
                    }
                    candidates.add(Candidate.manifested(indexed, input.source(), contributions));
                }
                for (IndexedLegacyProvider legacy : input.index().legacyProviders()) {
                    ProviderKey key = new ProviderKey(legacy.kind(), legacy.provider());
                    if (declaredManifestProviders.contains(key)) {
                        throw new IllegalStateException("Provider '" + legacy.provider()
                                + "' is represented as both manifested and legacy "
                                + "across plugin catalog inputs");
                    }
                    ServiceLoader.Provider<?> provider = handles.get(key);
                    if (provider != null) {
                        verifyProviderOrigin(key, provider, input);
                    }
                    String canonicalKey = key.kind() + "\u0000" + key.providerClass();
                    IndexedLegacy previous = indexedLegacy.putIfAbsent(canonicalKey,
                            new IndexedLegacy(legacy, input.source()));
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate indexed legacy provider '"
                                + legacy.provider() + "'");
                    }
                }
            }

            // Legacy providers need one trusted construction to obtain selector metadata.
            // This intentionally happens before policy because their policy id is synthetic.
            for (Map.Entry<ProviderKey, ServiceLoader.Provider<?>> discovered : handles.entrySet()) {
                if (claimed.contains(discovered.getKey())) {
                    continue;
                }
                if (discovered.getKey().kind().manifestRequired()) {
                    throw new IllegalStateException("ServiceLoader provider '"
                            + discovered.getKey().providerClass() + "' for contribution kind '"
                            + discovered.getKey().kind().manifestKey()
                            + "' requires an owning bundle manifest");
                }
                IndexedLegacy indexed = indexedLegacy.get(discovered.getKey().kind() + "\u0000"
                        + discovered.getKey().providerClass());
                if (indexed == null && !allowUnindexedLegacyProviders) {
                    throw new IllegalStateException("ServiceLoader provider '"
                            + discovered.getKey().providerClass()
                            + "' is absent from the authoritative plugin aggregate index; "
                            + "packaged and native runtimes do not synthesize legacy entries");
                }
                Object instance = instantiateLegacy(
                        discovered.getKey(), discovered.getValue(), loader);
                eagerLegacyInstances.add(instance);
                Candidate legacy = legacyCandidate(discovered.getKey(), instance,
                        indexed != null ? indexed.provider().digest()
                                : legacyClassDigest(discovered.getValue().type()),
                        indexed != null ? indexed.provider().digestMode()
                                : PluginDigestMode.LEGACY_CLASS,
                        indexed != null ? indexed.source() : PluginSourceCategory.CLASSPATH,
                        loader);
                candidates.add(legacy);
                claimed.add(discovered.getKey());
            }
            for (IndexedLegacy indexed : indexedLegacy.values()) {
                ProviderKey key = new ProviderKey(
                        indexed.provider().kind(), indexed.provider().provider());
                if (!claimed.contains(key)) {
                    throw new IllegalStateException("Indexed legacy provider '" + key.providerClass()
                            + "' has no matching ServiceLoader entry");
                }
            }

            Map<String, Candidate> discovered = uniqueCandidates(candidates);
            Set<String> missingAllowed = new TreeSet<>(options.allowList());
            missingAllowed.removeAll(options.denyList());
            missingAllowed.removeAll(discovered.keySet());
            if (!missingAllowed.isEmpty()) {
                throw new IllegalStateException("Allow-listed plugin bundles were not discovered: "
                        + missingAllowed);
            }

            Map<String, Candidate> selected = new TreeMap<>();
            for (Candidate candidate : discovered.values()) {
                PluginSelectionStatus status = selectionStatus(options, candidate.id());
                candidate.selectionStatus(status);
                if (status == PluginSelectionStatus.SELECTED) {
                    selected.put(candidate.id(), candidate);
                }
            }
            validateSelectedDependencies(selected);
            validateSelectedContributionKeys(selected.values());
            List<Candidate> ordered = topologicalOrder(selected);

            List<CatalogPluginProviderRegistry.Entry> registryEntries = new ArrayList<>();
            for (Candidate candidate : ordered) {
                for (CandidateContribution contribution : candidate.contributions()) {
                    registryEntries.add(new CatalogPluginProviderRegistry.Entry(
                            candidate.id(), contribution.kind(), contribution.name(),
                            contribution.providerClass(), contribution.manifest(),
                            contribution.supplier(), contribution.nodePluginMetadata()));
                }
            }
            verifyDirectoryArtifactsUnchanged(inputs);
            Map<String, Map<String, Object>> scopedBundleConfigs = bundleConfigs(
                    options.config(), selected.values());

            List<String> order = ordered.stream().map(Candidate::id).toList();
            CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                    registryEntries, order, eagerLegacyInstances, loader);
            String fingerprint = fingerprint(ordered);
            List<PluginBundleInfo> inventory = discovered.values().stream()
                    .sorted(Comparator.comparing(Candidate::id))
                    .map(PluginCatalogBuilder::inventory)
                    .toList();
            PluginCatalogSnapshot snapshot = new PluginCatalogSnapshot(
                    PLUGIN_API_MAJOR, PLUGIN_API_LEVEL, fingerprint, inventory, order);
            warnLegacyBundles(discovered.values());
            return new BuildResult(snapshot, registry, order,
                    Collections.unmodifiableSet(new LinkedHashSet<>(selected.keySet())),
                    scopedBundleConfigs);
        } catch (Throwable failure) {
            closeEagerLegacyInstances(eagerLegacyInstances, failure, loader);
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Plugin catalog construction failed", failure);
        }
    }

    private static BuildResult emptyResult(ClassLoader loader) {
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(), List.of(), List.of(), loader);
        return new BuildResult(new PluginCatalogSnapshot(
                PLUGIN_API_MAJOR, PLUGIN_API_LEVEL,
                fingerprint(List.of()), List.of(), List.of()),
                registry, List.of(), Set.of(), Map.of());
    }

    private static PluginSelectionStatus selectionStatus(
            PluginsOptions options,
            String bundleId
    ) {
        if (options.denyList().contains(bundleId)) {
            return PluginSelectionStatus.DENIED;
        }
        if (!options.allowList().isEmpty()
                && !options.allowList().contains(bundleId)) {
            return PluginSelectionStatus.NOT_ALLOW_LISTED;
        }
        return PluginSelectionStatus.SELECTED;
    }

    private static Supplier<?> unselectedDirectoryProviderSupplier(
            String bundleId,
            String providerClass
    ) {
        return () -> {
            throw new IllegalStateException("Policy-filtered directory provider '"
                    + providerClass + "' for bundle '" + bundleId
                    + "' is not executable");
        };
    }

    private static Map<ProviderKey, ServiceLoader.Provider<?>> discoverProviderHandles(
            ClassLoader loader) {
        return discoverProviderHandles(loader, PluginCatalogBuilder::providerIterator,
                MAX_DISCOVERED_PROVIDERS);
    }

    /** Package-private seam proving the provider-evidence bound spans catalog inputs. */
    static void validateProviderBudget(
            List<CatalogInput> inputs,
            int maximumProviders
    ) {
        Objects.requireNonNull(inputs, "inputs");
        if (maximumProviders < 1) {
            throw new IllegalArgumentException("maximumProviders must be positive");
        }
        long discovered = 0;
        for (CatalogInput input : inputs) {
            PluginIndex index = Objects.requireNonNull(input, "inputs must not contain null").index();
            for (IndexedBundle bundle : index.bundles()) {
                discovered = addProviderEvidence(
                        discovered, bundle.manifest().contributions().size(), maximumProviders);
            }
            discovered = addProviderEvidence(
                    discovered, index.legacyProviders().size(), maximumProviders);
        }
    }

    static void validateManifestCompatibility(List<CatalogInput> inputs) {
        for (CatalogInput input : inputs) {
            for (IndexedBundle indexed : input.index().bundles()) {
                BundleManifest manifest = indexed.manifest();
                if (!manifest.yanoApi().supports(
                        PLUGIN_API_MAJOR, PLUGIN_API_LEVEL)) {
                    throw new IllegalStateException("Plugin bundle '" + manifest.id()
                            + "' does not support Yano plugin API major "
                            + PLUGIN_API_MAJOR + " level " + PLUGIN_API_LEVEL);
                }
            }
        }
    }

    private static long addProviderEvidence(
            long discovered,
            int additional,
            int maximumProviders
    ) {
        if (additional > maximumProviders - discovered) {
            throw new IllegalStateException("Plugin catalog provider evidence exceeds the global "
                    + "limit of " + maximumProviders
                    + " across manifested contributions and legacy providers");
        }
        return discovered + additional;
    }

    private static Map<ProviderKey, ServiceLoader.Provider<?>> discoverProviderHandles(
            ClassLoader loader,
            ProviderSource providerSource,
            int maximumProviders) {
        Objects.requireNonNull(providerSource, "providerSource");
        if (maximumProviders < 1) {
            throw new IllegalArgumentException("maximumProviders must be positive");
        }
        Map<ProviderKey, ServiceLoader.Provider<?>> handles = new TreeMap<>(
                Comparator.comparing((ProviderKey key) -> key.kind().manifestKey())
                        .thenComparing(ProviderKey::providerClass));
        int discovered = 0;
        for (ContributionKind kind : ContributionKind.values()) {
            try {
                Iterator<? extends ServiceLoader.Provider<?>> providers =
                        PluginThreadContext.call(loader,
                                () -> providerSource.providers(kind.serviceType(), loader));
                while (PluginThreadContext.call(loader, providers::hasNext)) {
                    if (discovered == maximumProviders) {
                        throw new IllegalStateException(
                                "ServiceLoader provider discovery exceeds the global limit of "
                                        + maximumProviders);
                    }
                    discovered++;
                    ServiceLoader.Provider<?> provider = Objects.requireNonNull(
                            PluginThreadContext.call(loader, providers::next),
                            "ServiceLoader returned a null provider handle");
                    Class<?> providerType = Objects.requireNonNull(
                            PluginThreadContext.call(loader, provider::type),
                            "ServiceLoader provider returned a null type");
                    ProviderKey key = new ProviderKey(kind, providerType.getName());
                    ServiceLoader.Provider<?> previous = handles.putIfAbsent(key, provider);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate ServiceLoader provider '"
                                + key.providerClass() + "' for " + kind.manifestKey());
                    }
                }
            } catch (Throwable failure) {
                LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                throw new IllegalStateException("ServiceLoader discovery failed for "
                            + kind.serviceType().getName(), failure);
            }
        }
        return handles;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Iterator<? extends ServiceLoader.Provider<?>> providerIterator(
            Class<?> type,
            ClassLoader loader
    ) {
        return (Iterator) ServiceLoader.load((Class) type, loader).stream().iterator();
    }

    /** Package-private seam proving that the provider bound spans all service kinds. */
    static void discoverProviderHandlesForTesting(ClassLoader loader,
                                                  ProviderSource providerSource,
                                                  int maximumProviders) {
        discoverProviderHandles(loader, providerSource, maximumProviders);
    }

    @FunctionalInterface
    interface ProviderSource {
        Iterator<? extends ServiceLoader.Provider<?>> providers(
                Class<?> type,
                ClassLoader loader
        );
    }

    private static Object instantiateLegacy(ProviderKey key,
                                            ServiceLoader.Provider<?> provider,
                                            ClassLoader loader) {
        try {
            return PluginThreadContext.call(loader,
                    () -> Objects.requireNonNull(provider.get(), "ServiceLoader returned null"));
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            throw new LegacyProviderConstructionException(key.providerClass(), failure);
        }
    }

    private static void verifyProviderOrigin(ProviderKey key,
                                             ServiceLoader.Provider<?> provider,
                                             CatalogInput input) {
        if (input.source() != PluginSourceCategory.DIRECTORY) {
            return;
        }
        Path expected = input.artifact();
        try {
            var codeSource = provider.type().getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new IllegalStateException("Directory plugin provider '"
                        + key.providerClass() + "' has no verifiable code source");
            }
            URI location = codeSource.getLocation().toURI();
            if (!"file".equalsIgnoreCase(location.getScheme())) {
                throw new IllegalStateException("Directory plugin provider '"
                        + key.providerClass() + "' has a non-file code source");
            }
            Path actual = Path.of(location).toRealPath();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Directory plugin provider '"
                        + key.providerClass() + "' was loaded from a different artifact; "
                        + "parent-first shadowing is not allowed");
            }
        } catch (URISyntaxException | java.io.IOException e) {
            throw new IllegalStateException("Directory plugin provider '"
                    + key.providerClass() + "' code source could not be verified", e);
        }
    }

    private static void verifyDirectoryArtifactsUnchanged(List<CatalogInput> inputs) {
        for (CatalogInput input : inputs) {
            if (input.source() != PluginSourceCategory.DIRECTORY) {
                continue;
            }
            Set<CatalogDigests.Digest> expected = new HashSet<>();
            input.index().bundles().forEach(bundle -> expected.add(
                    new CatalogDigests.Digest(bundle.digest(), bundle.digestMode())));
            input.index().legacyProviders().forEach(provider -> expected.add(
                    new CatalogDigests.Digest(provider.digest(), provider.digestMode())));
            if (expected.size() != 1) {
                throw new IllegalStateException(
                        "Directory plugin index does not identify exactly one artifact digest");
            }
            try {
                CatalogDigests.Digest actual = CatalogDigests.artifact(input.artifact());
                if (!expected.iterator().next().equals(actual)) {
                    throw new IllegalStateException(
                            "Directory plugin artifact changed during catalog construction");
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                        "Directory plugin artifact could not be revalidated", e);
            }
        }
    }

    private static void closeEagerLegacyInstances(List<Object> instances,
                                                  Throwable primary,
                                                  ClassLoader loader) {
        Set<Object> closed = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable winner = primary;
        for (int i = instances.size() - 1; i >= 0; i--) {
            Object instance = instances.get(i);
            if (!(instance instanceof AutoCloseable closeable) || !closed.add(instance)) {
                continue;
            }
            try {
                PluginThreadContext.run(loader, closeable::close);
            } catch (Throwable cleanupFailure) {
                // A provider may throw the identical object from a metadata
                // callback and close(). The safe platform wrapper already
                // represents that failure; do not unwrap it merely because
                // Error normally outranks an ordinary activation failure.
                if (isDirectWrappedCause(winner, cleanupFailure)) {
                    continue;
                }
                Throwable safeCleanupFailure = cleanupFailure;
                if (LifecycleFailures.findProcessFatalReachable(cleanupFailure) == null) {
                    // Preserve Error precedence while selecting the cleanup
                    // winner, but never promote plugin-controlled text to the
                    // public activation diagnostic. If this safe Error wins,
                    // it is converted to the ordinary activation exception
                    // below after all cleanup callbacks have run.
                    safeCleanupFailure = cleanupFailure instanceof Error
                            ? new LegacyProviderCleanupError(
                                    instance.getClass().getName(), cleanupFailure)
                            : new LegacyProviderCleanupException(
                                    instance.getClass().getName(), cleanupFailure);
                }
                winner = LifecycleFailures.merge(winner, safeCleanupFailure);
            }
        }
        if (winner != primary) {
            if (winner instanceof LegacyProviderCleanupError cleanupError) {
                throw cleanupError.asActivationException();
            }
            if (winner instanceof Error error) {
                throw error;
            }
            if (winner instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Legacy provider cleanup failed", winner);
        }
    }

    private static boolean isDirectWrappedCause(Throwable primary, Throwable candidate) {
        return (primary instanceof LegacyProviderMetadataException
                || primary instanceof LegacyProviderConstructionException)
                && primary.getCause() == candidate;
    }

    private static String legacyClassDigest(Class<?> providerType) {
        String resource = "/" + providerType.getName().replace('.', '/') + ".class";
        try (InputStream input = providerType.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Legacy provider class bytes are unavailable for '"
                        + providerType.getName() + "'");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead = 0;
            int zeroProgressReads = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read == 0) {
                    if (++zeroProgressReads > MAX_ZERO_PROGRESS_READS) {
                        throw new IllegalStateException(
                                "Legacy provider class resource made no read progress");
                    }
                    continue;
                }
                zeroProgressReads = 0;
                if (read > MAX_LEGACY_CLASS_BYTES - bytesRead) {
                    throw new IllegalStateException("Legacy provider class resource exceeds "
                            + MAX_LEGACY_CLASS_BYTES + " bytes");
                }
                bytesRead += read;
                digest.update(buffer, 0, read);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Legacy provider class bytes could not be read for '"
                    + providerType.getName() + "'", e);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Candidate legacyCandidate(ProviderKey key,
                                             Object instance,
                                             String digest,
                                             PluginDigestMode digestMode,
                                             PluginSourceCategory source,
                                             ClassLoader loader) {
        String selector = selector(key, instance, loader);
        String id;
        String version;
        SemVersion semanticVersion = null;
        List<CandidateDependency> dependencies = List.of();
        CatalogPluginProviderRegistry.ImmutableNodePluginMetadata nodePluginMetadata = null;
        if (instance instanceof NodePlugin plugin) {
            id = requireLegacyId(legacyMetadataCallback(
                    key, "NodePlugin.id()", loader, plugin::id));
            version = requireMetadata("legacy NodePlugin version",
                    legacyMetadataCallback(
                            key, "NodePlugin.version()", loader, plugin::version),
                    MAX_LEGACY_VERSION_LENGTH);
            try {
                semanticVersion = SemVersion.parse(version);
            } catch (IllegalArgumentException ignored) {
                // ADR-011.1 treated this value as opaque; retain compatibility.
            }
            Set<String> actualDependencies = legacyMetadataCallback(
                    key, "NodePlugin.dependsOn()", loader, plugin::dependsOn);
            if (actualDependencies == null) {
                throw new IllegalStateException("Legacy NodePlugin dependencies must not be null");
            }
            dependencies = snapshotLegacyDependencies(
                    key, actualDependencies, loader);
            Set<PluginCapability> capabilities = snapshotLegacyCapabilities(
                    key, legacyMetadataCallback(
                            key, "NodePlugin.capabilities()", loader,
                            plugin::capabilities), loader);
            nodePluginMetadata = new CatalogPluginProviderRegistry.ImmutableNodePluginMetadata(
                    id, version,
                    dependencies.stream().map(CandidateDependency::id)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    capabilities);
        } else {
            String normalized = normalizedLegacySelector(selector);
            String hash = CatalogDigests.className(key.providerClass())
                    .substring("sha256:".length(), "sha256:".length() + 16);
            id = "legacy." + key.kind().manifestKey() + "." + normalized + "." + hash;
            version = "0.0.0-legacy";
            semanticVersion = SemVersion.parse(version);
        }
        CandidateContribution contribution = new CandidateContribution(key.kind(), selector,
                key.providerClass(), () -> instance, null, nodePluginMetadata);
        return new Candidate(id, version, semanticVersion, null, dependencies,
                List.of(contribution), true, digest, digestMode, source);
    }

    private static Set<PluginCapability> snapshotLegacyCapabilities(
            ProviderKey key,
            Set<PluginCapability> capabilities,
            ClassLoader loader
    ) {
        if (capabilities == null) {
            throw new IllegalStateException("Legacy NodePlugin capabilities must not be null");
        }
        Iterator<PluginCapability> iterator = legacyMetadataCallback(
                key, "NodePlugin.capabilities().iterator()", loader,
                capabilities::iterator);
        Set<PluginCapability> snapshot = new HashSet<>();
        int traversed = 0;
        while (legacyMetadataCallback(
                key, "NodePlugin.capabilities().iterator().hasNext()", loader,
                iterator::hasNext)) {
            if (++traversed > PluginCapability.values().length) {
                throw new IllegalStateException(
                        "Legacy NodePlugin capabilities contain too many entries");
            }
            snapshot.add(Objects.requireNonNull(legacyMetadataCallback(
                            key, "NodePlugin.capabilities().iterator().next()", loader,
                            iterator::next),
                    "Legacy NodePlugin capabilities must not contain null"));
        }
        return Set.copyOf(snapshot);
    }

    private static List<CandidateDependency> snapshotLegacyDependencies(
            ProviderKey key,
            Set<String> dependencies,
            ClassLoader loader) {
        Iterator<String> iterator = Objects.requireNonNull(legacyMetadataCallback(
                        key, "NodePlugin.dependsOn().iterator()", loader,
                        dependencies::iterator),
                "Legacy NodePlugin dependency iterator must not be null");
        Set<String> snapshot = new TreeSet<>();
        int traversed = 0;
        while (legacyMetadataCallback(
                key, "NodePlugin.dependsOn().hasNext()", loader, iterator::hasNext)) {
            if (++traversed > MAX_LEGACY_DEPENDENCIES) {
                throw new IllegalStateException("Legacy NodePlugin dependencies must contain at "
                        + "most " + MAX_LEGACY_DEPENDENCIES + " entries");
            }
            snapshot.add(requireLegacyId(legacyMetadataCallback(
                    key, "NodePlugin.dependsOn().next()", loader, iterator::next)));
        }
        return snapshot.stream().map(value -> new CandidateDependency(value, null)).toList();
    }

    private static Map<String, Candidate> uniqueCandidates(List<Candidate> candidates) {
        Map<String, Candidate> result = new TreeMap<>();
        Set<ProviderKey> providers = new HashSet<>();
        for (Candidate candidate : candidates) {
            Candidate previous = result.putIfAbsent(candidate.id(), candidate);
            if (previous != null) {
                throw new IllegalStateException("Duplicate plugin bundle id '" + candidate.id() + "'");
            }
            for (CandidateContribution contribution : candidate.contributions()) {
                ProviderKey key = new ProviderKey(contribution.kind(), contribution.providerClass());
                if (!providers.add(key)) {
                    throw new IllegalStateException("Duplicate plugin provider '"
                            + contribution.providerClass() + "'");
                }
            }
        }
        return result;
    }

    private static void validateSelectedDependencies(Map<String, Candidate> selected) {
        for (Candidate candidate : selected.values()) {
            for (CandidateDependency dependency : candidate.dependencies()) {
                Candidate target = selected.get(dependency.id());
                if (target == null) {
                    throw new IllegalStateException("Plugin bundle '" + candidate.id()
                            + "' requires unavailable selected bundle '" + dependency.id() + "'");
                }
                BundleDependency range = dependency.range();
                boolean bounded = range != null
                        && (range.minVersion() != null || range.maxVersionExclusive() != null);
                if (bounded && target.semanticVersion() == null) {
                    throw new IllegalStateException("Plugin bundle '" + candidate.id()
                            + "' declares a SemVer-bounded dependency on legacy bundle '"
                            + dependency.id() + "', whose reported version '" + target.version()
                            + "' is not valid SemVer");
                }
                if (bounded && !range.accepts(target.semanticVersion())) {
                    throw new IllegalStateException("Plugin bundle '" + candidate.id()
                            + "' requires an incompatible version of bundle '" + dependency.id() + "'");
                }
            }
        }
    }

    private static void validateSelectedContributionKeys(Collection<Candidate> selected) {
        Map<String, String> owners = new TreeMap<>();
        for (Candidate candidate : selected) {
            for (CandidateContribution contribution : candidate.contributions()) {
                String key = contribution.kind().manifestKey() + "/" + contribution.name();
                String previous = owners.putIfAbsent(key, candidate.id());
                if (previous != null) {
                    throw new IllegalStateException("Duplicate selected contribution '" + key
                            + "' from bundles '" + previous + "' and '" + candidate.id() + "'");
                }
            }
        }
    }

    private static List<Candidate> topologicalOrder(Map<String, Candidate> selected) {
        Map<String, Integer> indegree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        selected.keySet().forEach(id -> {
            indegree.put(id, 0);
            dependents.put(id, new TreeSet<>());
        });
        for (Candidate candidate : selected.values()) {
            for (CandidateDependency dependency : candidate.dependencies()) {
                indegree.compute(candidate.id(), (ignored, value) -> value + 1);
                dependents.get(dependency.id()).add(candidate.id());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) ready.add(id);
        });
        List<Candidate> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(selected.get(id));
            for (String dependent : dependents.get(id)) {
                int degree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) ready.add(dependent);
            }
        }
        if (ordered.size() != selected.size()) {
            throw new IllegalStateException(boundedCycleDiagnostic(cyclePath(selected)));
        }
        return List.copyOf(ordered);
    }

    private static String boundedCycleDiagnostic(List<String> cycle) {
        long renderedLength = DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX.length() + 2L;
        for (int index = 0; index < cycle.size(); index++) {
            renderedLength += cycle.get(index).length() + (index == 0 ? 0 : 2);
            if (renderedLength > MAX_CYCLE_DIAGNOSTIC_LENGTH) {
                return compactCycleDiagnostic(cycle);
            }
        }
        return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + cycle;
    }

    private static String compactCycleDiagnostic(List<String> cycle) {
        int traversedNodes = Math.max(0, cycle.size() - 1);
        if (traversedNodes == 0) {
            return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + "[]";
        }

        int headCount = Math.min(2, traversedNodes);
        int tailCount = Math.min(2, traversedNodes - headCount);
        int omittedCount = traversedNodes - headCount - tailCount;
        List<String> displayed = new ArrayList<>(headCount + tailCount + 2);
        for (int index = 0; index < headCount; index++) {
            displayed.add(abbreviateCycleId(cycle.get(index)));
        }
        if (omittedCount > 0) {
            displayed.add("... <" + omittedCount + " nodes omitted> ...");
        }
        for (int index = traversedNodes - tailCount; index < traversedNodes; index++) {
            displayed.add(abbreviateCycleId(cycle.get(index)));
        }
        // cyclePath always repeats the first node last; retain that closure in
        // the compact diagnostic so operators can still recognize a cycle.
        displayed.add(abbreviateCycleId(cycle.getLast()));
        return DEPENDENCY_CYCLE_DIAGNOSTIC_PREFIX + displayed;
    }

    private static String abbreviateCycleId(String id) {
        if (id.length() <= MAX_CYCLE_ID_DISPLAY_LENGTH) {
            return id;
        }
        int prefixLength = (MAX_CYCLE_ID_DISPLAY_LENGTH - 3) / 2;
        int suffixLength = MAX_CYCLE_ID_DISPLAY_LENGTH - 3 - prefixLength;
        return id.substring(0, prefixLength) + "..."
                + id.substring(id.length() - suffixLength);
    }

    private static List<String> cyclePath(Map<String, Candidate> selected) {
        Map<String, Visit> state = new TreeMap<>();
        selected.keySet().forEach(id -> state.put(id, Visit.NEW));
        Deque<String> path = new ArrayDeque<>();
        for (String id : selected.keySet()) {
            if (state.get(id) != Visit.NEW) {
                continue;
            }
            List<String> cycle = cycleFrom(id, selected, state, path);
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    private static List<String> cycleFrom(String id, Map<String, Candidate> selected,
                                          Map<String, Visit> state, Deque<String> path) {
        state.put(id, Visit.VISITING);
        path.addLast(id);
        Deque<CatalogCycleFrame> frames = new ArrayDeque<>();
        frames.addLast(catalogCycleFrame(id, selected));

        while (!frames.isEmpty()) {
            CatalogCycleFrame frame = frames.getLast();
            if (!frame.dependencies().hasNext()) {
                frames.removeLast();
                path.removeLast();
                state.put(frame.id(), Visit.DONE);
                continue;
            }

            String dependency = frame.dependencies().next();
            Visit dependencyState = state.get(dependency);
            if (dependencyState == Visit.DONE) {
                continue;
            }
            if (dependencyState == Visit.VISITING) {
                List<String> active = new ArrayList<>(path);
                int start = active.indexOf(dependency);
                List<String> cycle = new ArrayList<>(active.subList(start, active.size()));
                cycle.add(dependency);
                return List.copyOf(cycle);
            }

            state.put(dependency, Visit.VISITING);
            path.addLast(dependency);
            frames.addLast(catalogCycleFrame(dependency, selected));
        }
        return List.of();
    }

    private static CatalogCycleFrame catalogCycleFrame(
            String id,
            Map<String, Candidate> selected
    ) {
        Iterator<String> dependencies = selected.get(id).dependencies().stream()
                .map(CandidateDependency::id)
                .sorted()
                .iterator();
        return new CatalogCycleFrame(id, dependencies);
    }

    private static void validateReservedNames(BundleManifest manifest) {
        for (BundleContribution contribution : manifest.contributions()) {
            boolean reserved = switch (contribution.kind()) {
                case APP_STATE_MACHINE -> contribution.name().equals("ordered-log");
                case SEQUENCER_MODE -> contribution.name().equals("fixed")
                        || contribution.name().equals("rotating");
                case L1_OBSERVER -> contribution.name().equals("metadata-label")
                        || contribution.name().equals("address-deposit");
                case EFFECT_EXECUTOR -> contribution.name().equals("webhook");
                default -> false;
            };
            if (reserved) {
                throw new IllegalStateException("Plugin bundle '" + manifest.id()
                        + "' declares reserved SYSTEM contribution '"
                        + contribution.kind().manifestKey() + "/" + contribution.name() + "'");
            }
        }
    }

    private static PluginBundleInfo inventory(Candidate candidate) {
        List<PluginContributionInfo> contributions = candidate.contributions().stream()
                .sorted(Comparator.comparing((CandidateContribution value) -> value.kind().manifestKey())
                        .thenComparing(CandidateContribution::name))
                .map(value -> new PluginContributionInfo(value.kind().manifestKey(), value.name(),
                        value.providerClass(), trust(value.kind())))
                .toList();
        return new PluginBundleInfo(candidate.id(), candidate.version(), candidate.selected(),
                candidate.selectionStatus(),
                candidate.legacy(), candidate.source(), candidate.digest(), candidate.digestMode(),
                candidate.dependencies().stream().map(CandidateDependency::id).sorted().toList(),
                contributions);
    }

    private static void warnLegacyBundles(Collection<Candidate> candidates) {
        candidates.stream()
                .filter(Candidate::legacy)
                .sorted(Comparator.comparing(Candidate::id))
                .forEach(candidate -> log.warn(
                        "Discovered unmanifested legacy plugin bundle '{}' (selection={}); "
                                + "add an ADR-011.2 bundle manifest",
                        candidate.id(), candidate.selectionStatus()));
    }

    private static PluginTrustTier trust(ContributionKind kind) {
        return switch (kind) {
            case NODE_PLUGIN -> PluginTrustTier.REQUIRED;
            case APP_STATE_MACHINE, SEQUENCER_MODE, L1_OBSERVER -> PluginTrustTier.CONSENSUS;
            case SIGNER_PROVIDER, EFFECT_EXECUTOR, DOMAIN_API ->
                    PluginTrustTier.PRIVILEGED_LOCAL;
            case FINALIZED_SINK, HEALTH, METRICS -> PluginTrustTier.AUXILIARY_LOCAL;
        };
    }

    private static String fingerprint(List<Candidate> ordered) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(PLUGIN_API_MAJOR);
                data.writeInt(PLUGIN_API_LEVEL);
                data.writeInt(ordered.size());
                for (Candidate candidate : ordered) {
                    text(data, candidate.id());
                    text(data, candidate.version());
                    data.writeBoolean(candidate.legacy());
                    text(data, candidate.digest());
                    text(data, candidate.digestMode().name());
                    if (candidate.manifest() != null) {
                        data.writeInt(candidate.manifest().schemaVersion());
                        data.writeInt(candidate.manifest().yanoApi().min());
                        data.writeInt(candidate.manifest().yanoApi().max());
                        data.writeInt(candidate.manifest().yanoApi().minLevel());
                    } else {
                        data.writeInt(0);
                        data.writeInt(0);
                        data.writeInt(0);
                        data.writeInt(0);
                    }
                    List<CandidateDependency> dependencies = candidate.dependencies().stream()
                            .sorted(Comparator.comparing(CandidateDependency::id)).toList();
                    data.writeInt(dependencies.size());
                    for (CandidateDependency dependency : dependencies) {
                        text(data, dependency.id());
                        text(data, dependency.range() != null && dependency.range().minVersion() != null
                                ? dependency.range().minVersion().toString() : "");
                        text(data, dependency.range() != null
                                        && dependency.range().maxVersionExclusive() != null
                                ? dependency.range().maxVersionExclusive().toString() : "");
                    }
                    List<CandidateContribution> contributions = candidate.contributions().stream()
                            .sorted(Comparator.comparing((CandidateContribution value) ->
                                            value.kind().manifestKey())
                                    .thenComparing(CandidateContribution::name)
                                    .thenComparing(CandidateContribution::providerClass))
                            .toList();
                    data.writeInt(contributions.size());
                    for (CandidateContribution contribution : contributions) {
                        text(data, contribution.kind().manifestKey());
                        text(data, contribution.name());
                        text(data, contribution.providerClass());
                        text(data, trust(contribution.kind()).name());
                    }
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (java.io.IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Could not calculate plugin catalog fingerprint", impossible);
        }
    }

    private static void text(DataOutputStream data, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static Map<String, Map<String, Object>> bundleConfigs(
            Map<String, Object> config,
            Collection<Candidate> selected
    ) {
        Map<String, Map<String, Object>> mutable = new TreeMap<>();
        for (Candidate candidate : selected) {
            if (candidate.legacy()) {
                continue;
            }
            mutable.put(candidate.id(), new TreeMap<>());
        }

        // Quoted and bracketed owner segments are exact. Never infer ownership
        // with a dot prefix: removing com.example.product must not reassign its
        // stale secret to a selected com.example parent bundle.
        new TreeMap<>(config).forEach((key, value) -> parseExactBundleConfigKey(key)
                .ifPresent(parsed -> putBundleConfig(
                        mutable.get(parsed.owner()), parsed.property(), value)));

        // The previous punctuation-to-underscore owner spelling was lossy: a
        // stale secret for a removed `a-b` bundle could later be reassigned to
        // an installed `a.b` bundle. Never infer ownership from the current
        // catalog. The canonical environment form carries the exact UTF-8
        // bundle id as hex, so an unknown/removed owner remains unknown.
        new TreeMap<>(config).forEach((key, value) -> {
            if (key.startsWith(LEGACY_ENVIRONMENT_BUNDLE_PREFIX)) {
                throw new IllegalStateException(
                        "Lossy plugin bundle environment owner syntax is unsupported; "
                                + "use YANO_PLUGINS_BUNDLE_HEX__<UTF8_HEX_ID>__<PROPERTY> "
                                + "or the exact quoted property namespace");
            }
            if (!key.startsWith(ENVIRONMENT_BUNDLE_HEX_PREFIX)) {
                return;
            }
            BundleConfigKey parsed = parseEncodedEnvironmentBundleKey(key);
            Map<String, Object> ownerConfig = mutable.get(parsed.owner());
            if (ownerConfig == null) {
                return;
            }
            String environmentProperty = parsed.property();
            String property = environmentProperty(environmentProperty);
            if (property == null) {
                throw new IllegalStateException(
                        "Malformed encoded plugin bundle environment property name");
            }
            // An exact quoted/bracketed key is stronger evidence than the lossy
            // reverse mapping. It also lets another ConfigSource teach SmallRye
            // that an environment suffix contains a dash.
            List<String> exactProperties = ownerConfig.keySet().stream()
                    .filter(existing -> environmentToken(existing)
                            .equals(environmentProperty))
                    .toList();
            if (exactProperties.size() > 1) {
                throw new IllegalStateException("Environment plugin bundle property is ambiguous "
                        + "after SmallRye normalization for owner '" + parsed.owner()
                        + "'; use only exact quoted properties");
            }
            if (exactProperties.size() == 1) {
                // A reversible environment owner still has a lossy property
                // suffix. An exact quoted/bracketed property is authoritative
                // and the environment alias is ignored, including when its
                // value differs.
                return;
            }
            putBundleConfig(ownerConfig, property, value);
        });

        Map<String, Map<String, Object>> result = new TreeMap<>();
        mutable.forEach((id, values) -> result.put(id,
                Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        return Collections.unmodifiableMap(result);
    }

    private static final String QUOTED_BUNDLE_PREFIX = "yano.plugins.bundle.\"";
    private static final String BRACKETED_BUNDLE_PREFIX = "yano.plugins.bundle[";
    private static final String ENVIRONMENT_BUNDLE_HEX_PREFIX =
            "YANO_PLUGINS_BUNDLE_HEX__";
    private static final String LEGACY_ENVIRONMENT_BUNDLE_PREFIX =
            "YANO_PLUGINS_BUNDLE__";

    private static Optional<BundleConfigKey> parseExactBundleConfigKey(String key) {
        if (key.startsWith(QUOTED_BUNDLE_PREFIX)) {
            int ownerEnd = key.indexOf("\".", QUOTED_BUNDLE_PREFIX.length());
            if (ownerEnd <= QUOTED_BUNDLE_PREFIX.length()
                    || ownerEnd + 2 == key.length()) {
                return Optional.empty();
            }
            return Optional.of(new BundleConfigKey(
                    key.substring(QUOTED_BUNDLE_PREFIX.length(), ownerEnd),
                    key.substring(ownerEnd + 2)));
        }
        if (key.startsWith(BRACKETED_BUNDLE_PREFIX)) {
            int ownerEnd = key.indexOf("].", BRACKETED_BUNDLE_PREFIX.length());
            if (ownerEnd <= BRACKETED_BUNDLE_PREFIX.length()
                    || ownerEnd + 2 == key.length()) {
                return Optional.empty();
            }
            return Optional.of(new BundleConfigKey(
                    key.substring(BRACKETED_BUNDLE_PREFIX.length(), ownerEnd),
                    key.substring(ownerEnd + 2)));
        }
        return Optional.empty();
    }

    private static void putBundleConfig(Map<String, Object> ownerConfig,
                                        String property,
                                        Object value) {
        if (ownerConfig == null) {
            return;
        }
        if (ownerConfig.containsKey(property)
                && !Objects.equals(ownerConfig.get(property), value)) {
            throw new IllegalStateException(
                    "Conflicting plugin bundle configuration aliases (keys and values omitted)");
        }
        ownerConfig.putIfAbsent(property, value);
    }

    private static BundleConfigKey parseEncodedEnvironmentBundleKey(String key) {
        int ownerStart = ENVIRONMENT_BUNDLE_HEX_PREFIX.length();
        int ownerEnd = key.indexOf("__", ownerStart);
        if (ownerEnd == ownerStart || ownerEnd < 0 || ownerEnd + 2 == key.length()) {
            throw new IllegalStateException(
                    "Malformed encoded plugin bundle environment owner");
        }
        String encodedOwner = key.substring(ownerStart, ownerEnd);
        if (encodedOwner.length() > 320 || (encodedOwner.length() & 1) != 0
                || !encodedOwner.matches("[0-9A-Fa-f]+")) {
            throw new IllegalStateException(
                    "Malformed encoded plugin bundle environment owner");
        }
        String owner;
        try {
            byte[] encoded = java.util.HexFormat.of().parseHex(encodedOwner);
            owner = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(encoded)).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            throw new IllegalStateException(
                    "Malformed encoded plugin bundle environment owner");
        }
        return new BundleConfigKey(owner, key.substring(ownerEnd + 2));
    }

    private static String environmentToken(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')) {
                result.append(Character.toUpperCase(character));
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    private static String environmentProperty(String value) {
        if (value.isEmpty() || value.startsWith("_") || value.endsWith("_")
                || value.contains("__")) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_')) {
                return null;
            }
        }
        return value.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private record BundleConfigKey(String owner, String property) { }

    private static String selector(ProviderKey key,
                                   Object provider,
                                   ClassLoader loader) {
        String result = legacyMetadataCallback(
                key, "provider selector", loader, () -> switch (key.kind()) {
                    case NODE_PLUGIN -> ((NodePlugin) provider).id();
                    case APP_STATE_MACHINE -> ((AppStateMachineProvider) provider).id();
                    case SEQUENCER_MODE -> ((SequencerModeProvider) provider).id();
                    case L1_OBSERVER -> ((L1ObserverProvider) provider).type();
                    case SIGNER_PROVIDER -> ((SignerProviderFactory) provider).scheme();
                    case EFFECT_EXECUTOR -> ((AppEffectExecutorFactory) provider).scheme();
                    case FINALIZED_SINK -> ((FinalizedStreamSinkFactory) provider).scheme();
                    case DOMAIN_API -> ((DomainApiProvider) provider).id();
                    case HEALTH -> ((PluginHealthProvider) provider).id();
                    case METRICS -> ((PluginMetricsProvider) provider).id();
                });
        int maximumLength = key.kind() == ContributionKind.NODE_PLUGIN
                ? MAX_LEGACY_ID_LENGTH : MAX_LEGACY_SELECTOR_LENGTH;
        return requireMetadata("legacy provider selector", result, maximumLength);
    }

    /**
     * Legacy discovery must execute provider-owned identity callbacks before a
     * manifested bundle boundary exists. Keep the original failure as the
     * cause for programmatic inspection, but never promote its potentially
     * secret-bearing message into the catalog activation diagnostic.
     */
    private static <T> T legacyMetadataCallback(
            ProviderKey key,
            String operation,
            ClassLoader loader,
        Supplier<T> callback
    ) {
        try {
            return PluginThreadContext.call(loader, callback::get);
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            throw new LegacyProviderMetadataException(
                    key.providerClass(), operation, failure);
        }
    }

    static final class LegacyProviderConstructionException extends IllegalStateException {
        private LegacyProviderConstructionException(String providerClass, Throwable cause) {
            super("Legacy provider '" + providerClass + "' failed during construction", cause);
        }
    }

    static final class LegacyProviderCleanupException extends IllegalStateException {
        private LegacyProviderCleanupException(String providerClass, Throwable cause) {
            super("Legacy provider '" + providerClass + "' failed during cleanup", cause);
        }
    }

    /**
     * Internal rank-preserving wrapper for a non-process-fatal plugin Error.
     * It is never allowed to escape the catalog boundary as the public
     * diagnostic; {@link #asActivationException()} converts it after winner
     * selection while retaining best-effort cleanup context.
     */
    static final class LegacyProviderCleanupError extends Error {
        private final String providerClass;

        private LegacyProviderCleanupError(String providerClass, Throwable cause) {
            super("Legacy provider '" + providerClass + "' failed during cleanup", cause);
            this.providerClass = providerClass;
        }

        private LegacyProviderCleanupException asActivationException() {
            LegacyProviderCleanupException converted =
                    new LegacyProviderCleanupException(providerClass, getCause());
            for (Throwable context : getSuppressed()) {
                // Winner selection already happened while this object still
                // carried Error rank. Conversion must transfer that complete
                // context graph without performing a second rank comparison
                // against the now-ordinary public wrapper.
                converted.addSuppressed(context);
            }
            return converted;
        }
    }

    static final class LegacyProviderMetadataException extends IllegalStateException {
        private LegacyProviderMetadataException(
                String providerClass,
                String operation,
                Throwable cause
        ) {
            super("Legacy provider '" + providerClass
                    + "' failed during " + operation, cause);
        }
    }

    private static String requireLegacyId(String value) {
        return requireMetadata("legacy plugin id", value, MAX_LEGACY_ID_LENGTH);
    }

    private static String requireMetadata(String label, String value, int maxLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > maxLength) {
            throw new IllegalStateException(label + " must be non-blank, at most "
                    + maxLength + " characters, and have no surrounding whitespace");
        }
        return value;
    }

    private static String normalizedLegacySelector(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty()) normalized = "provider";
        if (normalized.length() > 48) normalized = normalized.substring(0, 48).replaceAll("-+$", "");
        return normalized;
    }

    record CatalogInput(PluginIndex index, PluginSourceCategory source, Path artifact) {
        CatalogInput(PluginIndex index, PluginSourceCategory source) {
            this(index, source, null);
        }

        CatalogInput {
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(source, "source");
            if (source == PluginSourceCategory.DIRECTORY) {
                if (artifact == null) {
                    throw new IllegalArgumentException(
                            "Directory catalog input requires its scanned artifact path");
                }
                try {
                    artifact = artifact.toRealPath();
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException(
                            "Directory catalog input artifact could not be resolved", e);
                }
            } else if (artifact != null) {
                throw new IllegalArgumentException(
                        "Only directory catalog inputs may declare an artifact path");
            }
        }
    }

    record BuildResult(
            PluginCatalogSnapshot catalog,
            CatalogPluginProviderRegistry registry,
            List<String> selectedOrder,
            Set<String> selectedIds,
            Map<String, Map<String, Object>> bundleConfigs
    ) {
    }

    private record ProviderKey(ContributionKind kind, String providerClass) {
        private ProviderKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(providerClass, "providerClass");
        }
    }

    private record IndexedLegacy(IndexedLegacyProvider provider, PluginSourceCategory source) {
    }

    private record CandidateDependency(String id, BundleDependency range) {
    }

    private record CatalogCycleFrame(String id, Iterator<String> dependencies) {
    }

    private record CandidateContribution(
            ContributionKind kind,
            String name,
            String providerClass,
            Supplier<?> supplier,
            BundleManifest manifest,
            CatalogPluginProviderRegistry.ImmutableNodePluginMetadata nodePluginMetadata
    ) {
    }

    private static final class Candidate {
        private final String id;
        private final String version;
        private final SemVersion semanticVersion;
        private final BundleManifest manifest;
        private final List<CandidateDependency> dependencies;
        private final List<CandidateContribution> contributions;
        private final boolean legacy;
        private final String digest;
        private final PluginDigestMode digestMode;
        private final PluginSourceCategory source;
        private PluginSelectionStatus selectionStatus;

        private Candidate(String id, String version, SemVersion semanticVersion,
                          BundleManifest manifest, List<CandidateDependency> dependencies,
                          List<CandidateContribution> contributions, boolean legacy,
                          String digest, PluginDigestMode digestMode, PluginSourceCategory source) {
            this.id = id;
            this.version = version;
            this.semanticVersion = semanticVersion;
            this.manifest = manifest;
            this.dependencies = List.copyOf(dependencies);
            this.contributions = List.copyOf(contributions);
            this.legacy = legacy;
            this.digest = digest;
            this.digestMode = digestMode;
            this.source = source;
        }

        static Candidate manifested(IndexedBundle indexed, PluginSourceCategory source,
                                    List<CandidateContribution> contributions) {
            BundleManifest manifest = indexed.manifest();
            List<CandidateDependency> dependencies = manifest.dependencies().stream()
                    .map(value -> new CandidateDependency(value.id(), value)).toList();
            return new Candidate(manifest.id(), manifest.version().toString(), manifest.version(),
                    manifest, dependencies, contributions, false, indexed.digest(),
                    indexed.digestMode(), source);
        }

        String id() { return id; }
        String version() { return version; }
        SemVersion semanticVersion() { return semanticVersion; }
        BundleManifest manifest() { return manifest; }
        List<CandidateDependency> dependencies() { return dependencies; }
        List<CandidateContribution> contributions() { return contributions; }
        boolean legacy() { return legacy; }
        String digest() { return digest; }
        PluginDigestMode digestMode() { return digestMode; }
        PluginSourceCategory source() { return source; }
        boolean selected() { return selectionStatus == PluginSelectionStatus.SELECTED; }
        PluginSelectionStatus selectionStatus() { return selectionStatus; }
        void selectionStatus(PluginSelectionStatus selectionStatus) {
            this.selectionStatus = Objects.requireNonNull(selectionStatus, "selectionStatus");
        }
    }

    private enum Visit { NEW, VISITING, DONE }
}
