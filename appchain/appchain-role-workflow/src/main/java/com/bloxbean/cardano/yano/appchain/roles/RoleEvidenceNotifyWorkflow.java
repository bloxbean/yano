package com.bloxbean.cardano.yano.appchain.roles;

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

/** Notification-only public evidence route for the role-gated preset. */
final class RoleEvidenceNotifyWorkflow implements CompositeWorkflow {
    static final String ID = "evidence-notify";
    static final String TOPIC = EvidenceContract.COMMAND_TOPIC;

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration evidence;
    private final EvidenceRegistryStateMachine machine;

    RoleEvidenceNotifyWorkflow(WorkflowDescriptor descriptor,
                               ComponentGeneration evidence,
                               EvidenceRegistryStateMachine machine) {
        this.descriptor = descriptor;
        this.evidence = evidence;
        this.machine = machine;
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            if (!(EvidenceCommandCodec.decode(message.getBody()) instanceof NotifyEvidenceCommandV1)) {
                return AppStateMachine.AdmissionResult.reject(
                        "ROLE_GATED_EVIDENCE_REQUIRES_RELEASE_WORKFLOW");
            }
            return machine.validate(message);
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_EVIDENCE_NOTIFY_COMMAND");
        }
    }

    @Override
    public void apply(AppBlock block, CompositeWorkflowContext context) {
        for (AppMessage message : block.messages()) {
            try {
                if (!(EvidenceCommandCodec.decode(message.getBody()) instanceof NotifyEvidenceCommandV1)
                        || !machine.validate(message).isAccepted()) continue;
            } catch (RuntimeException malformed) {
                continue;
            }
            machine.apply(withMessages(block, List.of(message)), context.state(evidence),
                    context.effects(evidence));
        }
    }

    private static AppBlock withMessages(AppBlock block, List<AppMessage> messages) {
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                AppBlockCodec.messagesRoot(messages), block.stateRoot(), messages,
                block.proposer(), FinalityCert.empty());
    }
}
