package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.RoleAuthorizationCapability;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailTransitions;

import java.util.List;
import java.util.Objects;

/** Atomic approval consumption and document-head transition. */
final class DocumentReviewWorkflow implements CompositeWorkflow {
    static final String ID = "document-review-release";
    static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration approvals;
    private final ComponentGeneration documents;
    private final ComponentGeneration receipts;
    private final RoleAuthorizationCapability authorization;
    private final DocTrailTransitions documentTransitions = new DocTrailTransitions();

    DocumentReviewWorkflow(WorkflowDescriptor descriptor,
                           ComponentGeneration approvals,
                           ComponentGeneration documents,
                           ComponentGeneration receipts,
                           String chainId) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.authorization = new RoleAuthorizationCapability(chainId);
        if (!ID.equals(descriptor.workflowId())
                || !DocumentReviewCommandV1.TOPIC.equals(descriptor.topic())
                || !descriptor.participants().equals(List.of(approvals, documents, receipts))) {
            throw new IllegalArgumentException("invalid document-review workflow descriptor");
        }
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            DocumentReviewCommandV1.decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_DOCUMENT_REVIEW_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        int visibleIndex = 0;
        for (AppMessage source : execution.messages()) {
            int originalIndex = execution.originalMessageIndex(visibleIndex++);
            DocumentReviewCommandV1 command;
            try {
                command = DocumentReviewCommandV1.decode(source.getBody());
            } catch (RuntimeException malformed) {
                continue;
            }
            var approvalState = context.state(approvals);
            var documentState = context.state(documents);
            var receiptState = context.state(receipts);
            byte[] receiptKey = DocumentReviewReceiptStateMachine.receiptKey(command.proposalId());
            byte[] commitment = command.actionCommitment();
            var verified = authorization.verifyApproval(
                    command, DocumentReviewCommandV1.PAYLOAD_DOMAIN,
                    commitment, commitment, block.height(),
                    receiptState.get(receiptKey).isPresent(), approvalState);
            if (!verified.accepted()
                    || context.claim(command.proposalId(), commitment)
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) {
                continue;
            }

            byte[] body = DocTrailStateMachine.append(command.documentEntityId(),
                    command.documentHash(), command.documentRef());
            TransitionPlans.commitIfApproved(documentTransitions.decide(body,
                            new TransitionContext(block.height(), block.timestamp(), originalIndex,
                                    source.getMessageId(), "doc-trail.command.v1", source.getSender()),
                            documentState),
                    documentState,
                    AppEffectEmitter.rejecting("document review does not emit effects"));

            DocumentReviewReceiptV1 receipt = new DocumentReviewReceiptV1(
                    command.proposalId(), command.documentEntityId(), commitment,
                    command.policyId(), command.policyRevision(), block.height(),
                    source.getMessageId());
            var plan = authorization.planApprovalConsumption(
                    verified, receiptKey, receipt.encode());
            receiptState.put(plan.replayKey(), plan.applicationReceipt());
        }
    }
}
