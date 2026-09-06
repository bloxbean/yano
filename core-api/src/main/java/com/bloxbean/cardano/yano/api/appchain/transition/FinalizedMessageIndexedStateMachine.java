package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityIds;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Generic same-transaction decorator for the optional finalized-message state index. */
public final class FinalizedMessageIndexedStateMachine implements AppStateMachine {
    public static final String ENABLED_SETTING = "machines.finalized-message-index.enabled";
    public static final String POLICY_SETTING = "machines.finalized-message-index.policy";
    public static final String MAX_MESSAGES_SETTING =
            "machines.finalized-message-index.max-messages-per-block";

    private final AppStateMachine delegate;
    private final FinalizedMessageIndex.Config config;

    public FinalizedMessageIndexedStateMachine(
            AppStateMachine delegate,
            FinalizedMessageIndex.Config config
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Resolve the canonical wrapper configuration shared by identity and execution. */
    public static Optional<FinalizedMessageIndex.Config> configuration(
            Map<String, String> settings, int frameworkMaxMessages
    ) {
        Map<String, String> source = settings == null ? Map.of() : settings;
        String enabled = source.getOrDefault(ENABLED_SETTING, "false");
        if (!"true".equals(enabled) && !"false".equals(enabled)) {
            throw new IllegalArgumentException(ENABLED_SETTING + " must be true or false");
        }
        if (!Boolean.parseBoolean(enabled)) return Optional.empty();
        String policy = source.getOrDefault(POLICY_SETTING, "APPLICATION_ONLY");
        if (!FinalizedMessageIndex.InclusionPolicy.APPLICATION_ONLY.name().equals(policy)) {
            throw new IllegalArgumentException(POLICY_SETTING + " must be APPLICATION_ONLY");
        }
        String configuredLimit = source.getOrDefault(
                MAX_MESSAGES_SETTING, Integer.toString(frameworkMaxMessages));
        int maximum;
        try {
            maximum = Integer.parseInt(configuredLimit);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    MAX_MESSAGES_SETTING + " must be a positive integer", malformed);
        }
        if (maximum > frameworkMaxMessages) {
            throw new IllegalArgumentException(
                    "finalized-message index block limit exceeds committed framework limit");
        }
        return Optional.of(FinalizedMessageIndex.Config.applicationOnly(maximum));
    }

    @Override public String id() { return delegate.id(); }
    @Override public void init(AppStateReader state, AppChainInfo info) {
        delegate.init(state, info);
        if (state.committedHeight() > 0) verifyConfig(state);
    }
    @Override public AdmissionResult validate(AppMessage message) { return delegate.validate(message); }
    @Override public AdmissionResult validateForBlock(
            AppMessage message, long candidateHeight, AppStateReader committedState) {
        return delegate.validateForBlock(message, candidateHeight, committedState);
    }
    @Override public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        return delegate.validatePrivilegedSystemSubmission(topic, body);
    }
    @Override public Map<String, Object> operationalStatus() { return delegate.operationalStatus(); }
    @Override public java.util.List<com.bloxbean.cardano.yano.api.appchain.snapshot
            .AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
        return delegate.authenticatedSnapshotSeries();
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        AppCapabilityManifest manifest = delegate.capabilityManifest();
        return manifest.withCrossCutting(new AppCapabilityManifest.CrossCutting(
                AppCapabilityIds.FINALIZED_MESSAGE, "1.0.0", true,
                HexFormat.of().formatHex(config.digest()),
                Map.of("policy", config.policy().name(),
                        "maxMessagesPerBlock", Integer.toString(config.maxMessagesPerBlock()),
                        "maximumWritesPerBlock", Integer.toString(config.maximumWritesPerBlock()),
                        "keyNamespace", FinalizedMessageIndex.LOGICAL_NAMESPACE,
                        "keyDerivation", "sha256(namespace || logical-key)"),
                AppCapabilityManifest.Origin.LAUNCHER_ENABLED))
                .withProofSubject(new AppCapabilityManifest.ProofSubject(
                        FinalizedMessageProofSubjectProvider.SUBJECT_ID, 1, "",
                        FinalizedMessageIndex.LOGICAL_NAMESPACE, "state-proof",
                        FinalizedMessageProofSubjectProvider.DESCRIPTOR.descriptorDigest()));
    }

    @Override
    public java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider>
    proofSubjectProviders() {
        java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider> result =
                new java.util.ArrayList<>(delegate.proofSubjectProviders());
        result.add(new FinalizedMessageProofSubjectProvider());
        return java.util.List.copyOf(result);
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        delegate.apply(context, writer, effects);
        index(context, writer, effects);
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects, AppObservationEmitter observations) {
        delegate.apply(context, writer, effects, observations);
        index(context, writer, effects);
    }

    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                               AppStateWriter writer, AppEffectEmitter effects,
                               AppObservationEmitter observations) {
        delegate.onEffectResult(context, result, writer, effects, observations);
    }

    @Override
    public void onObservationResult(AppBlockExecutionContext context, ObservationResult result,
                                    AppStateWriter writer, AppEffectEmitter effects,
                                    AppObservationEmitter observations) {
        delegate.onObservationResult(context, result, writer, effects, observations);
    }

    private void index(AppBlockExecutionContext context, AppStateWriter writer,
                       AppEffectEmitter effects) {
        if (context.block().height() == 1) {
            if (writer.get(FinalizedMessageIndex.CONFIG_KEY).isPresent()) {
                throw new IllegalStateException("finalized-message index config already exists");
            }
            writer.put(FinalizedMessageIndex.CONFIG_KEY, config.canonicalBytes());
        } else {
            verifyConfig(writer);
        }
        TransitionPlans.commit(FinalizedMessageIndex.plan(context, config), writer, effects);
    }

    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                               AppStateWriter writer, AppEffectEmitter effects) {
        delegate.onEffectResult(context, result, writer, effects);
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        return delegate.query(path, params, state);
    }

    private void verifyConfig(AppStateReader state) {
        byte[] retained = state.get(FinalizedMessageIndex.CONFIG_KEY).orElseThrow(() ->
                new IllegalStateException("finalized-message index config is absent"));
        if (!MessageDigest.isEqual(retained, config.canonicalBytes())) {
            throw new IllegalStateException("finalized-message index config is incompatible");
        }
    }
}
