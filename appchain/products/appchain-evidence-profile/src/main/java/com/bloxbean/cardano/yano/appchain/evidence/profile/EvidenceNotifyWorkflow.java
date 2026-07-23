package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandCodec;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;

import java.util.List;
import java.util.Objects;

/**
 * Gated-profile route for notifying an evidence record that was already
 * created by the release workflow. Storage submission and republish commands
 * remain unavailable on the public evidence topic.
 */
final class EvidenceNotifyWorkflow implements CompositeWorkflow {
    static final String ID = "evidence-notify";
    static final String TOPIC = EvidenceContract.COMMAND_TOPIC;

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration evidence;
    private final EvidenceRegistryStateMachine evidenceMachine;

    EvidenceNotifyWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration evidence,
            EvidenceRegistryStateMachine evidenceMachine
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.evidenceMachine = Objects.requireNonNull(evidenceMachine, "evidenceMachine");
        if (!descriptor.workflowId().equals(ID) || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(List.of(evidence))) {
            throw new IllegalArgumentException(
                    "evidence-notify descriptor does not match its participant");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            if (!(EvidenceCommandCodec.decode(message.getBody())
                    instanceof NotifyEvidenceCommandV1)) {
                return AppStateMachine.AdmissionResult.reject(
                        "GATED_EVIDENCE_COMMAND_REQUIRES_RELEASE_WORKFLOW");
            }
            return evidenceMachine.validate(message);
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_EVIDENCE_NOTIFY_COMMAND");
        }
    }

    @Override
    public void apply(AppBlock block, CompositeWorkflowContext context) {
        for (AppMessage message : block.messages()) {
            try {
                if (!(EvidenceCommandCodec.decode(message.getBody())
                        instanceof NotifyEvidenceCommandV1)
                        || !evidenceMachine.validate(message).isAccepted()) {
                    continue;
                }
            } catch (RuntimeException malformed) {
                continue;
            }
            evidenceMachine.apply(withMessages(block, List.of(message)),
                    context.state(evidence), context.effects(evidence));
        }
    }

    private static AppBlock withMessages(AppBlock block, List<AppMessage> messages) {
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                AppBlockCodec.messagesRoot(messages), block.stateRoot(), messages,
                block.proposer(), FinalityCert.empty());
    }
}
