package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
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
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        for (AppMessage message : execution.messages()) {
            try {
                if (!(EvidenceCommandCodec.decode(message.getBody()) instanceof NotifyEvidenceCommandV1)
                        || !machine.validate(message).isAccepted()) continue;
            } catch (RuntimeException malformed) {
                continue;
            }
            machine.applyCommand(block, message,
                    context.state(evidence), context.effects(evidence));
        }
    }
}
