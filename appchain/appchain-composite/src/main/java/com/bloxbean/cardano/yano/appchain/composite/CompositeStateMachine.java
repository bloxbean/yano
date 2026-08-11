package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityIds;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryCodecV1;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic routing, isolation, query, and effect-ownership boundary. */
public final class CompositeStateMachine implements AppStateMachine {
    public static final String ID = "composite";

    private final String machineId;
    private final CompositeProfile profile;
    private final byte[] profileBytes;
    private final CompositeProfileCatalog catalog;
    private final Map<String, RuntimeEntry> runtimesByDigest;
    private final List<ComponentBinding> allComponents;
    private final Map<ComponentGeneration, ComponentBinding> componentsByGeneration;
    private final List<WorkflowBinding> allWorkflows;
    private final CompositeProfileGovernanceRuntime governance;

    /**
     * Constructs a composite using the actual framework effect cap from the
     * chain context. Custom bundles use this factory so quota validation cannot
     * be accidentally omitted.
     */
    public static CompositeStateMachine create(
            AppStateMachineContext context,
            CompositeProfile profile,
            List<AppStateMachine> machines,
            List<CompositeWorkflow> workflows
    ) {
        return create(ID, context, profile, machines, workflows);
    }

    /**
     * Constructs a reusable composite core for a custom provider whose
     * selector ID is distinct from the stock {@code composite} provider.
     */
    public static CompositeStateMachine create(
            String machineId,
            AppStateMachineContext context,
            CompositeProfile profile,
            List<AppStateMachine> machines,
            List<CompositeWorkflow> workflows
    ) {
        Objects.requireNonNull(context, "context");
        int frameworkMaxEffects = context.consensusProfile().orElseThrow(() ->
                new IllegalArgumentException(
                        "composite requires AppStateMachineContext.consensusProfile() (ADR-016)"))
                .effectsMaxPerBlock();
        CompositeGovernanceConfig config = governanceConfig(context);
        verifyGovernedMembershipMode(context, config);
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(
                List.of(new CompositeProfileCatalog.Entry(profile, machines, workflows)),
                frameworkMaxEffects,
                config.mode() == CompositeGovernanceConfig.ProfileMode.GOVERNED
                        ? config.resultDrainBlocks() : null);
        return new CompositeStateMachine(machineId, context.chainId(), catalog,
                profile.digest(), frameworkMaxEffects, config,
                context.membershipView().orElse(null));
    }

    /**
     * Constructs a fixed or governed composite from one immutable executable
     * catalog. The initial digest is the genesis profile; dormant entries can
     * become active only through the governed protocol.
     */
    public static CompositeStateMachine create(
            String machineId,
            AppStateMachineContext context,
            CompositeProfileCatalog catalog,
            byte[] initialProfileDigest
    ) {
        Objects.requireNonNull(context, "context");
        int frameworkMaxEffects = context.consensusProfile().orElseThrow(() ->
                new IllegalArgumentException(
                        "composite requires AppStateMachineContext.consensusProfile() (ADR-016)"))
                .effectsMaxPerBlock();
        CompositeGovernanceConfig config = governanceConfig(context);
        verifyGovernedMembershipMode(context, config);
        return new CompositeStateMachine(machineId, context.chainId(), catalog,
                initialProfileDigest, frameworkMaxEffects, config,
                context.membershipView().orElse(null));
    }

    public static CompositeStateMachine create(
            AppStateMachineContext context,
            CompositeProfileCatalog catalog,
            byte[] initialProfileDigest
    ) {
        return create(ID, context, catalog, initialProfileDigest);
    }

    static CompositeStateMachine forTest(
            CompositeProfile profile,
            List<AppStateMachine> machines,
            List<CompositeWorkflow> workflows,
            int frameworkMaxEffects
    ) {
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(
                List.of(new CompositeProfileCatalog.Entry(profile, machines, workflows)),
                frameworkMaxEffects);
        return new CompositeStateMachine(ID, "test-chain", catalog, profile.digest(),
                frameworkMaxEffects, new CompositeGovernanceConfig(
                CompositeGovernanceConfig.ProfileMode.FIXED, 20, 600, 1_024, 600), null);
    }

