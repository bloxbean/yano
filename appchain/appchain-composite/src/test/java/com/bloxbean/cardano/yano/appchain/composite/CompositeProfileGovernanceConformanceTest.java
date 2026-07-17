package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeProfileGovernanceConformanceTest {
    private static final String CHAIN = "governed-replay";
    private static final long SEED = 73;
    private static final byte[] PROPOSAL_ID = new byte[32];
    private static final ComponentDescriptor OLD_DESCRIPTOR = descriptor(
            "1.0.0", 1, "records.v1", 1);
    private static final ComponentDescriptor NEXT_DESCRIPTOR = descriptor(
            "2.0.0", 8, "records.v2");
    private static final CompositeProfile OLD_PROFILE = CompositeProfile.of(
            "records-direct", "1", List.of(OLD_DESCRIPTOR));
    private static final CompositeProfile NEXT_PROFILE = CompositeProfile.of(
            "records-gated", "2", List.of(NEXT_DESCRIPTOR));

    @Test
    void profileActivationIsRootStableAcrossFullReplayRestartAndSnapshot() {
        byte[] sender = new byte[32];
        new Random(SEED ^ 0x5EED).nextBytes(sender);
        String member = HexFormat.of().formatHex(sender);
        AppChainMembershipEpoch membership = new AppChainMembershipEpoch(
                0, List.of(member), 1);
        CompositeProfileGovernanceV1.Begin begin = new CompositeProfileGovernanceV1.Begin(
                PROPOSAL_ID, OLD_PROFILE.digest(), membership.digest(), NEXT_PROFILE.digest(),
                NEXT_PROFILE.canonicalBytes().length, 1, 8, 12);
        byte[] proposalHash = CompositeProfileGovernanceV1.proposalHash(CHAIN, begin);

        Map<String, String> settings = Map.of(
                "effects.enabled", "true",
                "effects.max-per-block", "4",
                "effects.result-window-blocks", "3",
                "membership.mode", "governed",
                "machines.composite.profile-mode", "governed",
                "machines.composite.profile-governance.min-activation-lag", "2",
                "machines.composite.profile-governance.proposal-ttl-blocks", "20",
                "machines.composite.profile-governance.max-epochs", "10");
        StateMachineConformance.Result result = StateMachineConformance.builder(
                        new GovernedProvider(membership))
                .chainId(CHAIN)
                .settings(settings)
                .blocks(10)
                .messagesPerBlock(1)
                .seed(SEED)
                .runs(3)
                .restartAtHeight(7)
                .snapshotAtHeight(8)
                .messageGenerator((height, index, random) -> switch ((int) height) {
                    case 1, 7 -> new StateMachineConformance.CorpusMessage(
                            "records.v1", new byte[]{(byte) height});
                    case 2 -> governance(begin);
                    case 3 -> governance(new CompositeProfileGovernanceV1.Chunk(
                            PROPOSAL_ID, 0, NEXT_PROFILE.canonicalBytes()));
                    case 4 -> governance(new CompositeProfileGovernanceV1.Seal(PROPOSAL_ID));
                    case 5 -> governance(new CompositeProfileGovernanceV1.Approve(proposalHash));
                    case 6 -> governance(new CompositeProfileGovernanceV1.Ready(
                            proposalHash, NEXT_PROFILE.digest()));
                    case 8 -> new StateMachineConformance.CorpusMessage(
                            FxResultBody.TOPIC,
                            new FxResultBody(FxResultBody.BODY_VERSION, 7, 0,
                                    EffectOutcome.CONFIRMED, new byte[]{42}, null).encode());
                    default -> new StateMachineConformance.CorpusMessage(
                            "records.v2", new byte[]{(byte) height});
                })
                .stateProbe("active-profile", CompositeCommitmentV1.profileMarkerKey())
                .stateProbe("current-epoch", CompositeCommitmentV1.currentProfileEpochKey())
                .stateProbe("epoch-zero", CompositeCommitmentV1.profileEpochKey(0))
                .stateProbe("epoch-one", CompositeCommitmentV1.profileEpochKey(1))
                .stateProbe("old-effect-result", CompositeStateKeys.componentKey(
                        "records", new byte[]{2}))
                .run();

        assertThat(result.deterministic()).isTrue();
        assertThat(result.outcomesPerRun()).allSatisfy(outcomes ->
                assertThat(outcomes.get(8L).stateValues().get("old-effect-result"))
                        .isEqualTo("2a"));
    }

    private static StateMachineConformance.CorpusMessage governance(
            CompositeProfileGovernanceV1.Command command) {
        return new StateMachineConformance.CorpusMessage(
                CompositeProfileGovernanceV1.TOPIC, command.encode());
    }

    private static ComponentDescriptor descriptor(String version, long height, String topic) {
        return descriptor(version, height, topic, 0);
    }

    private static ComponentDescriptor descriptor(
            String version, long height, String topic, int maxEffectsPerBlock) {
        return new ComponentDescriptor("records", version, "config", "records-state-v1",
                height, 0, List.of(topic), List.of("get"), maxEffectsPerBlock);
    }

    private static final class GovernedProvider implements AppStateMachineProvider {
        private final AppChainMembershipEpoch membership;

        private GovernedProvider(AppChainMembershipEpoch membership) {
            this.membership = membership;
        }

        @Override public String id() { return "governed-conformance"; }

        @Override
        public AppStateMachine create() {
            throw new IllegalStateException("governed conformance requires chain context");
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            RecordingComponent oldComponent = new EffectComponent(OLD_DESCRIPTOR);
            RecordingComponent nextComponent = new RecordingComponent(NEXT_DESCRIPTOR);
            AppChainConsensusProfile limits = context.consensusProfile().orElseThrow();
            CompositeProfileCatalog catalog = new CompositeProfileCatalog(List.of(
                    new CompositeProfileCatalog.Entry(
                            OLD_PROFILE, List.of(oldComponent), List.of()),
                    new CompositeProfileCatalog.Entry(
                            NEXT_PROFILE, List.of(nextComponent), List.of())),
                    limits.effectsMaxPerBlock(), (int) limits.effectsResultWindowBlocks());
            AppStateMachineContext governedContext = new AppStateMachineContext() {
                @Override public String chainId() { return context.chainId(); }
                @Override public Map<String, String> settings() { return context.settings(); }
                @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                    return context.consensusProfile();
                }
                @Override public Optional<AppChainMembershipView> membershipView() {
                    return Optional.of(ignored -> membership);
                }
            };
            return CompositeStateMachine.create(id(), governedContext, catalog, OLD_PROFILE.digest());
        }
    }

    private static class RecordingComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;

        private RecordingComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public ComponentDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            block.messages().forEach(message -> state.put(new byte[]{1}, message.getBody()));
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            return state.get(new byte[]{1}).orElse(new byte[0]);
        }
    }

    private static final class EffectComponent extends RecordingComponent {
        private EffectComponent(ComponentDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            super.apply(block, state, effects);
            block.messages().forEach(message -> effects.emit(new EffectIntent(
                    "test.effect", new byte[]{1}, "records", FinalityGate.APP_FINAL,
                    ResultPolicy.CHAIN, 3, null)));
        }

        @Override
        public void onEffectResult(AppBlock block, EffectResult result,
                                   AppStateWriter state, AppEffectEmitter effects) {
            state.put(new byte[]{2}, result.externalRef());
        }
    }
}
