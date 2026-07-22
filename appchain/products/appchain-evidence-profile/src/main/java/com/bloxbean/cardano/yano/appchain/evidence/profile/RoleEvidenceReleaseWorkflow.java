package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;

import java.security.MessageDigest;
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
    private final EvidenceRegistryStateMachine evidenceMachine;

    RoleEvidenceReleaseWorkflow(WorkflowDescriptor descriptor,
                                ComponentGeneration registry,
                                ComponentGeneration approvals,
                                ComponentGeneration documents,
                                ComponentGeneration evidence,
                                DocTrailStateMachine documentMachine,
                                EvidenceRegistryStateMachine evidenceMachine) {
        this.descriptor = descriptor;
        this.registry = registry;
        this.approvals = approvals;
        this.documents = documents;
        this.evidence = evidence;
        this.documentMachine = documentMachine;
        this.evidenceMachine = evidenceMachine;
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
    public void apply(AppBlock block, CompositeWorkflowContext context) {
        for (AppMessage source : block.messages()) {
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
            if (proposal.status() != ApprovalProposalV1.ProposalStatus.APPROVED
                    || !proposal.policyId().equals(POLICY_ID)
                    || !proposal.payloadDomain().equals(PAYLOAD_DOMAIN)
                    || !MessageDigest.isEqual(
                    proposal.payloadHash(), command.commandHash())) continue;

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
            documentMachine.apply(withMessages(block, List.of(documentMessage)), documentState);
            evidenceMachine.apply(withMessages(block, List.of(evidenceMessage)), evidenceState,
                    context.effects(evidence));
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

    private static AppBlock withMessages(AppBlock block, List<AppMessage> messages) {
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                AppBlockCodec.messagesRoot(messages), block.stateRoot(), messages,
                block.proposer(), FinalityCert.empty());
    }
}
