package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateKeys;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
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

import static org.assertj.core.api.Assertions.assertThat;

class RoleEvidencePresetTest {
    private static final String CHAIN = "role-demo";
    private static final byte[] MEMBER = filled(0x44);

    @Test
    void presetCommitsRoleComponentsRoutesAndQueries() {
        CompositeStateMachine machine = RoleEvidencePreset.create(context());

        assertThat(machine.id()).isEqualTo(RoleEvidenceStateMachineProvider.ID);
        assertThat(machine.profile().profileId()).isEqualTo(RoleEvidencePreset.PROFILE_ID);
        assertThat(machine.profile().components()).extracting(component -> component.componentId())
                .containsExactly("registry", "domain-actors", "role-approvals",
                        "doc-trail", "evidence");
        assertThat(machine.profile().workflows()).extracting(workflow -> workflow.workflowId())
                .containsExactly("evidence-notify", "role-approval", "role-evidence-release");
        assertThat(machine.profile().components()).filteredOn(component ->
                        component.componentId().equals("domain-actors"))
                .singleElement().satisfies(component -> {
                    assertThat(component.topics()).containsExactly("actors.command.v1");
                    assertThat(component.queryPaths()).containsExactly(
                            "actor", "actor-current", "organization", "organization-current");
                });
        assertThat(machine.profile().components()).filteredOn(component ->
                        component.componentId().equals("role-approvals"))
                .singleElement().satisfies(component -> {
                    assertThat(component.topics()).isEmpty();
                    assertThat(component.queryPaths()).containsExactly(
                            "evidence-approval", "policy", "policy-current", "proposal", "stats");
                });
        assertThat(machine.profile().components()).filteredOn(component ->
                        component.componentId().equals("doc-trail"))
                .singleElement().satisfies(component -> {
                    assertThat(component.topics()).isEmpty();
                    assertThat(component.configurationId()).isEqualTo("workflow-only-v1");
                });
    }

    @Test
    void providerIsDiscoverableAndRequiresContext() {
        assertThat(java.util.ServiceLoader.load(
                com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider.class).stream()
                .map(provider -> provider.get().id()))
                .contains(RoleEvidenceStateMachineProvider.ID);
    }

    @Test
    void approvedRoleProposalAtomicallyReleasesEvidenceAndWritesItsAuditLinkOnce() {
        CompositeStateMachine machine = RoleEvidencePreset.create(context());
        MemoryState state = new MemoryState();
        machine.apply(block(1), state, new CapturingEmitter(1));

        SubmitEvidenceCommandV1 evidence = evidence("evidence-a");
        byte[] registryKey = "evidence/evidence-a".getBytes(StandardCharsets.US_ASCII);
        EvidenceReleaseCommandV1 release = new EvidenceReleaseCommandV1(
                "release-evidence-a-v1", registryKey, "approval-evidence-a-v1",
                "document-evidence-a", filled(0x66), "object:evidence-a/v1",
                evidence.encode());
        state.put(CompositeStateKeys.componentKey("registry", registryKey), filled(0x66));
        state.put(CompositeStateKeys.componentKey("role-approvals",
                        RoleWorkflowKeys.proposal(release.approvalItemId())),
                approvedProposal(release, filled(0x31)).encode());

        CapturingEmitter mismatched = new CapturingEmitter(2);
        machine.apply(block(2, message(2, release.encode())), state, mismatched);
        assertThat(mismatched.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("evidence-a", 1)))).isEmpty();

        state.put(CompositeStateKeys.componentKey("role-approvals",
                        RoleWorkflowKeys.proposal(release.approvalItemId())),
                approvedProposal(release, release.commandHash()).encode());
        EvidenceReleaseCommandV1 tamperedWrapper = new EvidenceReleaseCommandV1(
                release.releaseId(), release.registryKey(), release.approvalItemId(),
                release.documentEntityId(), release.documentHash(),
                "object:attacker-selected-reference", release.evidenceCommand());
        CapturingEmitter tampered = new CapturingEmitter(3);
        machine.apply(block(3, message(3, tamperedWrapper.encode())), state, tampered);
        assertThat(tampered.intents).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("evidence-a", 1)))).isEmpty();

        CapturingEmitter emitted = new CapturingEmitter(3);
        machine.apply(block(4, message(4, release.encode())), state, emitted);

        assertThat(emitted.intents).extracting(EffectIntent::type)
                .containsExactly("object.put", "ipfs.pin");
        assertThat(state.get(CompositeStateKeys.componentKey("evidence",
                EvidenceKeys.recordKey("evidence-a", 1)))).isPresent();
        assertThat(state.get(CompositeStateKeys.componentKey("role-approvals",
                RoleWorkflowKeys.evidenceApproval("evidence-a", 1))))
                .contains(release.approvalItemId().getBytes(StandardCharsets.US_ASCII));

        CapturingEmitter replay = new CapturingEmitter(4);
        machine.apply(block(5, message(5, release.encode())), state, replay);
        assertThat(replay.intents).isEmpty();
    }

    private static AppStateMachineContext context() {
        String member = HexFormat.of().formatHex(MEMBER);
        return new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() {
                return Map.of("effects.enabled", "true", "effects.max-per-block", "32");
            }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
            consensusProfile() {
                return Optional.of(AppChainTestProfiles.enabledEffects(32, 8));
            }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView>
            membershipView() {
                AppChainMembershipEpoch epoch = new AppChainMembershipEpoch(
                        0, List.of(member), 1);
                return Optional.of(height -> epoch);
            }
        };
    }

    private static ApprovalProposalV1 approvedProposal(EvidenceReleaseCommandV1 release,
                                                       byte[] payloadHash) {
        return new ApprovalProposalV1(release.approvalItemId(), "evidence-release", 1,
                filled(0x12), "evidence.release.v1", payloadHash, 100,
                ApprovalProposalV1.ProposalStatus.APPROVED,
                "manufacturer-a", "manufacturer-org", 1, "manufacturer", 1,
                "manufacturer-key", 1, List.of());
    }

    private static SubmitEvidenceCommandV1 evidence(String id) {
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive", "staging/certificate.bin", "evidence/certificate.bin",
                DigestAlgorithm.SHA_256, filled(0x11), 32,
                "application/octet-stream", null);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1("local", CanonicalCid.fromText(
                "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi"),
                true, "demo-single");
        return new SubmitEvidenceCommandV1(id, 1, object.encode(), filled(0x22),
                ipfs.encode(), filled(0x33), "primary", "evidence-ready", filled(0x55));
    }

    private static AppMessage message(long sequence, byte[] body) {
        byte[] id = new byte[32];
        id[31] = (byte) sequence;
        return AppMessage.builder().messageId(id).chainId(CHAIN)
                .topic(EvidenceReleaseCommandV1.TOPIC).sender(MEMBER).senderSeq(sequence)
                .expiresAt(Long.MAX_VALUE).body(body).authScheme(0)
                .authProof(new byte[64]).build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(1, CHAIN, height, new byte[32], 0, new byte[0],
                1_700_000_000_000L + height, new byte[32], new byte[32],
                List.of(messages), MEMBER, FinalityCert.empty());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EffectIntent> intents = new ArrayList<>();

        private CapturingEmitter(long height) { this.height = height; }
        @Override public EffectId emit(EffectIntent intent) {
            intents.add(intent);
            return new EffectId(CHAIN, height, intents.size() - 1);
        }
        @Override public long pendingCount() { return 0; }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return 0; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }
    }
}