    private CompositeStateMachine(
            String machineId,
            String chainId,
            CompositeProfileCatalog catalog,
            byte[] initialProfileDigest,
            int frameworkMaxEffects,
            CompositeGovernanceConfig governanceConfig,
            com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView membershipView
    ) {
        this.machineId = CompositeValidation.id(machineId, "machineId");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        CompositeProfileCatalog.Entry initial = catalog.require(initialProfileDigest);
        this.profile = initial.profile();
        this.profileBytes = profile.canonicalBytes();
        Map<String, RuntimeEntry> runtimes = new LinkedHashMap<>();
        Map<ComponentGeneration, ComponentBinding> byGeneration = new LinkedHashMap<>();
        Map<WorkflowDescriptor, WorkflowBinding> uniqueWorkflows = new LinkedHashMap<>();
        for (CompositeProfileCatalog.Entry entry : catalog.entries()) {
            List<ComponentBinding> bindings = new ArrayList<>(entry.machines().size());
            for (int index = 0; index < entry.machines().size(); index++) {
                ComponentBinding binding = new ComponentBinding(
                        entry.profile().components().get(index), entry.machines().get(index));
                bindings.add(binding);
                byGeneration.putIfAbsent(binding.descriptor().generation(), binding);
            }
            List<WorkflowBinding> workflowBindings = new ArrayList<>(entry.workflows().size());
            for (int index = 0; index < entry.workflows().size(); index++) {
                WorkflowBinding binding = new WorkflowBinding(
                        entry.profile().workflows().get(index), entry.workflows().get(index));
                workflowBindings.add(binding);
                uniqueWorkflows.putIfAbsent(binding.descriptor(), binding);
            }
            RuntimeEntry runtime = new RuntimeEntry(entry.profile(), List.copyOf(bindings),
                    List.copyOf(workflowBindings));
            runtimes.put(HexFormat.of().formatHex(entry.digest()), runtime);
        }
        this.runtimesByDigest = Map.copyOf(runtimes);
        this.componentsByGeneration = Map.copyOf(byGeneration);
        this.allComponents = List.copyOf(byGeneration.values());
        this.allWorkflows = List.copyOf(uniqueWorkflows.values());
        if (governanceConfig.mode() == CompositeGovernanceConfig.ProfileMode.FIXED) {
            if (catalog.entries().size() != 1) {
                throw new IllegalArgumentException("fixed composite mode requires a one-entry catalog");
            }
            this.governance = null;
        } else {
            if (membershipView == null) {
                throw new IllegalArgumentException(
                        "governed composite requires AppStateMachineContext.membershipView()");
            }
            this.governance = new CompositeProfileGovernanceRuntime(chainId, governanceConfig,
                    membershipView, catalog, frameworkMaxEffects, profileBytes);
        }
    }

    private static CompositeGovernanceConfig governanceConfig(AppStateMachineContext context) {
        var consensus = context.consensusProfile().orElseThrow();
        long resultWindow = consensus.effectsResultWindowBlocks();
        if (resultWindow < 0 || resultWindow > 10_000_000
                || (consensus.effectsEnabled() && resultWindow == 0)
                || (!consensus.effectsEnabled() && resultWindow != 0)) {
            throw new IllegalArgumentException(
                    "composite effect result window disagrees with the consensus profile");
        }
        return CompositeGovernanceConfig.from(context.settings(), (int) resultWindow);
    }

    private static void verifyGovernedMembershipMode(
            AppStateMachineContext context,
            CompositeGovernanceConfig config
    ) {
        if (config.mode() == CompositeGovernanceConfig.ProfileMode.GOVERNED
                && !"governed".equalsIgnoreCase(
                context.settings().getOrDefault("membership.mode", "static"))) {
            throw new IllegalArgumentException(
                    "governed composite profiles require membership.mode=governed");
        }
    }

    @Override
    public String id() {
        return machineId;
    }

