package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RoleApprovalsPresetTest {
    private static final String CHAIN = "generic-role-chain";
    private static final byte[] MEMBER = filled(0x41);

    @Test
    void presetCommitsOnlyGenericRoleComponentsAndEmitsNoEffects() {
        CompositeStateMachine machine = RoleApprovalsPreset.create(context());

        assertThat(machine.id()).isEqualTo(RoleApprovalsStateMachineProvider.ID);
        assertThat(machine.profile().profileId()).isEqualTo(RoleApprovalsPreset.PROFILE_ID);
        assertThat(machine.profile().components()).extracting(component -> component.componentId())
                .containsExactly("domain-actors", "role-approvals");
        assertThat(machine.profile().workflows()).extracting(workflow -> workflow.workflowId())
                .containsExactly(RoleApprovalWorkflow.WORKFLOW_ID);
        assertThat(machine.profile().components()).allSatisfy(component ->
                assertThat(component.maxEffectsPerBlock()).isZero());
        assertThat(machine.profile().workflows()).allSatisfy(workflow ->
                assertThat(workflow.maxEffectsPerBlock()).isZero());
        assertThat(machine.profile().components()).filteredOn(component ->
                        component.componentId().equals("role-approvals"))
                .singleElement().satisfies(component ->
                        assertThat(component.queryPaths()).containsExactly(
                                "policy", "policy-current", "proposal", "stats"));
    }

    @Test
    void providerReplayRestartAndSnapshotAreByteDeterministic() {
        for (long restartAt : List.of(1L, 2L, 3L)) {
            StateMachineConformance.Result result = StateMachineConformance.builder(
                            new RoleApprovalsStateMachineProvider())
                    .chainId(CHAIN)
                    .blocks(4)
                    .messagesPerBlock(2)
                    .runs(2)
                    .restartAtHeight(restartAt)
                    .snapshotAtHeight(restartAt)
                    .messageGenerator((height, index, random) ->
                            new StateMachineConformance.CorpusMessage(
                                    index == 0 ? DomainActorRegistryComponent.TOPIC
                                            : RoleApprovalWorkflow.TOPIC,
                                    new byte[]{(byte) height, (byte) index}))
                    .run();
            assertThat(result.deterministic()).as(result.describeDivergence()).isTrue();
            assertThat(result.outcomesPerRun().getFirst().values()).allSatisfy(outcome ->
                    assertThat(outcome.effectHashes()).isEmpty());
        }
    }

    @Test
    void providerIsDiscoverableAndContextRequired() {
        var provider = java.util.ServiceLoader.load(
                        com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider.class)
                .stream().map(java.util.ServiceLoader.Provider::get)
                .filter(candidate -> RoleApprovalsStateMachineProvider.ID.equals(candidate.id()))
                .findFirst().orElseThrow();
        assertThat(provider).isInstanceOf(RoleApprovalsStateMachineProvider.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(provider::create)
                .isInstanceOf(IllegalStateException.class);
    }

    private static AppStateMachineContext context() {
        String member = HexFormat.of().formatHex(MEMBER);
        return new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return Map.of(); }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
            consensusProfile() {
                return Optional.of(AppChainTestProfiles.fromSettings(Map.of()));
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

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
