package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable digest-keyed set of executable composite profiles owned by one bundle. */
public final class CompositeProfileCatalog {
    public static final int MAX_ENTRIES = 64;

    private final Map<String, Entry> entries;
    private final Map<ComponentGeneration, ComponentProduct> componentProducts;
    private final Map<WorkflowIdentity, WorkflowProduct> workflowProducts;

    public CompositeProfileCatalog(List<Entry> supplied, int frameworkMaxEffectsPerBlock) {
        this(supplied, frameworkMaxEffectsPerBlock, null);
    }

    public CompositeProfileCatalog(List<Entry> supplied,
                                   int frameworkMaxEffectsPerBlock,
                                   Integer governedResultDrainBlocks) {
        List<Entry> safe = List.copyOf(Objects.requireNonNull(supplied, "supplied"));
        if (safe.isEmpty() || safe.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("composite catalog must contain 1-64 entries");
        }
        // Canonicalize independently of provider/catalog construction order.
        // Lifecycle initialization follows this digest order, then each
        // profile's declared, canonically encoded component/workflow order.
        List<Entry> sortedEntries = safe.stream()
                .sorted(Comparator.comparing(entry ->
                        HexFormat.of().formatHex(entry.profile().digest())))
                .toList();
        Map<String, Entry> byDigest = new LinkedHashMap<>();
        Map<ComponentGeneration, ComponentProduct> components = new LinkedHashMap<>();
        Map<String, String> namespaceCompatibility = new LinkedHashMap<>();
        Map<WorkflowIdentity, WorkflowProduct> workflows = new LinkedHashMap<>();
        for (Entry entry : sortedEntries) {
            if (governedResultDrainBlocks == null) {
                entry.profile().validateEffectBudget(frameworkMaxEffectsPerBlock);
            } else {
                entry.profile().validateGovernedEffectBudget(
                        frameworkMaxEffectsPerBlock, governedResultDrainBlocks);
            }
            byte[] canonical = entry.profile().canonicalBytes();
            CompositeProfile decoded = CompositeProfileCodec.decode(canonical);
            if (!decoded.equals(entry.profile())) {
                throw new IllegalArgumentException("composite catalog profile does not round-trip");
            }
            String digest = HexFormat.of().formatHex(entry.profile().digest());
            if (byDigest.putIfAbsent(digest, entry) != null) {
                throw new IllegalArgumentException("duplicate composite catalog profile digest");
            }
            for (int index = 0; index < entry.machines().size(); index++) {
                ComponentDescriptor descriptor = entry.profile().components().get(index);
                AppStateMachine product = entry.machines().get(index);
                String previousCompatibility = namespaceCompatibility.putIfAbsent(
                        descriptor.componentId(), descriptor.stateAndResultCompatibilityId());
                if (previousCompatibility != null && !previousCompatibility.equals(
                        descriptor.stateAndResultCompatibilityId())) {
                    throw new IllegalArgumentException(
                            "catalog reuses one component namespace with incompatible state/result contracts: "
                                    + descriptor.componentId());
                }
                ComponentProduct existing = components.putIfAbsent(descriptor.generation(),
                        new ComponentProduct(descriptor, product));
                if (existing != null && (!existing.descriptor().equals(descriptor)
                        || existing.product() != product)) {
                    throw new IllegalArgumentException(
                            "catalog represents one component generation inconsistently: "
                                    + descriptor.generation());
                }
            }
            for (int index = 0; index < entry.workflows().size(); index++) {
                WorkflowDescriptor descriptor = entry.profile().workflows().get(index);
                CompositeWorkflow product = entry.workflows().get(index);
                WorkflowIdentity identity = new WorkflowIdentity(
                        descriptor.workflowId(), descriptor.fromHeight());
                WorkflowProduct existing = workflows.putIfAbsent(identity,
                        new WorkflowProduct(descriptor, product));
                if (existing != null && (!existing.descriptor().equals(descriptor)
                        || existing.product() != product)) {
                    throw new IllegalArgumentException(
                            "catalog represents one workflow generation inconsistently: " + identity);
                }
            }
        }
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(byDigest));
        this.componentProducts = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        this.workflowProducts = Collections.unmodifiableMap(new LinkedHashMap<>(workflows));
    }

    public Optional<Entry> find(byte[] digest) {
        if (digest == null || digest.length != 32) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(HexFormat.of().formatHex(digest)));
    }

    public Entry require(byte[] digest) {
        return find(digest).orElseThrow(() ->
                new IllegalStateException("composite profile digest is absent from executable catalog"));
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    Map<ComponentGeneration, ComponentProduct> componentProducts() {
        return componentProducts;
    }

    List<AppStateMachine> uniqueComponents() {
        Set<AppStateMachine> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<AppStateMachine> result = new ArrayList<>();
        for (ComponentProduct product : componentProducts.values()) {
            if (seen.add(product.product())) {
                result.add(product.product());
            }
        }
        return List.copyOf(result);
    }

    public record Entry(CompositeProfile profile,
                        List<AppStateMachine> machines,
                        List<CompositeWorkflow> workflows) {
        public Entry {
            profile = Objects.requireNonNull(profile, "profile");
            machines = List.copyOf(Objects.requireNonNull(machines, "machines"));
            workflows = List.copyOf(Objects.requireNonNull(workflows, "workflows"));
            if (profile.components().size() != machines.size()
                    || machines.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "catalog machine products must exactly match profile component order");
            }
            List<WorkflowDescriptor> workflowDescriptors = workflows.stream()
                    .map(CompositeWorkflow::descriptor).map(Objects::requireNonNull).toList();
            if (!profile.workflows().equals(workflowDescriptors)) {
                throw new IllegalArgumentException(
                        "catalog workflow products must exactly match profile order");
            }
        }

        public byte[] digest() {
            return profile.digest();
        }
    }

    record ComponentProduct(ComponentDescriptor descriptor, AppStateMachine product) {
    }

    record WorkflowProduct(WorkflowDescriptor descriptor, CompositeWorkflow product) {
    }

    private record WorkflowIdentity(String workflowId, long fromHeight) {
    }
}