    public CompositeProfile profile() {
        return profile;
    }

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.snapshot
            .AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
        Map<String, com.bloxbean.cardano.yano.api.appchain.snapshot
                .AuthenticatedSnapshotSeriesDescriptorV1> declared = new LinkedHashMap<>();
        for (ComponentBinding component : allComponents) {
            String componentId = component.descriptor().componentId();
            for (var series : component.product().authenticatedSnapshotSeries()) {
                String scopedId = componentId + "." + series.seriesId();
                var previous = declared.putIfAbsent(scopedId, series.withSeriesId(scopedId));
                if (previous != null && !previous.equals(series.withSeriesId(scopedId))) {
                    throw new IllegalStateException(
                            "incompatible authenticated snapshot declaration: " + scopedId);
                }
            }
        }
        return declared.values().stream().sorted(
                java.util.Comparator.comparing(com.bloxbean.cardano.yano.api.appchain.snapshot
                        .AuthenticatedSnapshotSeriesDescriptorV1::seriesId)).toList();
    }

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.snapshot
            .AuthenticatedSnapshotSourceCommitmentV1> authenticatedSnapshotSourceCommitments() {
        Map<String, com.bloxbean.cardano.yano.api.appchain.snapshot
                .AuthenticatedSnapshotSourceCommitmentV1> declared = new LinkedHashMap<>();
        for (ComponentBinding component : allComponents) {
            String componentId = component.descriptor().componentId();
            for (var source : component.product().authenticatedSnapshotSourceCommitments()) {
                String scopedId = componentId + "." + source.seriesId();
                var scoped = source.withSeriesId(scopedId);
                var previous = declared.putIfAbsent(scopedId, scoped);
                if (previous != null
                        && (!previous.algorithm().equals(scoped.algorithm())
                        || !previous.wireVersion().equals(scoped.wireVersion())
                        || !previous.compatibilityId().equals(scoped.compatibilityId()))) {
                    throw new IllegalStateException(
                            "incompatible authenticated snapshot source verifier: " + scopedId);
                }
            }
        }
        return declared.values().stream().sorted(java.util.Comparator.comparing(
                com.bloxbean.cardano.yano.api.appchain.snapshot
                        .AuthenticatedSnapshotSourceCommitmentV1::seriesId)).toList();
    }

    @Override
    public void init(AppStateReader state, AppChainInfo info) {
        if (governance != null) {
            governance.init(state);
        } else {
            verifyRetainedMarker(state);
        }
        for (ComponentBinding component : allComponents) {
            component.product().init(NamespacedStateViews.reader(
                    component.descriptor().componentId(), state), info);
        }
    }

    @Override
    public AdmissionResult validate(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message
    ) {
        return validateAgainstRuntime(message, runtimeFor(profileBytes), 1, false, null);
    }

    @Override
    public AdmissionResult validateForBlock(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message,
            long candidateHeight,
            AppStateReader committedState
    ) {
        if (candidateHeight < 1) {
            return AdmissionResult.reject("Invalid composite candidate height");
        }
        RuntimeEntry runtime = governance != null
                ? runtimeFor(governance.profileForCandidateHeight(candidateHeight, committedState))
                : runtimeFor(profileBytes);
        return validateAgainstRuntime(
                message, runtime, candidateHeight, true, committedState);
    }

    private AdmissionResult validateAgainstRuntime(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message,
            RuntimeEntry runtime,
            long candidateHeight,
            boolean candidateAware,
            AppStateReader committedState
    ) {
        String topic = message.getTopic();
        if (FxResultBody.TOPIC.equals(topic)) {
            return AdmissionResult.accept();
        }
        if (com.bloxbean.cardano.yano.appchain.composite.contracts
                .CompositeProfileGovernanceV1.TOPIC.equals(topic)) {
            return governance != null
                    ? AdmissionResult.accept()
                    : AdmissionResult.reject("Composite profile governance is disabled");
        }
        List<ComponentBinding> candidates = runtime.components().stream()
                .filter(component -> component.descriptor().activeAt(candidateHeight))
                .filter(component -> component.descriptor().topics().contains(topic))
                .toList();
        List<WorkflowBinding> workflowCandidates = runtime.workflows().stream()
                .filter(workflow -> workflow.descriptor().activeAt(candidateHeight))
                .filter(workflow -> workflow.descriptor().topic().equals(topic))
                .toList();
        if (topic != null && topic.startsWith("~")
                && !topic.startsWith(com.bloxbean.cardano.yano.api.appchain.l1view
                .L1Observation.TOPIC_PREFIX)
                && candidates.isEmpty()) {
            return AdmissionResult.reject("Unsupported composite framework topic");
        }
        if (candidates.isEmpty() && workflowCandidates.isEmpty()) {
            return AdmissionResult.reject("Unknown composite message topic");
        }
        for (ComponentBinding candidate : candidates) {
            AdmissionResult result = candidateAware
                    ? candidate.product().validateForBlock(
                    snapshot(message), candidateHeight,
                    NamespacedStateViews.reader(
                            candidate.descriptor().componentId(), committedState))
                    : candidate.product().validate(snapshot(message));
            if (!result.isAccepted()) {
                return result;
            }
        }
        for (WorkflowBinding workflow : workflowCandidates) {
            AdmissionResult result = candidateAware
                    ? workflow.product().validateForBlock(
                    snapshot(message), candidateHeight)
                    : workflow.product().validate(snapshot(message));
            if (!result.isAccepted()) {
                return result;
            }
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        AppBlock block = context.block();
        RuntimeEntry runtime = runtimeAtBlockStart(block, writer);
        if (governance != null) {
            governance.processCommands(block, writer);
            governance.captureOperationalStatus(writer, block.height());
        }
        for (ComponentBinding component : runtime.components()) {
            ComponentDescriptor descriptor = component.descriptor();
            if (!descriptor.activeAt(block.height())) {
                continue;
            }
            List<Integer> routed = routeIndexes(
                    context, descriptor.topics()::contains);
            component.product().apply(context.routeToMessageIndexes(routed),
                    NamespacedStateViews.writer(descriptor.componentId(), writer),
                    new OwnedEmitter(block.height(), descriptor, writer, effects));
        }
        for (WorkflowBinding workflow : runtime.workflows()) {
            WorkflowDescriptor descriptor = workflow.descriptor();
            if (!descriptor.activeAt(block.height())) {
                continue;
            }
            List<Integer> routed = routeIndexes(
                    context, topic -> descriptor.topic().equals(topic));
            workflow.product().apply(context.routeToMessageIndexes(routed),
                    new WorkflowContext(block.height(), descriptor, writer, effects));
        }
        clearQuotaCounters(block.height(), writer);
    }

    @Override
    public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        if (com.bloxbean.cardano.yano.appchain.composite.contracts
                .CompositeProfileGovernanceV1.TOPIC.equals(topic)) {
            if (governance == null) {
                return AdmissionResult.reject("Composite profile governance is disabled");
            }
            return governance.permitsLocalSubmission(body)
                    ? AdmissionResult.accept()
                    : AdmissionResult.reject(
                    "Invalid command or target profile is absent from the local executable catalog");
        }
        List<AppStateMachine> owners = allComponents.stream()
                .filter(component -> component.descriptor().topics().contains(topic))
                .map(ComponentBinding::product)
                .distinct()
                .toList();
        if (owners.isEmpty()) {
            return AdmissionResult.reject("No composite machine owns this privileged topic");
        }
        for (AppStateMachine owner : owners) {
            AdmissionResult result = owner.validatePrivilegedSystemSubmission(
                    topic, body != null ? body.clone() : null);
            if (result == null || !result.isAccepted()) {
                return result != null ? result
                        : AdmissionResult.reject("Composite machine returned no admission result");
            }
        }
        return AdmissionResult.accept();
    }

    @Override
    public Map<String, Object> operationalStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        if (governance != null) {
            status.putAll(governance.operationalStatus());
        } else {
            status.put("mode", "fixed");
            status.put("activeProfileDigest", HexFormat.of().formatHex(profile.digest()));
        }
        Map<String, Object> componentStatuses = new LinkedHashMap<>();
        for (ComponentBinding component : allComponents) {
            Map<String, Object> child = Objects.requireNonNull(
                    component.product().operationalStatus(),
                    "component operationalStatus");
            if (!child.isEmpty()) {
                ComponentGeneration generation = component.descriptor().generation();
                String key = generation.componentId() + "/" + generation.semanticVersion()
                        + "@" + generation.fromHeight();
                componentStatuses.put(key, Map.copyOf(child));
            }
        }
        if (!componentStatuses.isEmpty()) {
            status.put("components", Map.copyOf(componentStatuses));
        }
        return Map.copyOf(status);
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        AppCapabilityManifest.Builder manifest = AppCapabilityManifest.builder(
                machineId, profile.profileVersion());
        for (ComponentDescriptor component : profile.components()) {
            manifest.component(new AppCapabilityManifest.Component(
                    component.componentId(), component.semanticVersion(),
                    component.configurationId(),
                    "component/" + component.componentId() + "/v1",
                    component.topics(), component.queryPaths(),
                    AppCapabilityManifest.Origin.COMPOSED));
        }
        for (WorkflowDescriptor workflow : profile.workflows()) {
            manifest.workflow(new AppCapabilityManifest.Workflow(
                    workflow.workflowId(), workflow.semanticVersion(),
                    workflow.participants().stream()
                            .map(ComponentGeneration::componentId).distinct().toList(),
                    workflow.topic(), workflow.maxEffectsPerBlock() > 0
                    ? List.of("outbox") : List.of(),
                    AppCapabilityManifest.Origin.COMPOSED));
        }
        Set<String> componentIds = profile.components().stream()
                .map(ComponentDescriptor::componentId).collect(java.util.stream.Collectors.toSet());
        if (componentIds.contains("approvals")) {
            manifest.crossCutting(capability(AppCapabilityIds.BASIC_APPROVAL,
                    "composite-profile:" + HexFormat.of().formatHex(profile.digest())));
        }
        if (componentIds.contains("domain-actors")
                && componentIds.contains("role-approvals")) {
            manifest.crossCutting(capability(AppCapabilityIds.ACTOR_ROLE_APPROVAL,
                    "composite-profile:" + HexFormat.of().formatHex(profile.digest())));
        }
        if (componentIds.contains("authenticated-map")
                && componentIds.contains("domain-actors")) {
            manifest.crossCutting(capability(AppCapabilityIds.DIRECT_ROLE,
                    "composite-profile:" + HexFormat.of().formatHex(profile.digest())));
        }
        if (profile.components().stream().anyMatch(value -> value.maxEffectsPerBlock() > 0)
                || profile.workflows().stream().anyMatch(value -> value.maxEffectsPerBlock() > 0)) {
            manifest.crossCutting(capability(AppCapabilityIds.OUTBOX_EFFECTS,
                    "composite-profile:" + HexFormat.of().formatHex(profile.digest())));
        }
        for (ComponentDescriptor component : profile.components()) {
            for (String path : component.queryPaths()) {
                manifest.proofSubject(new AppCapabilityManifest.ProofSubject(
                        proofSubjectId(component.componentId(), path), component.componentId(),
                        "component/" + component.componentId() + "/v1",
                        "state-proof"));
            }
        }
        for (ProofSubjectProvider provider : proofSubjectProviders()) {
            for (var descriptor : provider.descriptors(null)) {
                manifest.proofSubject(new AppCapabilityManifest.ProofSubject(
                        descriptor.subjectId(), descriptor.subjectVersion(),
                        descriptor.componentId(), "typed-component-state-v1",
                        "typed-state-proof", descriptor.descriptorDigest()));
            }
        }
        return manifest.build();
    }

    @Override
    public List<ProofSubjectProvider> proofSubjectProviders() {
        List<ProofSubjectProvider> providers = new ArrayList<>();
        providers.add(CompositeProofSubjectProviders.profile());
        for (ComponentDescriptor descriptor : profile.components()) {
            ComponentBinding binding = componentsByGeneration.get(descriptor.generation());
            AppCapabilityManifest childManifest = binding.product().capabilityManifest();
            for (ProofSubjectProvider child : binding.product().proofSubjectProviders()) {
                for (var childDescriptor : child.descriptors(childManifest)) {
                    providers.add(CompositeProofSubjectProviders.component(
                            descriptor.componentId(), child, childDescriptor));
                }
            }
        }
        return List.copyOf(providers);
    }

    private static AppCapabilityManifest.CrossCutting capability(
            String id, String configurationDigest) {
        return new AppCapabilityManifest.CrossCutting(id, "1.0.0", true,
                configurationDigest, Map.of(), AppCapabilityManifest.Origin.COMPOSED);
    }

    private static String proofSubjectId(String component, String path) {
        return ("query:" + component + ":" + path)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9:._-]", "-");
    }

    @Override
    public void onEffectResult(
            AppBlockExecutionContext context,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        AppBlock block = context.block();
        runtimeAtBlockStart(block, writer);
        byte[] ownerKey = CompositeStateKeys.effectOwnerKey(result.effectId());
        byte[] encodedOwner = writer.get(ownerKey).orElseThrow(() ->
                new IllegalStateException("missing composite effect owner for "
                        + result.effectId().canonical()));
        ComponentGeneration generation;
        try {
            generation = CompositeStateKeys.decodeGeneration(encodedOwner);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("malformed composite effect owner for "
                    + result.effectId().canonical(), malformed);
        }
        ComponentBinding component = Optional.ofNullable(componentsByGeneration.get(generation))
                .orElseThrow(() -> new IllegalStateException(
                        "composite effect owner references an unavailable generation: " + generation));
        ComponentDescriptor descriptor = component.descriptor();
        component.product().onEffectResult(context.routeToMessageIndexes(List.of()), result,
                NamespacedStateViews.writer(descriptor.componentId(), writer),
                new OwnedEmitter(block.height(), descriptor, writer, effects));
        writer.delete(ownerKey);
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        Objects.requireNonNull(path, "path");
        byte[] safeParams = params != null ? params.clone() : new byte[0];
        RuntimeEntry runtime = runtimeForState(state);
        if ("composite/active-profile-v1".equals(path)) {
            if (safeParams.length != 0) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "active profile query takes no parameters");
            }
            return runtime.profile().canonicalBytes();
        }
        if ("composite/profile-epoch-v1".equals(path)) {
            if (governance == null) {
                throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "profile epochs are unavailable in fixed mode");
            }
            return governance.queryEpoch(safeParams, state);
        }
        if ("composite/governance-v1".equals(path)) {
            if (governance == null || safeParams.length != 0) {
                throw new AppQueryException(governance == null
                        ? AppQueryException.Code.UNSUPPORTED
                        : AppQueryException.Code.INVALID_REQUEST,
                        governance == null ? "profile governance is disabled"
                                : "governance query takes no parameters");
            }
            return governance.queryStatus(state);
        }
        if ("composite/aggregate-v1".equals(path)) {
            return aggregateQuery(runtime, safeParams, state);
        }

        LegacyQueryAlias alias = runtime.profile().queryAliases().stream()
                .filter(candidate -> candidate.aliasPath().equals(path))
                .findFirst().orElse(null);
        if (alias != null) {
            return componentQuery(runtime, alias.componentId(), alias.localPath(), safeParams, state);
        }

        if (!path.startsWith("components/")) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "unsupported composite query path");
        }
        String remainder = path.substring("components/".length());
        int separator = remainder.indexOf('/');
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "invalid composite component query path");
        }
        return componentQuery(runtime, remainder.substring(0, separator),
                remainder.substring(separator + 1), safeParams, state);
    }

    private byte[] aggregateQuery(RuntimeEntry runtime, byte[] params, AppQueryContext state) {
        List<AggregateQueryCodecV1.Subquery> queries;
        try {
            queries = AggregateQueryCodecV1.decodeRequest(
                    params, runtime.profile().aggregateQueryLimits());
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "invalid aggregate query request");
        }
        List<AggregateQueryCodecV1.Result> results = queries.stream()
                .map(query -> new AggregateQueryCodecV1.Result(
                        query.componentId(), query.localPath(),
                        componentQuery(runtime, query.componentId(), query.localPath(),
                                query.params(), state)))
                .toList();
        try {
            return AggregateQueryCodecV1.encodeResponse(
                    results, runtime.profile().aggregateQueryLimits());
        } catch (RuntimeException tooLarge) {
            throw new AppQueryException(AppQueryException.Code.FAILED,
                    "aggregate query response exceeds the committed bound");
        }
    }

    private byte[] componentQuery(
            RuntimeEntry runtime,
            String componentId,
            String localPath,
            byte[] params,
            AppQueryContext state
    ) {
        ComponentDescriptor descriptor = runtime.profile().components().stream()
                .filter(candidate -> candidate.componentId().equals(componentId))
                .filter(candidate -> candidate.activeAt(state.committedHeight()))
                .filter(candidate -> candidate.queryPaths().contains(localPath))
                .findFirst().orElseThrow(() -> new AppQueryException(
                        AppQueryException.Code.UNSUPPORTED,
                        "inactive or unsupported component query path"));
        ComponentBinding component = componentsByGeneration.get(descriptor.generation());
        byte[] response = component.product().query(localPath, params.clone(),
                NamespacedStateViews.query(componentId, state));
        if (response == null) {
            throw new IllegalStateException("component query returned null: " + componentId);
        }
        return response.clone();
    }

    private void verifyRetainedMarker(AppStateReader state) {
        if (state.get(CompositeStateKeys.governanceConfigKey()).isPresent()
                || state.get(CompositeStateKeys.currentProfileEpochKey()).isPresent()
                || state.get(CompositeStateKeys.profileEpochKey(0)).isPresent()
                || state.get(CompositeStateKeys.activeProposalKey()).isPresent()
                || state.get(CompositeStateKeys.retiredGenerationDrainsKey()).isPresent()) {
            throw new IllegalStateException(
                    "retained governed composite state cannot be opened in fixed mode");
        }
        Optional<byte[]> marker = state.get(CompositeStateKeys.profileMarkerKey());
        if (marker.isPresent()) {
            if (!Arrays.equals(marker.get(), profileBytes)) {
                throw new IllegalStateException(
                        "retained composite profile marker does not match effective profile");
            }
            return;
        }
        byte[] root = state.stateRoot();
        if (root == null || root.length != 32) {
            throw new IllegalStateException("retained composite state root is invalid");
        }
        if (!Arrays.equals(root, new byte[32])) {
            throw new IllegalStateException(
                    "retained composite profile marker is absent from non-empty state");
        }
    }

    private RuntimeEntry runtimeAtBlockStart(AppBlock block, AppStateWriter writer) {
        byte[] active;
        if (governance != null) {
            active = governance.ensureProfileForHeight(block.height(), writer);
        } else {
            verifyOrCreateMarker(block, writer);
            active = profileBytes;
        }
        return runtimeFor(active);
    }

    private RuntimeEntry runtimeForState(AppStateReader state) {
        byte[] active = state.get(CompositeStateKeys.profileMarkerKey())
                .orElse(profileBytes);
        return runtimeFor(active);
    }

    private RuntimeEntry runtimeFor(byte[] canonicalProfile) {
        byte[] digest = com.bloxbean.cardano.yano.appchain.composite.contracts
                .CompositeCommitmentV1.profileDigest(canonicalProfile);
        RuntimeEntry runtime = runtimesByDigest.get(HexFormat.of().formatHex(digest));
        if (runtime == null || !Arrays.equals(runtime.profile().canonicalBytes(), canonicalProfile)) {
            throw new IllegalStateException(
                    "active composite profile is absent from executable catalog");
        }
        return runtime;
    }

    private void verifyOrCreateMarker(AppBlock block, AppStateWriter writer) {
        byte[] markerKey = CompositeStateKeys.profileMarkerKey();
        var marker = writer.get(markerKey);
        if (marker.isPresent()) {
            if (!Arrays.equals(marker.get(), profileBytes)) {
                throw new IllegalStateException("composite profile marker does not match effective profile");
            }
            return;
        }
        if (block.height() != 1) {
            throw new IllegalStateException("composite profile marker is absent after genesis height");
        }
        writer.put(markerKey, profileBytes.clone());
    }

    private void clearQuotaCounters(long blockHeight, AppStateWriter writer) {
        for (ComponentBinding component : allComponents) {
            ComponentDescriptor descriptor = component.descriptor();
            writer.delete(CompositeStateKeys.quotaKey(blockHeight, descriptor.generation()));
        }
        for (WorkflowBinding workflow : allWorkflows) {
            WorkflowDescriptor descriptor = workflow.descriptor();
            writer.delete(CompositeStateKeys.workflowQuotaKey(blockHeight, descriptor));
        }
    }

    private static List<Integer> routeIndexes(
            AppBlockExecutionContext context,
            java.util.function.Predicate<String> topicFilter
    ) {
        List<Integer> indexes = new ArrayList<>();
        List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> messages =
                context.messages();
        for (int index = 0; index < messages.size(); index++) {
            if (topicFilter.test(messages.get(index).getTopic())) {
                indexes.add(context.originalMessageIndex(index));
            }
        }
        return List.copyOf(indexes);
    }

    private static com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage snapshot(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message
    ) {
        return com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage.builder()
                .version(message.getVersion())
                .messageId(message.getMessageId().clone())
                .chainId(message.getChainId())
                .topic(message.getTopic())
                .sender(message.getSender().clone())
                .senderSeq(message.getSenderSeq())
                .expiresAt(message.getExpiresAt())
                .body(message.getBody().clone())
                .authScheme(message.getAuthScheme())
                .authProof(message.getAuthProof().clone())
                .build();
    }

    private record ComponentBinding(ComponentDescriptor descriptor, AppStateMachine product) {
    }

    private record WorkflowBinding(WorkflowDescriptor descriptor, CompositeWorkflow product) {
    }

    private record RuntimeEntry(CompositeProfile profile,
                                List<ComponentBinding> components,
                                List<WorkflowBinding> workflows) {
    }

    private static int decodeQuota(byte[] encoded) {
        if (encoded.length != Integer.BYTES) {
            throw new IllegalStateException("malformed transient composite quota counter");
        }
        int count = ByteBuffer.wrap(encoded).getInt();
        if (count < 0) {
            throw new IllegalStateException("negative transient composite quota counter");
        }
        return count;
    }

    private static final class OwnedEmitter implements AppEffectEmitter {
        private final long blockHeight;
        private final ComponentDescriptor owner;
        private final AppStateWriter writer;
        private final AppEffectEmitter delegate;

        private OwnedEmitter(
                long blockHeight,
                ComponentDescriptor owner,
                AppStateWriter writer,
                AppEffectEmitter delegate
        ) {
            this.blockHeight = blockHeight;
            this.owner = owner;
            this.writer = writer;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            byte[] quotaKey = CompositeStateKeys.quotaKey(blockHeight, owner.generation());
            int used = writer.get(quotaKey).map(CompositeStateMachine::decodeQuota).orElse(0);
            if (used >= owner.maxEffectsPerBlock()) {
                throw new IllegalStateException("component effect quota exceeded: "
                        + owner.componentId());
            }
            EffectId effectId = delegate.emit(intent);
            writer.put(quotaKey, ByteBuffer.allocate(Integer.BYTES).putInt(used + 1).array());
            if (intent.result() == ResultPolicy.CHAIN) {
                byte[] ownerKey = CompositeStateKeys.effectOwnerKey(effectId);
                if (writer.get(ownerKey).isPresent()) {
                    throw new IllegalStateException("duplicate composite effect owner: "
                            + effectId.canonical());
                }
                writer.put(ownerKey, CompositeStateKeys.encodeGeneration(owner.generation()));
            }
            return effectId;
        }

        @Override
        public long pendingCount() {
            return delegate.pendingCount();
        }
    }

    private final class WorkflowContext implements CompositeWorkflowContext {
        private final long blockHeight;
        private final WorkflowDescriptor workflow;
        private final AppStateWriter writer;
        private final AppEffectEmitter delegate;

        private WorkflowContext(
                long blockHeight,
                WorkflowDescriptor workflow,
                AppStateWriter writer,
                AppEffectEmitter delegate
        ) {
            this.blockHeight = blockHeight;
            this.workflow = workflow;
            this.writer = writer;
            this.delegate = delegate;
        }

        @Override
        public AppStateWriter state(ComponentGeneration participant) {
            requireParticipant(participant);
            return NamespacedStateViews.writer(participant.componentId(), writer);
        }

        @Override
        public AppEffectEmitter effects(ComponentGeneration owner) {
            requireParticipant(owner);
            return new WorkflowEmitter(blockHeight, workflow, owner, writer, delegate);
        }

        @Override
        public ClaimResult claim(String operationId, byte[] commandHash) {
            byte[] hash = Objects.requireNonNull(commandHash, "commandHash").clone();
            if (hash.length != 32) {
                throw new IllegalArgumentException("workflow commandHash must be 32 bytes");
            }
            byte[] key = CompositeStateKeys.workflowClaimKey(workflow, operationId);
            Optional<byte[]> existing = writer.get(key);
            if (existing.isPresent()) {
                return MessageDigest.isEqual(existing.get(), hash)
                        ? ClaimResult.EXACT_REPLAY : ClaimResult.CONFLICT;
            }
            writer.put(key, hash);
            return ClaimResult.CLAIMED;
        }

        private void requireParticipant(ComponentGeneration participant) {
            if (!workflow.participants().contains(participant)) {
                throw new IllegalArgumentException("workflow attempted undeclared component access: "
                        + participant);
            }
        }
    }

    private static final class WorkflowEmitter implements AppEffectEmitter {
        private final long blockHeight;
        private final WorkflowDescriptor workflow;
        private final ComponentGeneration owner;
        private final AppStateWriter writer;
        private final AppEffectEmitter delegate;

        private WorkflowEmitter(
                long blockHeight,
                WorkflowDescriptor workflow,
                ComponentGeneration owner,
                AppStateWriter writer,
                AppEffectEmitter delegate
        ) {
            this.blockHeight = blockHeight;
            this.workflow = workflow;
            this.owner = owner;
            this.writer = writer;
            this.delegate = delegate;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            byte[] quotaKey = CompositeStateKeys.workflowQuotaKey(blockHeight, workflow);
            int used = writer.get(quotaKey).map(CompositeStateMachine::decodeQuota).orElse(0);
            if (used >= workflow.maxEffectsPerBlock()) {
                throw new IllegalStateException("workflow effect quota exceeded: "
                        + workflow.workflowId());
            }
            EffectId effectId = delegate.emit(intent);
            writer.put(quotaKey, ByteBuffer.allocate(Integer.BYTES).putInt(used + 1).array());
            if (intent.result() == ResultPolicy.CHAIN) {
                byte[] ownerKey = CompositeStateKeys.effectOwnerKey(effectId);
                if (writer.get(ownerKey).isPresent()) {
                    throw new IllegalStateException("duplicate composite effect owner: "
                            + effectId.canonical());
                }
                writer.put(ownerKey, CompositeStateKeys.encodeGeneration(owner));
            }
            return effectId;
        }

        @Override
        public long pendingCount() {
            return delegate.pendingCount();
        }
    }
}
