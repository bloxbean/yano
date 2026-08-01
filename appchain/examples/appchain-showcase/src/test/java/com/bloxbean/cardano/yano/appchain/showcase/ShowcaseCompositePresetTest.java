package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcaseCompositePresetTest {
    @Test
    void presetCommitsOneExplicitDemoAssembly() {
        CompositeStateMachine machine = ShowcaseCompositePreset.create(context(Map.of(
                "effects.enabled", "true",
                "effects.max-per-block", "2",
                "effects.result-window-blocks", "1000",
                "effects.max-expiry-blocks", "1000")));

        assertThat(machine.id()).isEqualTo("showcase-composite");
        assertThat(machine.profile().profileId()).isEqualTo("order-approval-outbox-v1");
        assertThat(machine.profile().components()).extracting(component -> component.componentId())
                .containsExactly("orders", "approvals", "audit", "release");
        assertThat(machine.profile().workflows()).extracting(workflow -> workflow.workflowId())
                .containsExactly("showcase-order-release");
        assertThat(machine.profile().digest()).hasSize(32);
    }

    @Test
    void presetRejectsEffectBudgetBelowItsCommittedQuota() {
        assertThatThrownBy(() -> ShowcaseCompositePreset.create(context(Map.of(
                "effects.enabled", "true",
                "effects.max-per-block", "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effect quota");
    }

    @Test
    void releaseCommandIsCanonicalAndHashStable() {
        ShowcaseReleaseCommand command = new ShowcaseReleaseCommand(
                "release-1", "order-1".getBytes(), "proposal-1");

        ShowcaseReleaseCommand decoded = ShowcaseReleaseCommand.decode(command.encode());
        assertThat(decoded.releaseId()).isEqualTo(command.releaseId());
        assertThat(decoded.orderKey()).containsExactly(command.orderKey());
        assertThat(decoded.approvalId()).isEqualTo(command.approvalId());
        assertThat(command.commandHash()).containsExactly(
                decoded.commandHash());
        byte[] nonCanonical = java.util.Arrays.copyOf(command.encode(), command.encode().length + 1);
        assertThatThrownBy(() -> ShowcaseReleaseCommand.decode(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        AppChainConsensusProfile profile = AppChainTestProfiles.fromSettings(settings);
        return new AppStateMachineContext() {
            @Override public String chainId() { return "workflow-chain"; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(profile);
            }
        };
    }
}
