package com.bloxbean.cardano.yano.appchain.composite.stock;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceReleaseCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryConfig;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Stock atomic registry + approval + document-trail + evidence release coordinator. */
final class EvidenceReleaseWorkflow implements CompositeWorkflow {
    public static final String ID = "evidence-release";
    public static final String PRODUCT_VERSION = "1.1.0";
    public static final String TOPIC = EvidenceReleaseCommandV1.TOPIC;

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration registry;
    private final ComponentGeneration approvals;
    private final ComponentGeneration docTrail;
    private final ComponentGeneration evidence;
    private final DocTrailStateMachine docTrailMachine;
    private final EvidenceRegistryStateMachine evidenceMachine;
    private final EvidenceRegistryConfig evidenceConfig;

    EvidenceReleaseWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration registry,
            ComponentGeneration approvals,
            ComponentGeneration docTrail,
            ComponentGeneration evidence,
            DocTrailStateMachine docTrailMachine,
            EvidenceRegistryStateMachine evidenceMachine,
            EvidenceRegistryConfig evidenceConfig
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.registry = registry;
        this.approvals = approvals;
        this.docTrail = docTrail;
        this.evidence = evidence;
        this.docTrailMachine = Objects.requireNonNull(docTrailMachine, "docTrailMachine");
        this.evidenceMachine = Objects.requireNonNull(evidenceMachine, "evidenceMachine");
        this.evidenceConfig = Objects.requireNonNull(evidenceConfig, "evidenceConfig");
        if (!descriptor.workflowId().equals(ID) || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(
                List.of(registry, approvals, docTrail, evidence))) {
            throw new IllegalArgumentException("evidence-release descriptor does not match its participants");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            EvidenceReleaseCommandV1.decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_EVIDENCE_RELEASE_COMMAND");
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
            AppStateWriter documentState = context.state(docTrail);
            AppStateWriter evidenceState = context.state(evidence);

            if (registryState.get(command.registryKey()).isEmpty()) {
                continue;
            }
            byte[] approval = approvalState.get(
                    ApprovalsStateMachine.itemKey(command.approvalItemId())).orElse(null);
            if (approval == null) {
                continue;
            }
            ApprovalsStateMachine.Item item = ApprovalsStateMachine.decodeItem(approval);
            if (item.status() != ApprovalsStateMachine.STATUS_APPROVED
                    || !MessageDigest.isEqual(item.payloadHash(), command.evidenceCommandHash())) {
                continue;
            }

            AppMessage documentMessage = routed(source, "doc-trail.command.v1",
                    DocTrailStateMachine.append(command.documentEntityId(),
                            command.documentHash(), command.documentRef()));
            AppMessage evidenceMessage = routed(source, EvidenceContract.COMMAND_TOPIC,
                    command.evidenceCommand());
            if (!docTrailMachine.validate(documentMessage).isAccepted()
                    || !evidenceMachine.validate(evidenceMessage).isAccepted()) {
                continue;
            }
            if (!canApply(command.evidenceStorageCommand(), source, evidenceState)) {
                continue;
            }
            if (context.claim(command.releaseId(), command.commandHash())
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) {
                continue;
            }

            docTrailMachine.apply(withMessages(block, List.of(documentMessage)), documentState);
            evidenceMachine.apply(withMessages(block, List.of(evidenceMessage)), evidenceState,
                    context.effects(evidence));
        }
    }

    private boolean canApply(
            EvidenceCommandV1 command,
            AppMessage source,
            AppStateWriter evidenceState
    ) {
        if (command instanceof SubmitEvidenceCommandV1 submit) {
            return evidenceConfig.isIssuer(source.getSender())
                    && evidenceState.get(EvidenceKeys.headKey(submit.evidenceId())).isEmpty()
                    && evidenceState.get(EvidenceKeys.recordKey(
                    submit.evidenceId(), submit.businessVersion())).isEmpty();
        }
        if (!(command instanceof RepublishEvidenceCommandV1 republish)) {
            return false;
        }
        byte[] headBytes = evidenceState.get(
                EvidenceKeys.headKey(republish.evidenceId())).orElse(null);
        if (headBytes == null) {
            return false;
        }
        EvidenceHeadV1 head = EvidenceHeadV1.decode(headBytes);
        if (!head.evidenceId().equals(republish.evidenceId())
                || !MessageDigest.isEqual(head.ownerPublicKey(), source.getSender())) {
            return false;
        }
        final long nextVersion;
        try {
            nextVersion = Math.addExact(head.latestVersion(), 1);
        } catch (ArithmeticException exhausted) {
            return false;
        }
        if (republish.businessVersion() != nextVersion
                || evidenceState.get(EvidenceKeys.recordKey(
                republish.evidenceId(), nextVersion)).isPresent()) {
            return false;
        }
        byte[] priorBytes = evidenceState.get(EvidenceKeys.recordKey(
                republish.evidenceId(), head.latestVersion())).orElse(null);
        if (priorBytes == null) {
            return false;
        }
        EvidenceRecordV1 prior = EvidenceRecordV1.decode(priorBytes);
        return prior.evidenceId().equals(republish.evidenceId())
                && prior.businessVersion() == head.latestVersion()
                && MessageDigest.isEqual(prior.ownerPublicKey(), head.ownerPublicKey())
                && EvidenceStatus.derive(prior).permitsRepublish();
    }

    private static AppMessage routed(AppMessage source, String topic, byte[] body) {
        return AppMessage.builder()
                .version(source.getVersion())
                .messageId(source.getMessageId())
                .chainId(source.getChainId())
                .topic(topic)
                .sender(source.getSender())
                .senderSeq(source.getSenderSeq())
                .expiresAt(source.getExpiresAt())
                .body(body)
                .authScheme(source.getAuthScheme())
                .authProof(source.getAuthProof())
                .build();
    }

    private static AppBlock withMessages(AppBlock block, List<AppMessage> messages) {
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                AppBlockCodec.messagesRoot(messages),
                block.stateRoot(), messages, block.proposer(), FinalityCert.empty());
    }
}
