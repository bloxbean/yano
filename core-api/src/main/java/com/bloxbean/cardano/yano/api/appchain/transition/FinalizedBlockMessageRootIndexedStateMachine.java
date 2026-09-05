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

/** Same-transaction framework decorator for ADR-037's default block-root bridge. */
public final class FinalizedBlockMessageRootIndexedStateMachine implements AppStateMachine {
    public static final String ENABLED_SETTING =
            "machines.finalized-block-message-root.enabled";
    public static final String MAX_MESSAGES_SETTING =
            "machines.finalized-block-message-root.max-messages-per-block";
    public static final String RETENTION_SETTING =
            "machines.finalized-block-message-root.retention-profile";

    private final AppStateMachine delegate;
    private final FinalizedBlockMessageRootIndex.Config config;

    public FinalizedBlockMessageRootIndexedStateMachine(
            AppStateMachine delegate,
            FinalizedBlockMessageRootIndex.Config config
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
    }

    public static FinalizedBlockMessageRootIndex.Config configuration(
            Map<String, String> settings, int frameworkMaximum
    ) {
        Map<String, String> source = settings == null ? Map.of() : settings;
        String enabledText = source.getOrDefault(ENABLED_SETTING, "true");
        if (!"true".equals(enabledText) && !"false".equals(enabledText)) {
            throw new IllegalArgumentException(ENABLED_SETTING + " must be true or false");
        }
        int maximum;
        try {
            maximum = Integer.parseInt(source.getOrDefault(
                    MAX_MESSAGES_SETTING, Integer.toString(frameworkMaximum)));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(MAX_MESSAGES_SETTING
                    + " must be a positive integer", malformed);
        }
        if (maximum > frameworkMaximum) {
            throw new IllegalArgumentException(
                    "finalized block-message limit exceeds committed framework limit");
        }
        return new FinalizedBlockMessageRootIndex.Config(Boolean.parseBoolean(enabledText),
                maximum, source.getOrDefault(RETENTION_SETTING,
                FinalizedBlockMessageRootIndex.PRIMARY_RETENTION));
    }

    @Override public String id() { return delegate.id(); }
    @Override public void init(AppStateReader state, AppChainInfo info) {
        delegate.init(state, info);
        if (config.enabled() && state.committedHeight() > 0) verifyConfig(state);
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
    @Override public java.util.List<com.bloxbean.cardano.yano.api.appchain.snapshot
            .AuthenticatedSnapshotSourceCommitmentV1> authenticatedSnapshotSourceCommitments() {
        return delegate.authenticatedSnapshotSourceCommitments();
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        AppCapabilityManifest manifest = delegate.capabilityManifest();
        manifest = manifest.withCrossCutting(new AppCapabilityManifest.CrossCutting(
                AppCapabilityIds.FINALIZED_BLOCK_MESSAGES, "1.0.0", config.enabled(),
                HexFormat.of().formatHex(config.digest()),
                Map.of("schemaVersion", Integer.toString(FinalizedBlockMessageRootIndex.SCHEMA_VERSION),
                        "maxMessagesPerBlock", Integer.toString(config.maxMessagesPerBlock()),
                        "maximumWritesPerBlock", config.enabled() ? "1" : "0",
                        "retentionProfile", config.retentionProfile(),
                        "keyNamespace", FinalizedBlockMessageRootIndex.LOGICAL_NAMESPACE,
                        "keyDerivation", "sha256(namespace || block/ || uint64-be height)"),
                AppCapabilityManifest.Origin.LAUNCHER_ENABLED));
        return config.enabled() ? manifest.withProofSubject(
                new AppCapabilityManifest.ProofSubject(
                        FinalizedBlockMessageRootIndex.SUBJECT_ID, 1, "",
                        FinalizedBlockMessageRootIndex.LOGICAL_NAMESPACE, "state-proof",
                        FinalizedBlockMessagesProofSubjectProvider.DESCRIPTOR.descriptorDigest())) : manifest;
    }

    @Override
    public java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider>
    proofSubjectProviders() {
        java.util.List<com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider> result =
                new java.util.ArrayList<>(delegate.proofSubjectProviders());
        if (config.enabled()) result.add(new FinalizedBlockMessagesProofSubjectProvider());
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
        if (!config.enabled()) return;
        if (context.block().height() == 1) {
            if (writer.get(FinalizedBlockMessageRootIndex.CONFIG_KEY).isPresent()) {
                throw new IllegalStateException("finalized block-message config already exists");
            }
            writer.put(FinalizedBlockMessageRootIndex.CONFIG_KEY, config.canonicalBytes());
        } else {
            verifyConfig(writer);
        }
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(context.block().height());
        if (writer.get(key).isPresent()) {
            throw new IllegalStateException("finalized block-message height already exists");
        }
        TransitionPlans.commit(FinalizedBlockMessageRootIndex.plan(context, config), writer, effects);
    }

    @Override public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                                          AppStateWriter writer, AppEffectEmitter effects) {
        delegate.onEffectResult(context, result, writer, effects);
    }
    @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
        return delegate.query(path, params, state);
    }

    private void verifyConfig(AppStateReader state) {
        byte[] retained = state.get(FinalizedBlockMessageRootIndex.CONFIG_KEY).orElseThrow(() ->
                new IllegalStateException("finalized block-message config is absent"));
        if (!MessageDigest.isEqual(retained, config.canonicalBytes())) {
            throw new IllegalStateException("finalized block-message config is incompatible");
        }
    }
}
