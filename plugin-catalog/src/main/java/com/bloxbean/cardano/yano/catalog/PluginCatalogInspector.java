package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Performs deterministic structural catalog validation without loading any
 * provider class.
 */
public final class PluginCatalogInspector {
    private static final int MAX_DIAGNOSTIC_IDS = 8;

    private final PluginIndexGenerator generator;

    /** Creates an inspector backed by the strict artifact scanner. */
    public PluginCatalogInspector() {
        this(new PluginIndexGenerator());
    }

    PluginCatalogInspector(PluginIndexGenerator generator) {
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    /**
     * Scans and inspects plugin artifacts without loading provider code.
     *
     * @param artifacts JARs or exploded artifact directories
     * @param policy API and selection policy
     * @return immutable deterministic catalog view
     * @throws IOException if artifact evidence cannot be read safely
     * @throws PluginCatalogException if structural catalog validation fails
     */
    public PluginCatalogInspection inspect(
            Collection<Path> artifacts,
            PluginCatalogInspectionPolicy policy
    ) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        return inspect(generator.generate(artifacts), policy);
    }

    /**
     * Inspects an already-scanned index without loading provider code.
     *
     * @param index canonical aggregate index
     * @param policy API and selection policy
     * @return immutable deterministic catalog view
     * @throws PluginCatalogException if structural catalog validation fails
     */
    public PluginCatalogInspection inspect(
            PluginIndex index,
            PluginCatalogInspectionPolicy policy
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(policy, "policy");
        if (!index.legacyProviders().isEmpty()) {
            throw invalid("Offline catalog inspection requires bundle manifests; "
                    + "unmanifested legacy providers cannot be identified without loading code");
        }

        Map<String, IndexedBundle> discovered = new TreeMap<>();
        for (IndexedBundle indexed : index.bundles()) {
            BundleManifest manifest = indexed.manifest();
            if (!manifest.yanoApi().supports(
                    policy.pluginApiMajor(), policy.pluginApiLevel())) {
                throw invalid("Plugin bundle '" + manifest.id()
                        + "' does not support Yano plugin API major "
                        + policy.pluginApiMajor() + " level " + policy.pluginApiLevel());
            }
            validateReservedNames(manifest);
            discovered.put(manifest.id(), indexed);
        }

        Set<String> missingAllowed = new TreeSet<>(policy.allowList());
        missingAllowed.removeAll(policy.denyList());
        missingAllowed.removeAll(discovered.keySet());
        if (!missingAllowed.isEmpty()) {
            throw invalid("Allow-listed plugin bundles were not discovered: "
                    + boundedIds(missingAllowed));
        }

        Map<String, IndexedBundle> selected = new TreeMap<>();
        Map<String, PluginSelectionStatus> statuses = new TreeMap<>();
        for (Map.Entry<String, IndexedBundle> entry : discovered.entrySet()) {
            PluginSelectionStatus status = selectionStatus(policy, entry.getKey());
            statuses.put(entry.getKey(), status);
            if (status == PluginSelectionStatus.SELECTED) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        validateSelectedDependencies(selected);
        validateSelectedContributionKeys(selected.values());
        List<IndexedBundle> ordered = topologicalOrder(selected);

        List<PluginBundleInfo> inventory = discovered.entrySet().stream()
                .map(entry -> inventory(entry.getValue(), statuses.get(entry.getKey())))
                .toList();
        List<String> selectedOrder = ordered.stream()
                .map(indexed -> indexed.manifest().id())
                .toList();
        return new PluginCatalogInspection(
                policy.pluginApiMajor(),
                policy.pluginApiLevel(),
                fingerprint(policy.pluginApiMajor(), policy.pluginApiLevel(), ordered),
                inventory,
                selectedOrder);
    }

    private static PluginSelectionStatus selectionStatus(
            PluginCatalogInspectionPolicy policy,
            String bundleId
    ) {
        if (policy.denyList().contains(bundleId)) {
            return PluginSelectionStatus.DENIED;
        }
        if (!policy.allowList().isEmpty() && !policy.allowList().contains(bundleId)) {
            return PluginSelectionStatus.NOT_ALLOW_LISTED;
        }
        return PluginSelectionStatus.SELECTED;
    }

    private static void validateSelectedDependencies(Map<String, IndexedBundle> selected) {
        for (IndexedBundle indexed : selected.values()) {
            BundleManifest manifest = indexed.manifest();
            for (BundleDependency dependency : manifest.dependencies()) {
                IndexedBundle target = selected.get(dependency.id());
                if (target == null) {
                    throw invalid("Plugin bundle '" + manifest.id()
                            + "' requires unavailable selected bundle '"
                            + dependency.id() + "'");
                }
                if (!dependency.accepts(target.manifest().version())) {
                    throw invalid("Plugin bundle '" + manifest.id()
                            + "' requires an incompatible version of bundle '"
                            + dependency.id() + "'");
                }
            }
        }
    }

    private static void validateSelectedContributionKeys(Collection<IndexedBundle> selected) {
        Map<String, String> owners = new TreeMap<>();
        for (IndexedBundle indexed : selected) {
            for (BundleContribution contribution : indexed.manifest().contributions()) {
                String key = contribution.kind().manifestKey() + "/" + contribution.name();
                String previous = owners.putIfAbsent(key, indexed.manifest().id());
                if (previous != null) {
                    throw invalid("Duplicate selected contribution '" + key
                            + "' from bundles '" + previous + "' and '"
                            + indexed.manifest().id() + "'");
                }
            }
        }
    }

    private static List<IndexedBundle> topologicalOrder(Map<String, IndexedBundle> selected) {
        Map<String, Integer> indegree = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        selected.keySet().forEach(id -> {
            indegree.put(id, 0);
            dependents.put(id, new TreeSet<>());
        });
        for (IndexedBundle indexed : selected.values()) {
            for (BundleDependency dependency : indexed.manifest().dependencies()) {
                indegree.compute(indexed.manifest().id(), (ignored, value) -> value + 1);
                dependents.get(dependency.id()).add(indexed.manifest().id());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<IndexedBundle> ordered = new ArrayList<>(selected.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(selected.get(id));
            for (String dependent : dependents.get(id)) {
                int degree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() != selected.size()) {
            Set<String> cyclic = new TreeSet<>(selected.keySet());
            ordered.forEach(indexed -> cyclic.remove(indexed.manifest().id()));
            throw invalid("Plugin bundle dependency cycle among selected bundles: "
                    + boundedIds(cyclic));
        }
        return List.copyOf(ordered);
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
                throw invalid("Plugin bundle '" + manifest.id()
                        + "' declares reserved SYSTEM contribution '"
                        + contribution.kind().manifestKey() + "/"
                        + contribution.name() + "'");
            }
        }
    }

    private static PluginBundleInfo inventory(
            IndexedBundle indexed,
            PluginSelectionStatus status
    ) {
        BundleManifest manifest = indexed.manifest();
        List<PluginContributionInfo> contributions = manifest.contributions().stream()
                .sorted(Comparator.comparing(
                                (BundleContribution value) -> value.kind().manifestKey())
                        .thenComparing(BundleContribution::name)
                        .thenComparing(BundleContribution::provider))
                .map(value -> new PluginContributionInfo(
                        value.kind().manifestKey(), value.name(), value.provider(),
                        trust(value.kind())))
                .toList();
        // Offline artifacts use DIRECTORY because they represent the same
        // operator-supplied drop-in input category as runtime plugin-dir
        // discovery. JAR versus exploded tree remains explicit in digestMode.
        return new PluginBundleInfo(
                manifest.id(), manifest.version().toString(),
                status == PluginSelectionStatus.SELECTED, status, false,
                PluginSourceCategory.DIRECTORY, indexed.digest(), indexed.digestMode(),
                manifest.dependencies().stream().map(BundleDependency::id).sorted().toList(),
                contributions);
    }

    private static PluginTrustTier trust(ContributionKind kind) {
        return switch (kind) {
            case NODE_PLUGIN -> PluginTrustTier.REQUIRED;
            case APP_STATE_MACHINE, SEQUENCER_MODE, L1_OBSERVER ->
                    PluginTrustTier.CONSENSUS;
            case SIGNER_PROVIDER, EFFECT_EXECUTOR, DOMAIN_API ->
                    PluginTrustTier.PRIVILEGED_LOCAL;
            case FINALIZED_SINK, HEALTH, METRICS -> PluginTrustTier.AUXILIARY_LOCAL;
        };
    }

    private static String fingerprint(
            int apiMajor,
            int apiLevel,
            List<IndexedBundle> ordered
    ) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeInt(apiMajor);
                data.writeInt(apiLevel);
                data.writeInt(ordered.size());
                for (IndexedBundle indexed : ordered) {
                    BundleManifest manifest = indexed.manifest();
                    text(data, manifest.id());
                    text(data, manifest.version().toString());
                    data.writeBoolean(false);
                    text(data, indexed.digest());
                    text(data, indexed.digestMode().name());
                    data.writeInt(manifest.schemaVersion());
                    data.writeInt(manifest.yanoApi().min());
                    data.writeInt(manifest.yanoApi().max());
                    data.writeInt(manifest.yanoApi().minLevel());
                    List<BundleDependency> dependencies = manifest.dependencies().stream()
                            .sorted(Comparator.comparing(BundleDependency::id))
                            .toList();
                    data.writeInt(dependencies.size());
                    for (BundleDependency dependency : dependencies) {
                        text(data, dependency.id());
                        text(data, dependency.minVersion() == null
                                ? "" : dependency.minVersion().toString());
                        text(data, dependency.maxVersionExclusive() == null
                                ? "" : dependency.maxVersionExclusive().toString());
                    }
                    List<BundleContribution> contributions = manifest.contributions().stream()
                            .sorted(Comparator.comparing(
                                            (BundleContribution value) ->
                                                    value.kind().manifestKey())
                                    .thenComparing(BundleContribution::name)
                                    .thenComparing(BundleContribution::provider))
                            .toList();
                    data.writeInt(contributions.size());
                    for (BundleContribution contribution : contributions) {
                        text(data, contribution.kind().manifestKey());
                        text(data, contribution.name());
                        text(data, contribution.provider());
                        text(data, trust(contribution.kind()).name());
                    }
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "Could not calculate plugin catalog fingerprint", impossible);
        }
    }

    private static void text(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String boundedIds(Collection<String> ids) {
        List<String> values = ids.stream().sorted().limit(MAX_DIAGNOSTIC_IDS).toList();
        if (ids.size() <= MAX_DIAGNOSTIC_IDS) {
            return values.toString();
        }
        return values + " (and " + (ids.size() - MAX_DIAGNOSTIC_IDS) + " more)";
    }

    private static PluginCatalogException invalid(String message) {
        return new PluginCatalogException(message);
    }
}
