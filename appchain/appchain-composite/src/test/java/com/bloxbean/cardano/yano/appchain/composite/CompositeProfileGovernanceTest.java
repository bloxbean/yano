package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeGovernanceStatusV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileEpochV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeProfileGovernanceTest {
    private static final String A = hex(1);
    private static final String B = hex(2);
    private static final String C = hex(3);
    private static final byte[] VALUE_KEY = new byte[]{1};

    @Test
    void thresholdAndAllMemberReadinessActivateExactCatalogProfileAtHeight() {
        ComponentDescriptor oldDescriptor = descriptor("records", "1.0.0", 1, "records.v1");
        ComponentDescriptor nextDescriptor = descriptor("records", "2.0.0", 8, "records.v2");
        RecordingComponent oldComponent = new RecordingComponent(oldDescriptor);
        RecordingComponent nextComponent = new RecordingComponent(nextDescriptor);
        CompositeProfile oldProfile = CompositeProfile.of(
                "records-direct", "1", List.of(oldDescriptor));
        CompositeProfile nextProfile = CompositeProfile.of(
                "records-gated", "2", List.of(nextDescriptor));
        AppChainConsensusProfile limits = AppChainTestProfiles.fromSettings(Map.of(
                "effects.enabled", "true", "effects.max-per-block", "4",
                "effects.result-window-blocks", "3"));
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(List.of(
                new CompositeProfileCatalog.Entry(oldProfile, List.of(oldComponent), List.of()),
                new CompositeProfileCatalog.Entry(nextProfile, List.of(nextComponent), List.of())),
                4, 3);
        CompositeStateMachine machine = CompositeStateMachine.create(context(limits), catalog,
                oldProfile.digest());
        MemoryState state = new MemoryState();

        apply(machine, state, 1);
        CompositeProfileEpochV1 genesis = CompositeProfileEpochV1.decode(machine.query(
                "composite/profile-epoch-v1", new byte[0], state));
        assertThat(genesis.epochNumber()).isZero();
        assertThat(genesis.canonicalProfileBytes()).containsExactly(oldProfile.canonicalBytes());
        assertThat(machine.operationalStatus())
                .containsEntry("currentMembershipDigest",
                        HexFormat.of().formatHex(membership().digest()))
                .containsEntry("currentMembershipThreshold", 2)
                .containsEntry("catalogReady", true);

        byte[] proposalId = filled(9);
        CompositeProfileGovernanceV1.Begin begin = new CompositeProfileGovernanceV1.Begin(
                proposalId, oldProfile.digest(), membership().digest(), nextProfile.digest(),
                nextProfile.canonicalBytes().length, 1, 8, 12);
        apply(machine, state, 2,
                command(A, begin),
                command(A, new CompositeProfileGovernanceV1.Chunk(
                        proposalId, 0, nextProfile.canonicalBytes())));
        apply(machine, state, 3,
                command(A, new CompositeProfileGovernanceV1.Seal(proposalId)));

        byte[] proposalHash = CompositeProfileGovernanceV1.proposalHash("chain", begin);
        apply(machine, state, 4,
                command(A, new CompositeProfileGovernanceV1.Approve(proposalHash)),
                command(B, new CompositeProfileGovernanceV1.Approve(proposalHash)),
                command(A, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest())),
                command(B, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest())));
        CompositeGovernanceStatusV1 waiting = status(machine, state);
        assertThat(waiting.proposal().statusCode()).isEqualTo(1);

        apply(machine, state, 5,
                command(C, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest())));
        assertThat(status(machine, state).proposal().statusCode()).isEqualTo(2);

        assertThat(machine.validateForBlock(
                business(A, "records.v1", new byte[]{7}), 7, state).isAccepted()).isTrue();
        assertThat(machine.validateForBlock(
                business(A, "records.v2", new byte[]{7}), 7, state).isAccepted()).isFalse();
        assertThat(machine.validateForBlock(
                business(A, "records.v1", new byte[]{8}), 8, state).isAccepted()).isFalse();
        assertThat(machine.validateForBlock(
                business(A, "records.v2", new byte[]{8}), 8, state).isAccepted()).isTrue();

        apply(machine, state, 7, business(A, "records.v1", new byte[]{7}));
        assertThat(state.get(CompositeStateKeys.componentKey("records", VALUE_KEY)))
                .contains(new byte[]{7});
        apply(machine, state, 8,
                business(A, "records.v1", new byte[]{99}),
                business(A, "records.v2", new byte[]{8}));

        assertThat(state.get(CompositeStateKeys.componentKey("records", VALUE_KEY)))
                .contains(new byte[]{8});
        assertThat(machine.query("composite/active-profile-v1", new byte[0], state))
                .containsExactly(nextProfile.canonicalBytes());
        assertThat(machine.validateForBlock(
                business(A, "records.v1", new byte[]{9}), 9, state).isAccepted()).isFalse();
        assertThat(machine.validateForBlock(
                business(A, "records.v2", new byte[]{9}), 9, state).isAccepted()).isTrue();
        CompositeGovernanceStatusV1 active = status(machine, state);
        assertThat(active.currentEpoch()).isEqualTo(1);
        assertThat(active.activeFromHeight()).isEqualTo(8);
        assertThat(active.proposal()).isNull();
        assertThat(active.drains()).singleElement().satisfies(drain -> {
            assertThat(drain.componentId()).isEqualTo("records");
            assertThat(drain.throughHeight()).isEqualTo(11);
        });
        CompositeProfileEpochV1 epoch = CompositeProfileEpochV1.decode(machine.query(
                "composite/profile-epoch-v1", ByteBuffer.allocate(8).putLong(1).array(), state));
        assertThat(epoch.previousProfileDigest()).containsExactly(oldProfile.digest());
        assertThat(epoch.proposalHash()).containsExactly(proposalHash);
    }

    private static CompositeGovernanceStatusV1 status(
            CompositeStateMachine machine, MemoryState state) {
        return CompositeGovernanceStatusV1.decode(
                machine.query("composite/governance-v1", new byte[0], state));
    }

    private static AppStateMachineContext context(AppChainConsensusProfile limits) {
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
            @Override public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView>
            membershipView() {
                return Optional.of(ignored -> membership());
            }
        };
    }

    private static AppChainMembershipEpoch membership() {
        return new AppChainMembershipEpoch(0, List.of(A, B, C), 2);
    }

    private static ComponentDescriptor descriptor(
            String id, String version, long fromHeight, String topic) {
        return new ComponentDescriptor(id, version, "config", "records-state-v1",
                fromHeight, 0, List.of(topic), List.of("get"), 1);
    }

    private static void apply(CompositeStateMachine machine,
                              MemoryState state,
                              long height,
                              AppMessage... messages) {
        state.height = height;
        machine.apply(block(height, List.of(messages)), state);
    }

    private static AppBlock block(long height, List<AppMessage> messages) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], messages, new byte[32], FinalityCert.empty());
    }

    private static AppMessage command(String sender, CompositeProfileGovernanceV1.Command command) {
        return message(sender, CompositeProfileGovernanceV1.TOPIC, command.encode());
    }

    private static AppMessage business(String sender, String topic, byte[] body) {
        return message(sender, topic, body);
    }

    private static AppMessage message(String sender, String topic, byte[] body) {
        return AppMessage.builder().messageId(filled(4)).chainId("chain").topic(topic)
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

    private static final class RecordingComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;

        private RecordingComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public ComponentDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, AppStateWriter state,
                          com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) state.put(VALUE_KEY, message.getBody());
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            return state.get(VALUE_KEY).orElse(new byte[0]);
        }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new HashMap<>();
        private long height;

        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() {
            return values.isEmpty() ? new byte[32] : CompositeCommitmentV1.profileDigest(new byte[]{1});
        }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        @Override public long committedHeight() { return height; }
    }
}
