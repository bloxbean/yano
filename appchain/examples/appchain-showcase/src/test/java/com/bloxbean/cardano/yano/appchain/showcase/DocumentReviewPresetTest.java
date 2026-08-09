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

class DocumentReviewPresetTest {
    @Test
    void presetComposesStockRoleApprovalAndDocumentCapabilities() {
        CompositeStateMachine machine = DocumentReviewPreset.create(context());

        assertThat(machine.id()).isEqualTo("document-review");
        assertThat(machine.profile().profileId()).isEqualTo("document-role-approval-v1");
        assertThat(machine.profile().components())
                .extracting(component -> component.componentId())
                .containsExactly("domain-actors", "role-approvals", "documents",
                        "document-review-receipts");
        assertThat(machine.profile().workflows())
                .extracting(workflow -> workflow.workflowId())
                .containsExactly("role-approval-v1", "document-review-release");
        assertThat(machine.profile().components().stream()
                .filter(component -> component.componentId().equals("documents"))
                .findFirst().orElseThrow().queryPaths()).containsExactly("head");
        assertThat(ShowcaseAuthenticatedMapConfig.documentReviewGenesis(
                "document-review-chain").approvalPolicy(DocumentReviewPreset.POLICY_ID))
                .isNotNull();
    }

    @Test
    void commandIsCanonicalAndBindsItsApprovalCommitment() {
        DocumentReviewCommandV1 command = new DocumentReviewCommandV1(
                "review-1", "document-release", 1, "document-1",
                new byte[32], "showcase://documents/1");

        DocumentReviewCommandV1 decoded = DocumentReviewCommandV1.decode(command.encode());
        assertThat(decoded.proposalId()).isEqualTo(command.proposalId());
        assertThat(decoded.policyId()).isEqualTo(command.policyId());
        assertThat(decoded.documentEntityId()).isEqualTo(command.documentEntityId());
        assertThat(decoded.documentHash()).containsExactly(command.documentHash());
        assertThat(decoded.actionCommitment()).containsExactly(command.actionCommitment());
        byte[] trailing = java.util.Arrays.copyOf(command.encode(), command.encode().length + 1);
        assertThatThrownBy(() -> DocumentReviewCommandV1.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AppStateMachineContext context() {
        Map<String, String> settings = Map.of("membership.mode", "governed");
        AppChainConsensusProfile profile = AppChainTestProfiles.fromSettings(settings);
        return new AppStateMachineContext() {
            @Override public String chainId() { return "document-review-chain"; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(profile);
            }
        };
    }
}
