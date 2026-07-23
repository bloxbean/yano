package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryCodecV1;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;

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
            List<CompositeComponent> components,
            List<CompositeWorkflow> workflows
    ) {
        return create(ID, context, profile, components, workflows);
    }

    /**
     * Constructs a reusable composite core for a custom provider whose
     * selector ID is distinct from the stock {@code composite} provider.
     */
    public static CompositeStateMachine create(
            String machineId,
            AppStateMachineContext context,
            CompositeProfile profile,
            List<CompositeComponent> components,
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
                List.of(new CompositeProfileCatalog.Entry(profile, components, workflows)),
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
            List<CompositeComponent> components,
            List<CompositeWorkflow> workflows,
            int frameworkMaxEffects
    ) {
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(
                List.of(new CompositeProfileCatalog.Entry(profile, components, workflows)),
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
            List<ComponentBinding> bindings = new ArrayList<>(entry.components().size());
            for (int index = 0; index < entry.components().size(); index++) {
                ComponentBinding binding = new ComponentBinding(
                        entry.profile().components().get(index), entry.components().get(index));
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
        return validateAgainstRuntime(message, runtimeFor(profileBytes), 1);
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
        return validateAgainstRuntime(message, runtime, candidateHeight);
    }

    private AdmissionResult validateAgainstRuntime(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message,
            RuntimeEntry runtime,
            long candidateHeight
    ) {
        String topic = message.getTopic();
        if (topic != null && topic.startsWith("~")) {
            return AdmissionResult.accept();
        }
        List<ComponentBinding> candidates = runtime.components().stream()
                .filter(component -> component.descriptor().activeAt(candidateHeight))
                .filter(component -> component.descriptor().topics().contains(topic))
                .toList();
        List<WorkflowBinding> workflowCandidates = runtime.workflows().stream()
                .filter(workflow -> workflow.descriptor().activeAt(candidateHeight))
                .filter(workflow -> workflow.descriptor().topic().equals(topic))
                .toList();
        if (candidates.isEmpty() && workflowCandidates.isEmpty()) {
            return AdmissionResult.reject("Unknown composite message topic");
        }
        for (ComponentBinding candidate : candidates) {
            AdmissionResult result = candidate.product().validate(snapshot(message));
            if (!result.isAccepted()) {
                return result;
            }
        }
        for (WorkflowBinding workflow : workflowCandidates) {
            AdmissionResult result = workflow.product().validate(snapshot(message));
            if (!result.isAccepted()) {
                return result;
            }
        }
        return AdmissionResult.accept();
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        apply(block, writer, AppEffectEmitter.rejecting("effects unavailable to composite"));
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
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
            List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> routed =
                    block.messages().stream()
                            .filter(message -> message.getTopic() != null
                                    && !message.getTopic().startsWith("~"))
                            .filter(message -> descriptor.topics().contains(message.getTopic()))
                            .toList();
            component.product().apply(withMessages(block, routed),
                    NamespacedStateViews.writer(descriptor.componentId(), writer),
                    new OwnedEmitter(block.height(), descriptor, writer, effects));
        }
        for (WorkflowBinding workflow : runtime.workflows()) {
            WorkflowDescriptor descriptor = workflow.descriptor();
            if (!descriptor.activeAt(block.height())) {
                continue;
            }
            List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> routed =
                    block.messages().stream()
                            .filter(message -> descriptor.topic().equals(message.getTopic()))
                            .toList();
            workflow.product().apply(withMessages(block, routed),
                    new WorkflowContext(block.height(), descriptor, writer, effects));
        }
        clearQuotaCounters(block.height(), writer);
    }

    @Override
    public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        if (governance == null
                || !com.bloxbean.cardano.yano.appchain.composite.contracts
                .CompositeProfileGovernanceV1.TOPIC.equals(topic)) {
            return AdmissionResult.reject("Composite profile governance is disabled");
        }
        return governance.permitsLocalSubmission(body)
                ? AdmissionResult.accept()
                : AdmissionResult.reject(
                "Invalid command or target profile is absent from the local executable catalog");
    }

    @Override
    public Map<String, Object> operationalStatus() {
        if (governance != null) return governance.operationalStatus();
        return Map.of("mode", "fixed", "activeProfileDigest",
                HexFormat.of().formatHex(profile.digest()));
    }

    @Override
    public void onEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
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
        component.product().onEffectResult(withMessages(block, List.of()), result,
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

    private static AppBlock withMessages(
            AppBlock block,
            List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> messages
    ) {
        List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> safeMessages =
                messages.stream().map(CompositeStateMachine::snapshot).toList();
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash().clone(),
                block.l1Slot(), block.l1BlockHash().clone(), block.timestamp(),
                AppBlockCodec.messagesRoot(safeMessages),
                block.stateRoot().clone(), safeMessages, block.proposer().clone(),
                FinalityCert.empty());
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

    private record ComponentBinding(ComponentDescriptor descriptor, CompositeComponent product) {
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
