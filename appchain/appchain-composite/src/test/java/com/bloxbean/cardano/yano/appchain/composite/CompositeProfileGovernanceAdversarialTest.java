package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeGovernanceStatusV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeProfileGovernanceAdversarialTest {
    private static final String A = hex(1);
    private static final String B = hex(2);
    private static final String C = hex(3);
    private static final String D = hex(4);
    private static final String OUTSIDER = hex(9);
    private static final byte[] VALUE_KEY = new byte[]{1};

    @Test
    void catalogCanonicalizesProfileAndLifecycleOrderByDigest() {
        ComponentDescriptor firstDescriptor = descriptor(
                "first", "1", 1, "first.v1", 1);
        ComponentDescriptor secondDescriptor = descriptor(
                "second", "1", 1, "second.v1", 1);
        CompositeProfile firstProfile = CompositeProfile.of(
                "first-profile", "1", List.of(firstDescriptor));
        CompositeProfile secondProfile = CompositeProfile.of(
                "second-profile", "1", List.of(secondDescriptor));
        CompositeProfileCatalog.Entry first = entry(
                firstProfile, new RecordingComponent(firstDescriptor));
        CompositeProfileCatalog.Entry second = entry(
                secondProfile, new RecordingComponent(secondDescriptor));

        CompositeProfileCatalog forward = new CompositeProfileCatalog(
                List.of(first, second), 1, 1);
        CompositeProfileCatalog reverse = new CompositeProfileCatalog(
                List.of(second, first), 1, 1);

        assertThat(forward.entries()).containsExactlyElementsOf(reverse.entries());
        assertThat(forward.uniqueComponents())
                .containsExactlyElementsOf(reverse.uniqueComponents());
        assertThat(forward.entries().stream()
                .map(entry -> HexFormat.of().formatHex(entry.digest())).toList())
                .isSorted();
    }

    @Test
    void catalogRequiresOneExactProductInstanceForEachGeneration() {
        ComponentDescriptor descriptor = new ComponentDescriptor(
                "records", "1", "cfg", "records-state-v1", 1, 0,
                List.of("records.v1"), List.of(), 0);
        CompositeProfile first = CompositeProfile.of(
                "records-a", "1", List.of(descriptor));
        CompositeProfile second = CompositeProfile.of(
                "records-b", "1", List.of(descriptor));

        assertThatThrownBy(() -> new CompositeProfileCatalog(List.of(
                entry(first, new RecordingComponent(descriptor)),
                entry(second, new RecordingComponent(descriptor))), 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one component generation inconsistently");
    }

    @Test
    void fixedModeCannotReopenRetainedGovernedState() {
        Fixture governed = fixture(4, 1, 1, ignored -> membership());
        apply(governed.machine, governed.state, 1);
        CompositeStateMachine fixed = CompositeStateMachine.forTest(
                governed.oldProfile, List.of(governed.oldComponent), List.of(), 4);

        assertThatThrownBy(() -> fixed.init(
                governed.state, new AppChainInfo("chain", A, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be opened in fixed mode");
    }

    @Test
    void retainedEpochHistoryRequiresEveryHistoricalCatalogEntry() {
        ComponentDescriptor firstDescriptor = new ComponentDescriptor(
                "first", "1", "cfg", "first-state-v1", 1, 0,
                List.of("first.v1"), List.of(), 0);
        ComponentDescriptor middleDescriptor = new ComponentDescriptor(
                "middle", "1", "cfg", "middle-state-v1", 1, 0,
                List.of("middle.v1"), List.of(), 0);
        ComponentDescriptor lastDescriptor = new ComponentDescriptor(
                "last", "1", "cfg", "last-state-v1", 1, 0,
                List.of("last.v1"), List.of(), 0);
        CompositeProfile first = CompositeProfile.of(
                "first-profile", "1", List.of(firstDescriptor));
        CompositeProfile middle = CompositeProfile.of(
                "middle-profile", "1", List.of(middleDescriptor));
        CompositeProfile last = CompositeProfile.of(
                "last-profile", "1", List.of(lastDescriptor));
        CompositeProfileCatalog incomplete = new CompositeProfileCatalog(List.of(
                entry(first, new RecordingComponent(firstDescriptor)),
                entry(last, new RecordingComponent(lastDescriptor))), 0, 0);
        AppChainConsensusProfile disabled = AppChainTestProfiles.fromSettings(Map.of());
        CompositeStateMachine machine = CompositeStateMachine.create(
                context(disabled, ignored -> membership()), incomplete, first.digest());
        MemoryState state = new MemoryState();
        apply(machine, state, 1);
        state.put(CompositeStateKeys.profileEpochKey(1), new CompositeProfileEpochV1(
                1, 1, 2, first.digest(), middle.canonicalBytes(), filled(71)).encode());
        state.put(CompositeStateKeys.profileEpochKey(2), new CompositeProfileEpochV1(
                1, 2, 3, middle.digest(), last.canonicalBytes(), filled(72)).encode());
        state.put(CompositeStateKeys.currentProfileEpochKey(),
                java.nio.ByteBuffer.allocate(Long.BYTES).putLong(2).array());
        state.put(CompositeStateKeys.profileMarkerKey(), last.canonicalBytes());

        assertThatThrownBy(() -> machine.init(state, new AppChainInfo("chain", A, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from executable catalog");
    }

    @Test
    void fixedAndGovernedCompositesSupportCanonicalEffectsDisabledProfiles() {
        ComponentDescriptor descriptor = new ComponentDescriptor(
                "records", "1", "cfg", "records-state-v1", 1, 0,
                List.of("records.v1"), List.of(), 0);
        RecordingComponent product = new RecordingComponent(descriptor);
        CompositeProfile profile = CompositeProfile.of(
                "records", "1", List.of(descriptor));
        AppChainConsensusProfile disabled = AppChainTestProfiles.fromSettings(Map.of());
        AppStateMachineContext fixedContext = new AppStateMachineContext() {
            @Override public String chainId() { return "chain"; }
            @Override public Map<String, String> settings() { return Map.of(); }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(disabled);
            }
        };
        CompositeStateMachine fixed = CompositeStateMachine.create(
                fixedContext, profile, List.of(product), List.of());
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(List.of(
                entry(profile, product)), 0, 0);
        CompositeStateMachine governed = CompositeStateMachine.create(
                context(disabled, ignored -> membership()), catalog, profile.digest());

        fixed.apply(block(1, List.of()), new MemoryState());
        governed.apply(block(1, List.of()), new MemoryState());
    }

    @Test
    void catalogCannotRemoveThenReintroduceAnIncompatiblePhysicalNamespace() {
        ComponentDescriptor original = new ComponentDescriptor(
                "records", "1", "cfg-1", "records-state-v1", 1, 0,
                List.of("records.v1"), List.of(), 0);
        ComponentDescriptor unrelated = new ComponentDescriptor(
                "audit", "1", "cfg-1", "audit-state-v1", 1, 0,
                List.of("audit.v1"), List.of(), 0);
        ComponentDescriptor incompatibleReintroduction = new ComponentDescriptor(
                "records", "2", "cfg-2", "records-state-v2", 1, 0,
                List.of("records.v2"), List.of(), 0);

        assertThatThrownBy(() -> new CompositeProfileCatalog(List.of(
                entry(CompositeProfile.of("original", "1", List.of(original)),
                        new RecordingComponent(original)),
                entry(CompositeProfile.of("removed", "2", List.of(unrelated)),
                        new RecordingComponent(unrelated)),
                entry(CompositeProfile.of("reintroduced", "3",
                                List.of(incompatibleReintroduction)),
                        new RecordingComponent(incompatibleReintroduction))), 4, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible state/result contracts");
    }

    @Test
    void thresholdWrongReadinessAndForeignMemberCannotSchedule() {
        Fixture fixture = fixture(4, 1, 1, ignored -> membership());
        apply(fixture.machine, fixture.state, 1);
        Proposal proposal = stageAndSeal(fixture, 2, 3, 8, 12, 11);

        apply(fixture.machine, fixture.state, 4,
                command(OUTSIDER, new CompositeProfileGovernanceV1.Approve(proposal.hash)),
                command(A, new CompositeProfileGovernanceV1.Approve(filled(44))),
                command(A, new CompositeProfileGovernanceV1.Approve(proposal.hash)),
                command(A, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())),
                command(B, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())),
                command(C, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.oldProfile.digest())));

        CompositeGovernanceStatusV1 waiting = status(fixture);
        assertThat(waiting.proposal().statusCode()).isEqualTo(1);
        assertThat(waiting.proposal().approvals()).containsExactly(A);
        assertThat(waiting.proposal().readiness()).containsExactly(A, B);

        apply(fixture.machine, fixture.state, 5,
                command(C, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())));
        assertThat(status(fixture).proposal().statusCode()).isEqualTo(1);

        apply(fixture.machine, fixture.state, 6,
                command(B, new CompositeProfileGovernanceV1.Approve(proposal.hash)));
        assertThat(status(fixture).proposal().statusCode()).isEqualTo(2);
    }

    @Test
    void scheduledMembershipChangeVoidsProposalBeforeActivation() {
        AtomicBoolean membershipChanged = new AtomicBoolean();
        AppChainMembershipView view = height -> membershipChanged.get() && height >= 8
                ? new AppChainMembershipEpoch(8, List.of(A, B, D), 2)
                : membership();
        Fixture fixture = fixture(4, 1, 1, view);
        apply(fixture.machine, fixture.state, 1);
        Proposal proposal = stageAndSeal(fixture, 2, 3, 8, 12, 12);
        schedule(fixture, proposal, 4, 5);
        assertThat(status(fixture).proposal().statusCode()).isEqualTo(2);

        membershipChanged.set(true);
        apply(fixture.machine, fixture.state, 6);

        assertThat(status(fixture).proposal()).isNull();
        assertThat(fixture.machine.query(
                "composite/active-profile-v1", new byte[0], fixture.state))
                .containsExactly(fixture.oldProfile.canonicalBytes());
    }

    @Test
    void conflictingChunksCancellationExpiryAndMalformedFloodStayBoundedNoOps() {
        Fixture fixture = fixture(4, 1, 1, ignored -> membership());
        apply(fixture.machine, fixture.state, 1);
        int genesisEntries = fixture.state.size();

        AppMessage[] malformed = new AppMessage[500];
        for (int index = 0; index < malformed.length; index++) {
            malformed[index] = rawCommand(A, new byte[]{(byte) 0x9f, (byte) index});
        }
        apply(fixture.machine, fixture.state, 2, malformed);
        assertThat(status(fixture).proposal()).isNull();
        assertThat(fixture.state.size()).isEqualTo(genesisEntries);

        byte[] id = filled(20);
        CompositeProfileGovernanceV1.Begin begin = begin(
                fixture, id, 9, 13);
        apply(fixture.machine, fixture.state, 3,
                command(A, begin),
                command(A, new CompositeProfileGovernanceV1.Chunk(
                        id, 0, fixture.nextProfile.canonicalBytes())),
                command(A, new CompositeProfileGovernanceV1.Chunk(id, 0, new byte[]{1})));
        assertThat(status(fixture).proposal()).isNull();

        Proposal cancellable = stageAndSeal(fixture, 4, 5, 10, 14, 21);
        apply(fixture.machine, fixture.state, 6,
                command(A, new CompositeProfileGovernanceV1.Cancel(cancellable.hash)),
                command(B, new CompositeProfileGovernanceV1.Cancel(cancellable.hash)));
        assertThat(status(fixture).proposal()).isNull();

        stageAndSeal(fixture, 7, 8, 11, 11, 22);
        apply(fixture.machine, fixture.state, 12);
        assertThat(status(fixture).proposal()).isNull();
        assertThat(fixture.state.size()).isLessThanOrEqualTo(genesisEntries + 1);
    }

    @Test
    void restartVerifiesWholeEpochChainAndRequiresScheduledTargetCatalog() {
        Fixture fixture = fixture(4, 1, 1, ignored -> membership());
        activate(fixture, 8, 31);

        fixture.machine.init(fixture.state, new AppChainInfo("chain", A, 3));

        CompositeProfileEpochV1 corruptGenesis = new CompositeProfileEpochV1(
                1, 0, 1, new byte[32], fixture.nextProfile.canonicalBytes(), new byte[32]);
        fixture.state.put(CompositeStateKeys.profileEpochKey(0), corruptGenesis.encode());
        assertThatThrownBy(() -> fixture.machine.init(
                fixture.state, new AppChainInfo("chain", A, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("genesis profile mismatch");

        Fixture scheduled = fixture(4, 1, 1, ignored -> membership());
        apply(scheduled.machine, scheduled.state, 1);
        Proposal proposal = stageAndSeal(scheduled, 2, 3, 8, 12, 32);
        schedule(scheduled, proposal, 4, 5);
        CompositeProfileCatalog incomplete = new CompositeProfileCatalog(List.of(
                new CompositeProfileCatalog.Entry(scheduled.oldProfile,
                        List.of(scheduled.oldComponent), List.of())), 4, 3);
        CompositeStateMachine missingTarget = CompositeStateMachine.create(
                context(limits(4), ignored -> membership()), incomplete,
                scheduled.oldProfile.digest());
        assertThatThrownBy(() -> missingTarget.init(
                scheduled.state, new AppChainInfo("chain", A, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from executable catalog");
    }

    @Test
    void transitionQuotaIncludesRetiredDrainAndReleasesItAfterWindow() {
        Fixture rejected = fixture(4, 3, 2, ignored -> membership());
        apply(rejected.machine, rejected.state, 1);
        stageAndSeal(rejected, 2, 3, 8, 12, 41);
        assertThat(status(rejected).proposal()).isNull();

        Fixture accepted = fixture(4, 2, 2, ignored -> membership());
        activate(accepted, 8, 42);
        assertThat(status(accepted).drains()).singleElement()
                .extracting(CompositeGovernanceStatusV1.Drain::throughHeight)
                .isEqualTo(11L);
        apply(accepted.machine, accepted.state, 12);
        assertThat(status(accepted).drains()).isEmpty();
    }

    @Test
    void activationFromResultPathKeepsExactOldGenerationCallback() {
        EffectComponent old = new EffectComponent(descriptor(
                "records", "1.0.0", 1, "records.v1", 2));
        RecordingComponent next = new RecordingComponent(descriptor(
                "records", "2.0.0", 8, "records.v2", 1));
        Fixture fixture = fixture(4, old, next, ignored -> membership());
        apply(fixture.machine, fixture.state, 1);
        Proposal proposal = stageAndSeal(fixture, 2, 3, 8, 12, 51);
        schedule(fixture, proposal, 4, 5);

        CapturingEmitter emitted = new CapturingEmitter(7);
        apply(fixture.machine, fixture.state, emitted, 7,
                business(A, "records.v1", new byte[]{7}),
                business(B, "records.v1", new byte[]{6}));
        EffectId oldEffect = emitted.ids.getFirst();
        EffectId expiredOldEffect = emitted.ids.get(1);

        fixture.state.height = 8;
        fixture.machine.onEffectResult(block(8, List.of()), new EffectResult(
                oldEffect, "test.effect", "records", EffectOutcome.CONFIRMED,
                "ok".getBytes(StandardCharsets.US_ASCII), null, 8),
                fixture.state, new CapturingEmitter(8));
        fixture.machine.onEffectResult(block(8, List.of()), new EffectResult(
                expiredOldEffect, "test.effect", "records", EffectOutcome.EXPIRED,
                new byte[0], null, 8), fixture.state, new CapturingEmitter(8));
        apply(fixture.machine, fixture.state, 8,
                business(A, "records.v2", new byte[]{8}));

        assertThat(old.resultCalls).isEqualTo(2);
        assertThat(next.applied).isEqualTo(1);
        assertThat(status(fixture).currentEpoch()).isEqualTo(1);
    }

    private static void activate(Fixture fixture, long activationHeight, int id) {
        apply(fixture.machine, fixture.state, 1);
        Proposal proposal = stageAndSeal(
                fixture, 2, 3, activationHeight, activationHeight + 4, id);
        schedule(fixture, proposal, 4, 5);
        apply(fixture.machine, fixture.state, activationHeight);
    }

    private static Proposal stageAndSeal(Fixture fixture,
                                         long beginHeight,
                                         long sealHeight,
                                         long activationHeight,
                                         long expiryHeight,
                                         int id) {
        byte[] proposalId = filled(id);
        CompositeProfileGovernanceV1.Begin begin = begin(
                fixture, proposalId, activationHeight, expiryHeight);
        apply(fixture.machine, fixture.state, beginHeight,
                command(A, begin),
                command(A, new CompositeProfileGovernanceV1.Chunk(
                        proposalId, 0, fixture.nextProfile.canonicalBytes())));
        apply(fixture.machine, fixture.state, sealHeight,
                command(A, new CompositeProfileGovernanceV1.Seal(proposalId)));
        return new Proposal(CompositeProfileGovernanceV1.proposalHash("chain", begin));
    }

    private static CompositeProfileGovernanceV1.Begin begin(
            Fixture fixture, byte[] id, long activation, long expiry) {
        return new CompositeProfileGovernanceV1.Begin(id, fixture.oldProfile.digest(),
                membership().digest(), fixture.nextProfile.digest(),
                fixture.nextProfile.canonicalBytes().length, 1, activation, expiry);
    }

    private static void schedule(Fixture fixture, Proposal proposal,
                                 long approvalHeight, long readyHeight) {
        apply(fixture.machine, fixture.state, approvalHeight,
                command(A, new CompositeProfileGovernanceV1.Approve(proposal.hash)),
                command(B, new CompositeProfileGovernanceV1.Approve(proposal.hash)),
                command(A, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())),
                command(B, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())));
        apply(fixture.machine, fixture.state, readyHeight,
                command(C, new CompositeProfileGovernanceV1.Ready(
                        proposal.hash, fixture.nextProfile.digest())));
    }

    private static Fixture fixture(int cap, int oldQuota, int nextQuota,
                                   AppChainMembershipView membershipView) {
        return fixture(cap,
                new RecordingComponent(descriptor(
                        "records", "1.0.0", 1, "records.v1", oldQuota)),
                new RecordingComponent(descriptor(
                        "records", "2.0.0", 8, "records.v2", nextQuota)),
                membershipView);
    }

    private static CompositeProfileCatalog.Entry entry(CompositeProfile profile,
                                                       CompositeComponent component) {
        return new CompositeProfileCatalog.Entry(profile, List.of(component), List.of());
    }

    private static Fixture fixture(int cap,
                                   RecordingComponent oldComponent,
                                   RecordingComponent nextComponent,
                                   AppChainMembershipView membershipView) {
        CompositeProfile oldProfile = CompositeProfile.of(
                "records-direct", "1", List.of(oldComponent.descriptor()));
        CompositeProfile nextProfile = CompositeProfile.of(
                "records-gated", "2", List.of(nextComponent.descriptor()));
        AppChainConsensusProfile limits = limits(cap);
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(List.of(
                new CompositeProfileCatalog.Entry(
                        oldProfile, List.of(oldComponent), List.of()),
                new CompositeProfileCatalog.Entry(
                        nextProfile, List.of(nextComponent), List.of())), cap, 3);
        CompositeStateMachine machine = CompositeStateMachine.create(
                context(limits, membershipView), catalog, oldProfile.digest());
        return new Fixture(machine, new MemoryState(), oldProfile, nextProfile,
                oldComponent, nextComponent);
    }

    private static AppChainConsensusProfile limits(int cap) {
        return AppChainTestProfiles.fromSettings(Map.of(
                "effects.enabled", "true", "effects.max-per-block", Integer.toString(cap),
                "effects.result-window-blocks", "3"));
    }

    private static AppStateMachineContext context(
            AppChainConsensusProfile limits, AppChainMembershipView membershipView) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return "chain"; }
            @Override public Map<String, String> settings() {
                return Map.of(
                        "membership.mode", "governed",
                        "machines.composite.profile-mode", "governed",
                        "machines.composite.profile-governance.min-activation-lag", "2",
                        "machines.composite.profile-governance.proposal-ttl-blocks", "20",
                        "machines.composite.profile-governance.max-epochs", "10");
            }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(limits);
            }
            @Override public Optional<AppChainMembershipView> membershipView() {
                return Optional.of(membershipView);
            }
        };
    }

    private static AppChainMembershipEpoch membership() {
        return new AppChainMembershipEpoch(0, List.of(A, B, C), 2);
    }

    private static CompositeGovernanceStatusV1 status(Fixture fixture) {
        return CompositeGovernanceStatusV1.decode(fixture.machine.query(
                "composite/governance-v1", new byte[0], fixture.state));
    }

    private static ComponentDescriptor descriptor(String id, String version,
                                                  long fromHeight, String topic, int quota) {
        return new ComponentDescriptor(id, version, "config", "records-state-v1",
                fromHeight, 0, List.of(topic), List.of("get"), quota);
    }

    private static void apply(CompositeStateMachine machine, MemoryState state,
                              long height, AppMessage... messages) {
        apply(machine, state, AppEffectEmitter.rejecting("effects unavailable"),
                height, messages);
    }

    private static void apply(CompositeStateMachine machine, MemoryState state,
                              AppEffectEmitter effects, long height, AppMessage... messages) {
        state.height = height;
        machine.apply(block(height, List.of(messages)), state, effects);
    }

    private static AppBlock block(long height, List<AppMessage> messages) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
    }

    private static AppMessage command(String sender, CompositeProfileGovernanceV1.Command command) {
        return rawCommand(sender, command.encode());
    }

    private static AppMessage rawCommand(String sender, byte[] body) {
        return message(sender, CompositeProfileGovernanceV1.TOPIC, body);
    }

    private static AppMessage business(String sender, String topic, byte[] body) {
        return message(sender, topic, body);
    }

    private static AppMessage message(String sender, String topic, byte[] body) {
        return AppMessage.builder().messageId(filled(7)).chainId("chain").topic(topic)
                .sender(HexFormat.of().parseHex(sender)).senderSeq(1).expiresAt(1_000)
                .body(body).authScheme(1).authProof(new byte[64]).build();
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hex(int value) {
        return HexFormat.of().formatHex(filled(value));
    }

    private record Proposal(byte[] hash) {
        private Proposal { hash = hash.clone(); }
    }

    private record Fixture(CompositeStateMachine machine,
                           MemoryState state,
                           CompositeProfile oldProfile,
                           CompositeProfile nextProfile,
                           RecordingComponent oldComponent,
                           RecordingComponent nextComponent) {
    }

    private static class RecordingComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;
        private int applied;

        private RecordingComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public ComponentDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) {
                applied++;
                state.put(VALUE_KEY, message.getBody());
            }
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            return state.get(VALUE_KEY).orElse(new byte[0]);
        }
    }

    private static final class EffectComponent extends RecordingComponent {
        private int resultCalls;

        private EffectComponent(ComponentDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            for (AppMessage ignored : block.messages()) {
                effects.emit(new EffectIntent("test.effect", new byte[]{1}, "records",
                        FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 3, null));
            }
        }

        @Override
        public void onEffectResult(AppBlock block, EffectResult result,
                                   AppStateWriter state, AppEffectEmitter effects) {
            resultCalls++;
            state.put("result".getBytes(StandardCharsets.US_ASCII), result.externalRef());
        }
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EffectId> ids = new ArrayList<>();

        private CapturingEmitter(long height) { this.height = height; }

        @Override public EffectId emit(EffectIntent intent) {
            EffectId id = new EffectId("chain", height, ids.size());
            ids.add(id);
            return id;
        }

        @Override public long pendingCount() { return ids.size(); }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new HashMap<>();
        private long height;

        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override public byte[] stateRoot() {
            return values.isEmpty() ? new byte[32]
                    : CompositeCommitmentV1.profileDigest(new byte[]{1});
        }

        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }

        @Override public long committedHeight() { return height; }

        private int size() { return values.size(); }
    }
}
