package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.ComposableAppStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.GovernedRoleApprovalWorkflow;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Committed lean document + role + approval reference composition. */
public final class DocumentReviewPreset {
    public static final String PROFILE_ID = "document-role-approval-v1";
    public static final String PROFILE_VERSION = "1.0.0";
    public static final String POLICY_ID = "document-release";
    public static final String DOCUMENTS_COMPONENT_ID = "documents";

    private DocumentReviewPreset() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        GovernedGenesisV1 genesis = ShowcaseAuthenticatedMapConfig.documentReviewGenesis(
                context.chainId());
        byte[] applicationId = Blake2bUtil.blake2bHash256(genesis.encode());
        String configurationId = PROFILE_ID + ":" + HexFormat.of().formatHex(applicationId);

        ComponentDescriptor actorsDescriptor = descriptor(
                DomainActorRegistryComponent.COMPONENT_ID, configurationId,
                "domain-actors-state-v1", List.of(DomainActorRegistryComponent.TOPIC),
                List.of(DomainActorRegistryComponent.QUERY_ACTOR,
                        DomainActorRegistryComponent.QUERY_ACTOR_CURRENT,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION_CURRENT), 0);
        ComponentDescriptor approvalsDescriptor = descriptor(
                RoleAwareApprovalsComponent.COMPONENT_ID, configurationId,
                "role-approvals-state-v1", List.of(),
                List.of(RoleAwareApprovalsComponent.QUERY_POLICY,
                        RoleAwareApprovalsComponent.QUERY_POLICY_CURRENT,
                        RoleAwareApprovalsComponent.QUERY_PROPOSAL,
                        RoleAwareApprovalsComponent.QUERY_STATS), 0);
        ComponentDescriptor documentsDescriptor = descriptor(
                DOCUMENTS_COMPONENT_ID, "approval-gated-v1",
                "document-trail-state-v1", List.of(),
                List.of(DocTrailStateMachine.QUERY_HEAD), 0);
        ComponentDescriptor receiptsDescriptor = descriptor(
                DocumentReviewReceiptStateMachine.ID, "approval-consumption-v1",
                "document-review-receipts-state-v1", List.of(),
                List.of(DocumentReviewReceiptStateMachine.QUERY_RECEIPT), 0);

        DomainActorRegistryComponent actors = DomainActorRegistryComponent.genesisBound(
                actorsDescriptor, context.chainId(), genesis, applicationId);
        RoleAwareApprovalsComponent approvals = new RoleAwareApprovalsComponent(
                approvalsDescriptor, genesis);
        DocTrailStateMachine documents = new DocTrailStateMachine();
        DocumentReviewReceiptStateMachine receipts = new DocumentReviewReceiptStateMachine();
        ComponentGeneration actorsGeneration = actorsDescriptor.generation();
        ComponentGeneration approvalsGeneration = approvalsDescriptor.generation();
        ComponentGeneration documentsGeneration = documentsDescriptor.generation();
        ComponentGeneration receiptsGeneration = receiptsDescriptor.generation();

        WorkflowDescriptor roleDescriptor = new WorkflowDescriptor(
                GovernedRoleApprovalWorkflow.WORKFLOW_ID,
                GovernedRoleApprovalWorkflow.PRODUCT_VERSION,
                GovernedRoleApprovalWorkflow.TOPIC, 1, 0,
                List.of(actorsGeneration, approvalsGeneration), 0);
        GovernedRoleApprovalWorkflow roles = new GovernedRoleApprovalWorkflow(
                roleDescriptor, actorsGeneration, approvalsGeneration,
                context.chainId(), applicationId, genesis,
                DocumentReviewCommandV1.PAYLOAD_DOMAIN);
        WorkflowDescriptor reviewDescriptor = new WorkflowDescriptor(
                DocumentReviewWorkflow.ID, DocumentReviewWorkflow.VERSION,
                DocumentReviewCommandV1.TOPIC, 1, 0,
                List.of(approvalsGeneration, documentsGeneration, receiptsGeneration), 0);
        DocumentReviewWorkflow review = new DocumentReviewWorkflow(
                reviewDescriptor, approvalsGeneration, documentsGeneration,
                receiptsGeneration, context.chainId());

        return ComposableAppStateMachine.builder(DocumentReviewStateMachineProvider.ID,
                        context, PROFILE_ID, PROFILE_VERSION)
                .machine(actorsDescriptor, actors)
                .machine(approvalsDescriptor, approvals)
                .machine(documentsDescriptor, documents)
                .machine(receiptsDescriptor, receipts)
                .workflow(roles)
                .workflow(review)
                .build();
    }

    private static ComponentDescriptor descriptor(
            String id, String configurationId, String stateCompatibilityId,
            List<String> topics, List<String> queries, int effects
    ) {
        return new ComponentDescriptor(id, PROFILE_VERSION, configurationId,
                stateCompatibilityId, 1, 0, topics, queries, effects);
    }
}
