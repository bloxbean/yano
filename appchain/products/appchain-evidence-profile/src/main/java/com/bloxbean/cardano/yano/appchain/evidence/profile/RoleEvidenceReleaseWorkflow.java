package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceApprovalConsumptionV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.roles.RoleAuthorizationCapability;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalReferenceV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailTransitions;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Atomic role-approval + document-trail + evidence release coordinator. */
final class RoleEvidenceReleaseWorkflow implements CompositeWorkflow {
    static final String ID = "role-evidence-release";
    static final String PRODUCT_VERSION = "1.0.0";
    static final String TOPIC = EvidenceReleaseCommandV1.TOPIC;
    private static final String POLICY_ID = "evidence-release";
    private static final String PAYLOAD_DOMAIN = "evidence.release.v1";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration registry;
    private final ComponentGeneration approvals;
    private final ComponentGeneration documents;
    private final ComponentGeneration evidence;
    private final DocTrailStateMachine documentMachine;
    private final DocTrailTransitions documentTransitions = new DocTrailTransitions();
    private final EvidenceRegistryStateMachine evidenceMachine;
    private final RoleAuthorizationCapability authorization;

    RoleEvidenceReleaseWorkflow(WorkflowDescriptor descriptor,
                                ComponentGeneration registry,
                                ComponentGeneration approvals,
                                ComponentGeneration documents,
                                ComponentGeneration evidence,
                                DocTrailStateMachine documentMachine,
                                EvidenceRegistryStateMachine evidenceMachine,
                                String chainId) {
        this.descriptor = descriptor;
        this.registry = registry;
        this.approvals = approvals;
        this.documents = documents;
        this.evidence = evidence;
        this.documentMachine = documentMachine;
        this.evidenceMachine = evidenceMachine;
        this.authorization = new RoleAuthorizationCapability(chainId);
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            EvidenceReleaseCommandV1.decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_ROLE_EVIDENCE_RELEASE_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        int visibleIndex = 0;
        for (AppMessage source : execution.messages()) {
            int originalIndex = execution.originalMessageIndex(visibleIndex++);
            final EvidenceReleaseCommandV1 command;
            try {
                command = EvidenceReleaseCommandV1.decode(source.getBody());
            } catch (RuntimeException malformed) {
                continue;
            }
            AppStateWriter registryState = context.state(registry);
            AppStateWriter approvalState = context.state(approvals);
            AppStateWriter documentState = context.state(documents);
            AppStateWriter evidenceState = context.state(evidence);
            if (registryState.get(command.registryKey()).isEmpty()) continue;
            byte[] encodedProposal = approvalState.get(
                    RoleWorkflowKeys.proposal(command.approvalItemId())).orElse(null);
            if (encodedProposal == null) continue;
            final ApprovalProposalV1 proposal;
            try {
                proposal = ApprovalProposalV1.decode(encodedProposal);
            } catch (RuntimeException corrupt) {
                throw new IllegalStateException("corrupt role approval proposal", corrupt);
            }
            byte[] actionCommitment = command.commandHash();
            byte[] consumptionKey = RoleEvidenceKeys.approvalConsumption(
                    command.approvalItemId());
            var verified = authorization.verifyApproval(
                    new EvidenceApprovalReference(command.approvalItemId(),
                            actionCommitment, POLICY_ID, proposal.policyRevision()),
                    PAYLOAD_DOMAIN, actionCommitment, actionCommitment,
                    block.height(), approvalState.get(consumptionKey).isPresent(),
                    approvalState);
            if (!verified.accepted()) continue;

            AppMessage documentMessage = routed(source, "doc-trail.command.v1",
                    DocTrailStateMachine.append(command.documentEntityId(),
                            command.documentHash(), command.documentRef()));
            AppMessage evidenceMessage = routed(source, EvidenceContract.COMMAND_TOPIC,
                    command.evidenceCommand());
            if (!documentMachine.validate(documentMessage).isAccepted()
                    || !evidenceMachine.validate(evidenceMessage).isAccepted()
                    || !evidenceMachine.canApplyStorage(evidenceMessage,
                    command.evidenceStorageCommand(), evidenceState)) continue;
            if (context.claim(command.releaseId(), command.commandHash())
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) continue;
            TransitionPlans.commitIfApproved(documentTransitions.decide(
                            documentMessage.getBody(),
                            new TransitionContext(block.height(), block.timestamp(), originalIndex,
                                    source.getMessageId(), documentMessage.getTopic(), source.getSender()),
                            documentState),
                    documentState,
                    AppEffectEmitter.rejecting("document trail does not emit effects"));
            evidenceMachine.applyCommand(block, evidenceMessage,
                    evidenceState, context.effects(evidence));
            EvidenceApprovalConsumptionV1 consumption = new EvidenceApprovalConsumptionV1(
                    command.approvalItemId(), command.releaseId(), actionCommitment,
                    verified.value().policy().policyId(),
                    verified.value().policy().revision(), block.height(),
                    source.getMessageId());
            RoleAuthorizationCapability.ConsumptionPlan consumptionPlan =
                    authorization.planApprovalConsumption(
                            verified, consumptionKey, consumption.encode());
            approvalState.put(consumptionPlan.replayKey(),
                    consumptionPlan.applicationReceipt());
            approvalState.put(RoleEvidenceKeys.evidenceApproval(
                            command.evidenceStorageCommand().evidenceId(),
                            command.evidenceStorageCommand().businessVersion()),
                    command.approvalItemId().getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static AppMessage routed(AppMessage source, String topic, byte[] body) {
        return AppMessage.builder().version(source.getVersion()).messageId(source.getMessageId())
                .chainId(source.getChainId()).topic(topic).sender(source.getSender())
                .senderSeq(source.getSenderSeq()).expiresAt(source.getExpiresAt()).body(body)
                .authScheme(source.getAuthScheme()).authProof(source.getAuthProof()).build();
    }

    private record EvidenceApprovalReference(
            String proposalId,
            byte[] actionCommitment,
            String policyId,
            long policyRevision
    ) implements ApprovalReferenceV1 {
        private EvidenceApprovalReference {
            actionCommitment = actionCommitment.clone();
        }

        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
    }

}
