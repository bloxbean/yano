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
import java.util.LinkedHashMap;
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
    private final List<ComponentBinding> components;
    private final Map<ComponentGeneration, ComponentBinding> componentsByGeneration;
    private final List<WorkflowBinding> workflows;

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
        String configured = context.settings().getOrDefault("effects.max-per-block", "256");
        final int frameworkMaxEffects;
        try {
            frameworkMaxEffects = Integer.parseInt(configured.trim());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("effects.max-per-block must be a decimal integer", invalid);
        }
        return new CompositeStateMachine(
                machineId, profile, components, workflows, frameworkMaxEffects);
    }

    static CompositeStateMachine forTest(
            CompositeProfile profile,
            List<CompositeComponent> components,
            List<CompositeWorkflow> workflows,
            int frameworkMaxEffects
    ) {
        return new CompositeStateMachine(ID, profile, components, workflows, frameworkMaxEffects);
    }

    private CompositeStateMachine(
            String machineId,
            CompositeProfile profile,
            List<CompositeComponent> components,
            List<CompositeWorkflow> workflows,
            int frameworkMaxEffects
    ) {
        this.machineId = CompositeValidation.id(machineId, "machineId");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.profileBytes = profile.canonicalBytes();
        profile.validateEffectBudget(frameworkMaxEffects);
        List<CompositeComponent> products = List.copyOf(
                Objects.requireNonNull(components, "components"));
        List<CompositeWorkflow> workflowProducts = List.copyOf(
                Objects.requireNonNull(workflows, "workflows"));
        List<ComponentDescriptor> actual = products.stream()
                .map(CompositeComponent::descriptor).map(Objects::requireNonNull).toList();
        if (!actual.equals(profile.components())) {
            throw new IllegalArgumentException("component products must exactly match the committed profile order");
        }
        List<WorkflowDescriptor> actualWorkflows = workflowProducts.stream()
                .map(CompositeWorkflow::descriptor).map(Objects::requireNonNull).toList();
        if (!actualWorkflows.equals(profile.workflows())) {
            throw new IllegalArgumentException(
                    "workflow products must exactly match the committed sorted workflow descriptors");
        }
        List<ComponentBinding> bindings = new java.util.ArrayList<>(products.size());
        Map<ComponentGeneration, ComponentBinding> byGeneration = new LinkedHashMap<>();
        for (int index = 0; index < products.size(); index++) {
            ComponentBinding binding = new ComponentBinding(
                    profile.components().get(index), products.get(index));
            bindings.add(binding);
            byGeneration.put(binding.descriptor().generation(), binding);
        }
        this.components = List.copyOf(bindings);
        this.componentsByGeneration = Map.copyOf(byGeneration);
        List<WorkflowBinding> workflowBindings = new java.util.ArrayList<>(workflowProducts.size());
        for (int index = 0; index < workflowProducts.size(); index++) {
            workflowBindings.add(new WorkflowBinding(
                    profile.workflows().get(index), workflowProducts.get(index)));
        }
        this.workflows = List.copyOf(workflowBindings);
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
        verifyRetainedMarker(state);
        for (ComponentBinding component : components) {
            component.product().init(NamespacedStateViews.reader(
                    component.descriptor().componentId(), state), info);
        }
    }

    @Override
    public AdmissionResult validate(
            com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message
    ) {
        String topic = message.getTopic();
        if (topic != null && topic.startsWith("~")) {
            return AdmissionResult.accept();
        }
        List<ComponentBinding> candidates = components.stream()
                .filter(component -> component.descriptor().topics().contains(topic))
                .toList();
        List<WorkflowBinding> workflowCandidates = workflows.stream()
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
        verifyOrCreateMarker(block, writer);
        for (ComponentBinding component : components) {
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
        for (WorkflowBinding workflow : workflows) {
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
    public void onEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        verifyExistingMarker(writer);
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
        if ("composite/aggregate-v1".equals(path)) {
            return aggregateQuery(safeParams, state);
        }

        LegacyQueryAlias alias = profile.queryAliases().stream()
                .filter(candidate -> candidate.aliasPath().equals(path))
                .findFirst().orElse(null);
        if (alias != null) {
            return componentQuery(alias.componentId(), alias.localPath(), safeParams, state);
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
        return componentQuery(remainder.substring(0, separator),
                remainder.substring(separator + 1), safeParams, state);
    }

    private byte[] aggregateQuery(byte[] params, AppQueryContext state) {
        List<AggregateQueryCodecV1.Subquery> queries;
        try {
            queries = AggregateQueryCodecV1.decodeRequest(
                    params, profile.aggregateQueryLimits());
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "invalid aggregate query request");
        }
        List<AggregateQueryCodecV1.Result> results = queries.stream()
                .map(query -> new AggregateQueryCodecV1.Result(
                        query.componentId(), query.localPath(),
                        componentQuery(query.componentId(), query.localPath(),
                                query.params(), state)))
                .toList();
        try {
            return AggregateQueryCodecV1.encodeResponse(
                    results, profile.aggregateQueryLimits());
        } catch (RuntimeException tooLarge) {
            throw new AppQueryException(AppQueryException.Code.FAILED,
                    "aggregate query response exceeds the committed bound");
        }
    }

    private byte[] componentQuery(
            String componentId,
            String localPath,
            byte[] params,
            AppQueryContext state
    ) {
        ComponentDescriptor descriptor = profile.components().stream()
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

    private void verifyExistingMarker(AppStateReader state) {
        byte[] marker = state.get(CompositeStateKeys.profileMarkerKey()).orElseThrow(() ->
                new IllegalStateException("composite profile marker is absent during result callback"));
        if (!Arrays.equals(marker, profileBytes)) {
            throw new IllegalStateException("composite profile marker does not match effective profile");
        }
    }

    private void clearQuotaCounters(long blockHeight, AppStateWriter writer) {
        for (ComponentDescriptor descriptor : profile.components()) {
            writer.delete(CompositeStateKeys.quotaKey(blockHeight, descriptor.generation()));
        }
        for (WorkflowDescriptor descriptor : profile.workflows()) {
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
