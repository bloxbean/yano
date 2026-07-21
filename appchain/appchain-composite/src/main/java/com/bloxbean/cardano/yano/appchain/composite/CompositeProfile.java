package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable effective composition profile committed by {@link CompositeStateMachine}. */
public record CompositeProfile(
        int schemaVersion,
        String profileId,
        String profileVersion,
        List<ComponentDescriptor> components,
        List<WorkflowDescriptor> workflows,
        List<LegacyQueryAlias> queryAliases,
        AggregateQueryLimitsV1 aggregateQueryLimits
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_COMPONENTS = 64;
    public static final int MAX_WORKFLOWS = 32;
    public static final int MAX_ALIASES = 64;

    public CompositeProfile {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported composite profile schemaVersion: " + schemaVersion);
        }
        profileId = CompositeValidation.id(profileId, "profileId");
        profileVersion = CompositeValidation.printable(profileVersion, "profileVersion");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        workflows = Objects.requireNonNull(workflows, "workflows").stream()
                .sorted(Comparator.comparing(WorkflowDescriptor::workflowId)
                        .thenComparingLong(WorkflowDescriptor::fromHeight)
                        .thenComparing(WorkflowDescriptor::semanticVersion))
                .toList();
        queryAliases = Objects.requireNonNull(queryAliases, "queryAliases").stream()
                .sorted(Comparator.comparing(LegacyQueryAlias::aliasPath))
                .toList();
        aggregateQueryLimits = Objects.requireNonNull(aggregateQueryLimits, "aggregateQueryLimits");
        validateBounds(components, workflows, queryAliases);
        validateComponents(components);
        validateWorkflows(components, workflows);
        validateAliases(components, queryAliases);
    }

    public static CompositeProfile of(
            String profileId,
            String profileVersion,
            List<ComponentDescriptor> components
    ) {
        return new CompositeProfile(SCHEMA_VERSION, profileId, profileVersion, components,
                List.of(), List.of(), AggregateQueryLimitsV1.DEFAULT);
    }

    public byte[] canonicalBytes() {
        return CompositeProfileCodec.encode(this);
    }

    public byte[] digest() {
        return CompositeProfileCodec.digest(this);
    }

    public void validateEffectBudget(int frameworkMaxEffectsPerBlock) {
        if (frameworkMaxEffectsPerBlock < 1 || frameworkMaxEffectsPerBlock > 1_048_576) {
            throw new IllegalArgumentException("frameworkMaxEffectsPerBlock must be between 1 and 1048576");
        }
        Set<Long> boundaries = new TreeSet<>();
        components.forEach(component -> {
            boundaries.add(component.fromHeight());
            if (component.untilHeight() != 0) {
                boundaries.add(component.untilHeight());
            }
        });
        workflows.forEach(workflow -> {
            boundaries.add(workflow.fromHeight());
            if (workflow.untilHeight() != 0) {
                boundaries.add(workflow.untilHeight());
            }
        });
        for (long height : boundaries) {
            // A CHAIN result may arrive after its emitting generation became
            // inactive. Its callback still executes against that exact retained
            // generation and may emit continuations before current components
            // run. Reserve every generation that has started, not only the
            // currently active generation, so delayed callbacks cannot consume
            // capacity promised to the active profile. V1 keeps this reservation
            // conservatively for the remaining life of the fixed profile.
            long required = components.stream()
                    .filter(component -> component.fromHeight() <= height)
                    .mapToLong(ComponentDescriptor::maxEffectsPerBlock).sum();
            required += workflows.stream().filter(workflow -> workflow.activeAt(height))
                    .mapToLong(WorkflowDescriptor::maxEffectsPerBlock).sum();
            if (required > frameworkMaxEffectsPerBlock) {
                throw new IllegalArgumentException("composite effect quota " + required
                        + " exceeds effects.max-per-block " + frameworkMaxEffectsPerBlock
                        + " at height " + height);
            }
        }
    }

    private static void validateBounds(
            List<ComponentDescriptor> components,
            List<WorkflowDescriptor> workflows,
            List<LegacyQueryAlias> aliases
    ) {
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("profile may contain at most " + MAX_COMPONENTS + " components");
        }
        if (workflows.size() > MAX_WORKFLOWS) {
            throw new IllegalArgumentException("profile may contain at most " + MAX_WORKFLOWS + " workflows");
        }
        if (aliases.size() > MAX_ALIASES) {
            throw new IllegalArgumentException("profile may contain at most " + MAX_ALIASES + " query aliases");
        }
    }

    private static void validateComponents(List<ComponentDescriptor> components) {
        Set<ComponentGeneration> generations = new HashSet<>();
        for (ComponentDescriptor component : components) {
            if (!generations.add(component.generation())) {
                throw new IllegalArgumentException("duplicate component generation: " + component.generation());
            }
        }
        for (int leftIndex = 0; leftIndex < components.size(); leftIndex++) {
            ComponentDescriptor left = components.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < components.size(); rightIndex++) {
                ComponentDescriptor right = components.get(rightIndex);
                if (left.componentId().equals(right.componentId())
                        && !left.stateAndResultCompatibilityId().equals(
                        right.stateAndResultCompatibilityId())) {
                    throw new IllegalArgumentException("replacement generations for component "
                            + left.componentId() + " must share one state/result compatibility identity");
                }
                if (!CompositeValidation.overlaps(left.fromHeight(), left.untilHeight(),
                        right.fromHeight(), right.untilHeight())) {
                    continue;
                }
                if (left.componentId().equals(right.componentId())) {
                    throw new IllegalArgumentException("overlapping generations for component "
                            + left.componentId());
                }
                Set<String> duplicateTopics = new HashSet<>(left.topics());
                duplicateTopics.retainAll(right.topics());
                if (!duplicateTopics.isEmpty()) {
                    throw new IllegalArgumentException("overlapping component topic ownership: "
                            + duplicateTopics.stream().sorted().toList());
                }
            }
        }
    }

    private static void validateWorkflows(
            List<ComponentDescriptor> components,
            List<WorkflowDescriptor> workflows
    ) {
        Set<ComponentGeneration> available = components.stream()
                .map(ComponentDescriptor::generation).collect(java.util.stream.Collectors.toSet());
        for (WorkflowDescriptor workflow : workflows) {
            if (!available.containsAll(workflow.participants())) {
                List<ComponentGeneration> missing = new ArrayList<>(workflow.participants());
                missing.removeAll(available);
                throw new IllegalArgumentException("workflow references missing component generations: " + missing);
            }
            for (ComponentGeneration participant : workflow.participants()) {
                ComponentDescriptor descriptor = components.stream()
                        .filter(component -> component.generation().equals(participant))
                        .findFirst().orElseThrow();
                boolean coversStart = descriptor.fromHeight() <= workflow.fromHeight();
                boolean coversEnd = descriptor.untilHeight() == 0
                        || (workflow.untilHeight() != 0
                        && descriptor.untilHeight() >= workflow.untilHeight());
                if (!coversStart || !coversEnd) {
                    throw new IllegalArgumentException("workflow activation is not covered by participant "
                            + participant);
                }
            }
            for (ComponentDescriptor component : components) {
                if (component.topics().contains(workflow.topic())
                        && CompositeValidation.overlaps(workflow.fromHeight(), workflow.untilHeight(),
                        component.fromHeight(), component.untilHeight())) {
                    throw new IllegalArgumentException("workflow topic overlaps component route: "
                            + workflow.topic());
                }
            }
        }
        for (int leftIndex = 0; leftIndex < workflows.size(); leftIndex++) {
            WorkflowDescriptor left = workflows.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < workflows.size(); rightIndex++) {
                WorkflowDescriptor right = workflows.get(rightIndex);
                if (left.workflowId().equals(right.workflowId())
                        && CompositeValidation.overlaps(left.fromHeight(), left.untilHeight(),
                        right.fromHeight(), right.untilHeight())) {
                    throw new IllegalArgumentException("overlapping generations for workflow "
                            + left.workflowId());
                }
                if (left.topic().equals(right.topic())
                        && CompositeValidation.overlaps(left.fromHeight(), left.untilHeight(),
                        right.fromHeight(), right.untilHeight())) {
                    throw new IllegalArgumentException("overlapping workflow topic ownership: " + left.topic());
                }
            }
        }
    }

    private static void validateAliases(
            List<ComponentDescriptor> components,
            List<LegacyQueryAlias> aliases
    ) {
        if (aliases.stream().map(LegacyQueryAlias::aliasPath).distinct().count() != aliases.size()) {
            throw new IllegalArgumentException("query alias paths must be unique");
        }
        for (LegacyQueryAlias alias : aliases) {
            boolean declared = components.stream().anyMatch(component ->
                    component.componentId().equals(alias.componentId())
                            && component.queryPaths().contains(alias.localPath()));
            if (!declared) {
                throw new IllegalArgumentException("query alias targets an undeclared component path: "
                        + alias.aliasPath());
            }
        }
    }
}
