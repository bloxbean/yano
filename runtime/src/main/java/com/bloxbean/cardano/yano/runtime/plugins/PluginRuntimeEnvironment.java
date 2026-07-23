package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.NodePlugin;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.catalog.CatalogDigests;
import com.bloxbean.cardano.yano.catalog.PluginArtifactScanner;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.catalog.PluginIndexCodec;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;

/**
 * One immutable catalog plus the explicitly owned provider/class-loader
 * lifetime used by a runtime assembly.
 */
public final class PluginRuntimeEnvironment implements AutoCloseable {
    /** Stable prefix consumed by native packaging smoke tests and operators. */
    public static final String PROVENANCE_LOG_PREFIX = "YANO_PLUGIN_CATALOG_PROVENANCE";

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeEnvironment.class);
    private final PluginsOptions options;
    private final PluginLoaderHandle loaderHandle;
    private final PluginCatalogBuilder.BuildResult build;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean nodePluginManagerClaimed = new AtomicBoolean();
    private final Object closeMonitor = new Object();
    private volatile PluginManager nodePluginManager;
    private boolean closeInProgress;
    private Thread closeOwner;
    private Throwable terminalCloseFailure;

    private PluginRuntimeEnvironment(PluginsOptions options,
                                     PluginLoaderHandle loaderHandle,
                                     PluginCatalogBuilder.BuildResult build) {
        this.options = options;
        this.loaderHandle = loaderHandle;
        this.build = build;
    }

    public static PluginRuntimeEnvironment open(PluginsOptions options,
                                                PluginLoaderHandle loaderHandle) {
        return open(options, loaderHandle, (embeddedIndexSha256, result) ->
                log.info("{} indexSha256={} catalogFingerprint={}", PROVENANCE_LOG_PREFIX,
                        embeddedIndexSha256, result.catalog().fingerprint()));
    }

    /** Package-private post-build seam for ownership and cleanup-order tests. */
    static PluginRuntimeEnvironment open(
            PluginsOptions options,
            PluginLoaderHandle loaderHandle,
            PostBuildAction postBuildAction
    ) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(loaderHandle, "loaderHandle");
        Objects.requireNonNull(postBuildAction, "postBuildAction");
        loaderHandle.claimRuntimeOwnership();
        PluginCatalogBuilder.BuildResult result = null;
        try {
            DiscoveryResult discovery = options.enabled()
                    ? discoverInputs(options, loaderHandle) : DiscoveryResult.disabled();
            result = new PluginCatalogBuilder().build(
                    options, loaderHandle.classLoader(), discovery.inputs(),
                    discovery.allowUnindexedLegacyProviders());
            PluginRuntimeEnvironment environment =
                    new PluginRuntimeEnvironment(options, loaderHandle, result);
            postBuildAction.run(discovery.embeddedIndexSha256(), result);
            return environment;
        } catch (Throwable failure) {
            // Ownership was already claimed. Cleanup must happen before any
            // overridable diagnostic inspection and for sneaky checked
            // throwables as well as RuntimeException/Error.
            Throwable outcome = closeAfterOpenFailure(result, loaderHandle, failure);
            LifecycleFailures.rethrowIfProcessFatalReachable(outcome);
            throw PluginCatalogActivationException.from(outcome);
        }
    }

    private static Throwable closeAfterOpenFailure(
            PluginCatalogBuilder.BuildResult result,
            PluginLoaderHandle loaderHandle,
            Throwable failure
    ) {
        Throwable outcome = failure;
        if (result != null) {
            try {
                result.registry().close();
            } catch (Throwable cleanup) {
                outcome = LifecycleFailures.merge(outcome, cleanup);
            }
        }
        try {
            loaderHandle.closeClaimed();
        } catch (Throwable cleanup) {
            outcome = LifecycleFailures.merge(outcome, cleanup);
        }
        return outcome;
    }

    /**
     * Opens the catalog in library-compatibility mode. This is the only mode
     * that permits a missing aggregate index and implicit legacy discovery.
     */
    public static PluginRuntimeEnvironment classpath(PluginsOptions options,
                                                     ClassLoader loader) {
        return open(options, PluginLoaderHandle.classpath(loader));
    }

    /** Opens the catalog in packaged-JVM mode with one mandatory aggregate index. */
    public static PluginRuntimeEnvironment packagedClasspath(PluginsOptions options,
                                                             ClassLoader loader) {
        return open(options, PluginLoaderHandle.packagedClasspath(loader));
    }

    private static DiscoveryResult discoverInputs(
            PluginsOptions options,
            PluginLoaderHandle handle) {
        List<PluginCatalogBuilder.CatalogInput> inputs = new ArrayList<>();
        List<String> embeddedIndexDigests = new ArrayList<>();
        PluginIndexCodec codec = new PluginIndexCodec();
        try {
            Enumeration<URL> resources = handle.embeddedIndexLoader()
                    .getResources(PluginIndex.RESOURCE_PATH);
            List<URL> embeddedIndexes = new ArrayList<>();
            if (resources.hasMoreElements()) {
                embeddedIndexes.add(resources.nextElement());
            }
            // Only cardinalities 0 and 1 are valid. Fail at the second
            // element instead of draining an adversarial or enormous
            // ClassLoader enumeration into memory.
            if (resources.hasMoreElements()) {
                throw new IllegalStateException(
                        "Multiple embedded plugin aggregate indexes were discovered; "
                                + "packaging must produce exactly one authoritative index");
            }
            if (handle.discoveryMode().requiresEmbeddedIndex()
                    && embeddedIndexes.isEmpty()) {
                throw new IllegalStateException("Plugin discovery mode "
                        + handle.discoveryMode()
                        + " requires exactly one embedded aggregate index at "
                        + PluginIndex.RESOURCE_PATH);
            }
            PluginSourceCategory embeddedSource = handle.source() == PluginSourceCategory.NATIVE
                    ? PluginSourceCategory.NATIVE : PluginSourceCategory.CLASSPATH;
            for (URL resource : embeddedIndexes) {
                try (InputStream input = resource.openStream()) {
                    byte[] encodedIndex = input.readNBytes(PluginIndexCodec.MAX_INDEX_BYTES + 1);
                    if (encodedIndex.length > PluginIndexCodec.MAX_INDEX_BYTES) {
                        throw new IllegalStateException("Embedded plugin aggregate index exceeds "
                                + PluginIndexCodec.MAX_INDEX_BYTES + " bytes");
                    }
                    PluginIndex index = codec.read(encodedIndex);
                    validateEmbeddedIndex(index, handle.discoveryMode());
                    inputs.add(new PluginCatalogBuilder.CatalogInput(index, embeddedSource));
                    embeddedIndexDigests.add(sha256(encodedIndex));
                }
            }
            PluginArtifactScanner scanner = new PluginArtifactScanner();
            for (Path artifact : handle.artifacts()) {
                PluginIndex index = scanner.scan(artifact);
                if (index.bundles().isEmpty() && index.legacyProviders().isEmpty()) {
                    throw new IllegalStateException("Plugin directory JAR '"
                            + handle.artifactSourceName(artifact)
                            + "' has no bundle manifest or supported ServiceLoader provider; "
                            + "v1 bundles must be self-contained");
                }
                verifyLoaderOrderingDigest(handle, artifact, index);
                inputs.add(new PluginCatalogBuilder.CatalogInput(
                        index, PluginSourceCategory.DIRECTORY, artifact));
            }
            PluginCatalogBuilder.validateProviderBudget(inputs, PluginIndex.MAX_PROVIDERS);
            PluginCatalogBuilder.validateManifestCompatibility(inputs);
            validateDirectoryArtifacts(handle, inputs);
            handle.activateDirectoryArtifacts(
                    selectedDirectoryArtifacts(options, inputs));
            boolean allowUnindexedLegacy = embeddedIndexes.isEmpty()
                    && handle.discoveryMode().allowsUnindexedLegacyProviders();
            String embeddedIndexSha256 = embeddedIndexDigests.isEmpty()
                    ? "none" : embeddedIndexDigests.getFirst();
            return new DiscoveryResult(
                    List.copyOf(inputs), allowUnindexedLegacy, embeddedIndexSha256);
        } catch (IOException e) {
            throw new IllegalStateException("Plugin catalog inputs could not be read", e);
        }
    }

    private static void validateDirectoryArtifacts(
            PluginLoaderHandle handle,
            List<PluginCatalogBuilder.CatalogInput> inputs
    ) {
        DirectoryPluginArtifactValidator.DeploymentValidationBudget deploymentBudget =
                new DirectoryPluginArtifactValidator.DeploymentValidationBudget(
                        PluginCatalogBuilder.MAX_DISCOVERED_PROVIDERS);
        for (PluginCatalogBuilder.CatalogInput input : inputs) {
            if (input.source() == PluginSourceCategory.DIRECTORY) {
                DirectoryPluginArtifactValidator.validate(
                        input.artifact(), input.index(), handle.embeddedIndexLoader(),
                        deploymentBudget);
            }
        }
    }

    private static Set<Path> selectedDirectoryArtifacts(
            PluginsOptions options,
            List<PluginCatalogBuilder.CatalogInput> inputs
    ) {
        Set<Path> selected = new LinkedHashSet<>();
        for (PluginCatalogBuilder.CatalogInput input : inputs) {
            if (input.source() != PluginSourceCategory.DIRECTORY) {
                continue;
            }
            if (!input.index().legacyProviders().isEmpty()) {
                // A legacy bundle's policy id comes from provider callbacks, so
                // it cannot be classified before executable loading. Refuse an
                // ambiguous filtered deployment instead of placing possibly
                // unselected bytes on the shared loader. With no filters every
                // legacy directory artifact is selected, preserving migration
                // compatibility without weakening the policy boundary.
                if (!options.allowList().isEmpty() || !options.denyList().isEmpty()) {
                    throw new IllegalStateException(
                            "Directory legacy providers cannot be used with bundle allow/deny "
                                    + "policy; add ADR-011.2 manifests before filtering them");
                }
                selected.add(input.artifact());
                continue;
            }
            Set<Boolean> bundleSelections = input.index().bundles().stream()
                    .map(bundle -> selectedByPolicy(options, bundle.manifest().id()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (bundleSelections.size() > 1) {
                throw new IllegalStateException(
                        "One directory artifact cannot mix selected and policy-filtered bundles; "
                                + "split each bundle into its own JAR");
            }
            if (bundleSelections.contains(Boolean.TRUE)) {
                selected.add(input.artifact());
            }
        }
        return Set.copyOf(selected);
    }

    private static boolean selectedByPolicy(PluginsOptions options, String bundleId) {
        return !options.denyList().contains(bundleId)
                && (options.allowList().isEmpty()
                || options.allowList().contains(bundleId));
    }

    private static void verifyLoaderOrderingDigest(
            PluginLoaderHandle handle,
            Path artifact,
            PluginIndex index
    ) {
        Set<CatalogDigests.Digest> scanDigests = new HashSet<>();
        index.bundles().forEach(bundle -> scanDigests.add(
                new CatalogDigests.Digest(bundle.digest(), bundle.digestMode())));
        index.legacyProviders().forEach(provider -> scanDigests.add(
                new CatalogDigests.Digest(provider.digest(), provider.digestMode())));
        if (scanDigests.size() != 1) {
            throw new IllegalStateException("Plugin directory JAR '"
                    + handle.artifactSourceName(artifact)
                    + "' does not identify exactly one scanned artifact digest");
        }
        CatalogDigests.Digest orderingDigest = handle.artifactOrderingDigest(artifact);
        CatalogDigests.Digest scanDigest = scanDigests.iterator().next();
        if (!orderingDigest.equals(scanDigest)) {
            throw new IllegalStateException("Plugin directory JAR '"
                    + handle.artifactSourceName(artifact)
                    + "' changed after class-loader ordering was established");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validateEmbeddedIndex(
            PluginIndex index,
            PluginDiscoveryMode discoveryMode
    ) {
        if (!discoveryMode.requiresEmbeddedIndex()) {
            return;
        }
        if (!index.legacyProviders().isEmpty()) {
            throw new IllegalStateException("Plugin discovery mode " + discoveryMode
                    + " requires manifested build-time providers; the embedded aggregate "
                    + "index contains legacy providers");
        }
        List<String> weakEvidence = index.bundles().stream()
                .filter(bundle -> bundle.digestMode() != PluginDigestMode.ARTIFACT_CLOSURE)
                .map(bundle -> bundle.manifest().id())
                .sorted()
                .toList();
        if (!weakEvidence.isEmpty()) {
            throw new IllegalStateException("Plugin discovery mode " + discoveryMode
                    + " requires ARTIFACT_CLOSURE evidence for every embedded bundle; "
                    + "invalid bundles=" + weakEvidence);
        }
    }

    public PluginCatalogView catalog() {
        ensureOpen();
        return build.catalog();
    }

    public PluginProviderRegistry providers() {
        ensureOpen();
        return build.registry();
    }

    /** Create the exactly-once lifecycle owner for this catalog's selected NodePlugins. */
    public PluginManager createNodePluginManager(EventBus eventBus,
                                                 ScheduledExecutorService scheduler) {
        return createNodePluginManager(
                eventBus, scheduler, NodePluginLifecycleObserver.NOOP);
    }

    /** Create the lifecycle owner with actual outcomes projected into operations data. */
    public PluginManager createNodePluginManager(
            EventBus eventBus,
            ScheduledExecutorService scheduler,
            PluginOperationsRegistry operations
    ) {
        Objects.requireNonNull(operations, "operations");
        return createNodePluginManager(eventBus, scheduler,
                new NodePluginLifecycleObserver() {
                    @Override
                    public void starting(String bundleId) {
                        operations.nodePluginStarting(bundleId);
                    }

                    @Override
                    public void started(String bundleId) {
                        operations.nodePluginStarted(bundleId);
                    }

                    @Override
                    public void startFailed(String bundleId) {
                        operations.nodePluginStartFailed(bundleId);
                    }

                    @Override
                    public void stopped(String bundleId, boolean succeeded) {
                        operations.nodePluginStopped(bundleId, succeeded);
                    }

                    @Override
                    public void closed(String bundleId, boolean succeeded) {
                        operations.nodePluginClosed(bundleId, succeeded);
                    }
                });
    }

    private PluginManager createNodePluginManager(
            EventBus eventBus,
            ScheduledExecutorService scheduler,
            NodePluginLifecycleObserver lifecycleObserver
    ) {
        // Claim the manager lifetime at the same ordering point used by
        // close(). Construction itself stays outside the monitor because it
        // invokes plugin metadata callbacks. If creation wins, close observes
        // the claim (even before the manager is published) and fails safely;
        // if close wins, creation observes the terminal environment.
        synchronized (closeMonitor) {
            ensureOpen();
            if (!nodePluginManagerClaimed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "Plugin runtime environment already owns a NodePlugin manager");
            }
        }
        try {
            List<NodePlugin> plugins = build.registry().nodePluginInstances();
            PluginManager created = PluginManager.fromCatalog(
                    eventBus, scheduler, options, classLoader(), plugins,
                    build.selectedIds(), build.bundleConfigs(),
                    build.registry()::markNodePluginClosed,
                    build.registry()::registerContributionCleanup,
                    build.registry().callbackTracker(), lifecycleObserver);
            nodePluginManager = created;
            return created;
        } catch (Throwable failure) {
            synchronized (closeMonitor) {
                nodePluginManagerClaimed.set(false);
            }
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PluginCatalogActivationException(
                    "Plugin catalog NodePlugin manager creation failed", failure);
        }
    }

    public Set<String> selectedBundleIds() {
        ensureOpen();
        return build.selectedIds();
    }

    public List<String> selectedBundleOrder() {
        ensureOpen();
        return build.selectedOrder();
    }

    public Map<String, Map<String, Object>> bundleConfigs() {
        ensureOpen();
        return build.bundleConfigs();
    }

    /** Immutable configuration view for one selected bundle, or empty. */
    public Map<String, Object> bundleConfig(String bundleId) {
        ensureOpen();
        Objects.requireNonNull(bundleId, "bundleId");
        return build.bundleConfigs().getOrDefault(bundleId, Map.of());
    }

    /**
     * Safe configuration projected into an ADR-011.3 domain context.
     *
     * <p>v1 deliberately exposes no raw bundle values. The catalog namespace
     * can contain credentials, while the domain SPI does not yet define a
     * typed opaque secret-reference/capability value. Returning an empty view
     * fails closed until that contract exists instead of relying on key-name
     * heuristics that can copy secret material into a request-facing plugin.</p>
     */
    public Map<String, Object> domainApiConfig(String bundleId) {
        ensureOpen();
        Objects.requireNonNull(bundleId, "bundleId");
        return Map.of();
    }

    public ClassLoader classLoader() {
        ensureOpen();
        return loaderHandle.classLoader();
    }

    /** True while an app-chain product may still invoke bundle code. */
    public boolean hasPendingContributionCleanup() {
        ensureOpen();
        return build.registry().hasPendingContributionCleanup();
    }

    /**
     * Keep NodePlugin lifecycle, providers and the bundle class loader alive
     * until every returned product callback/close has reached its terminal
     * barrier. In-process code cannot be safely unloaded while it is running.
     */
    public void awaitContributionCleanup() {
        ensureOpen();
        build.registry().awaitContributionCleanup();
    }

    /** Reopen typed SPI callback admission for a restarted runtime cycle. */
    public void resumeContributionCallbacks() {
        ensureOpen();
        build.registry().resumeContributionCallbacks();
    }

    /** Reject typed SPI callback admission while the runtime is stopping. */
    public void sealContributionCallbacks() {
        ensureOpen();
        build.registry().sealContributionCallbacks();
    }

    /** Fail fast rather than allowing a contribution callback to wait for itself. */
    public void requireContributionTeardownAllowed(String action) {
        // Keep this check live while close() is in progress. Provider close
        // callbacks run after the public environment has become terminal, and
        // must still be prevented from re-entering node/plugin teardown and
        // synchronously waiting on their own close operation.
        build.registry().requireContributionTeardownAllowed(action);
    }

    /** True while a typed SPI callback admitted before the seal is still running. */
    public boolean hasPendingContributionCallbacks() {
        ensureOpen();
        return build.registry().hasPendingContributionCallbacks();
    }

    /** Wait for typed SPI callbacks admitted before the seal to return. */
    public void awaitContributionCallbacks() {
        ensureOpen();
        build.registry().awaitContributionCallbacks();
    }

    @Override
    public void close() {
        requireContributionTeardownAllowed("close the plugin environment");
        synchronized (closeMonitor) {
            boolean interrupted = false;
            while (closeInProgress) {
                if (closeOwner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "Cannot close the plugin environment recursively");
                }
                try {
                    closeMonitor.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (closed.get()) {
                rethrowCloseFailure(terminalCloseFailure);
                return;
            }
            PluginManager manager = nodePluginManager;
            if (nodePluginManagerClaimed.get()
                    && (manager == null || !manager.isTerminallyClosed())) {
                throw new IllegalStateException(
                        "Close the NodePlugin manager before closing its plugin environment");
            }
            closeInProgress = true;
            closeOwner = Thread.currentThread();
            // Reject public access from the linearization point onward while
            // concurrent close callers wait for terminal cleanup below.
            closed.set(true);
        }
        Throwable failure = null;
        try {
            build.registry().sealContributionCallbacks();
            build.registry().awaitContributionCallbacks();
        } catch (Throwable callbackFailure) {
            // Closing from inside an admitted plugin callback cannot safely
            // wait for itself. Leave providers and the loader intact so a
            // host thread can retry after that callback returns.
            finishClose(false, null);
            rethrowCloseFailure(callbackFailure);
            return;
        }
        try {
            build.registry().awaitContributionCleanup();
        } catch (Throwable contributionFailure) {
            failure = recordCloseFailure(failure, contributionFailure);
        }
        try {
            build.registry().close();
        } catch (Throwable providerFailure) {
            failure = recordCloseFailure(failure, providerFailure);
        }
        try {
            loaderHandle.closeClaimed();
        } catch (Throwable loaderFailure) {
            failure = recordCloseFailure(failure, loaderFailure);
        }
        finishClose(true, failure);
        rethrowCloseFailure(failure);
    }

    private void finishClose(boolean terminal, Throwable failure) {
        synchronized (closeMonitor) {
            if (!terminal) {
                closed.set(false);
            } else {
                terminalCloseFailure = failure;
            }
            closeInProgress = false;
            closeOwner = null;
            closeMonitor.notifyAll();
        }
    }

    private static Throwable recordCloseFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) {
            throw new IllegalStateException("Plugin environment close failed", failure);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Plugin runtime environment is closed");
        }
    }

    private record DiscoveryResult(
            List<PluginCatalogBuilder.CatalogInput> inputs,
            boolean allowUnindexedLegacyProviders,
            String embeddedIndexSha256
    ) {
        private DiscoveryResult {
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(embeddedIndexSha256, "embeddedIndexSha256");
        }

        private static DiscoveryResult disabled() {
            return new DiscoveryResult(List.of(), false, "none");
        }
    }

    @FunctionalInterface
    interface PostBuildAction {
        void run(
                String embeddedIndexSha256,
                PluginCatalogBuilder.BuildResult result
        ) throws Throwable;
    }
}
