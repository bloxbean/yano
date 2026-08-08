package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Atomic order binding, approval check, audit append, and outbox emission. */
final class ShowcaseReleaseWorkflow implements CompositeWorkflow {
    static final String ID = "showcase-order-release";
    static final String TOPIC = "showcase.release.v1";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration orders;
    private final ComponentGeneration approvals;
    private final ComponentGeneration audit;
    private final ComponentGeneration release;
    private final DocTrailStateMachine auditMachine;
    private final ShowcaseReleaseStateMachine releaseMachine;

    ShowcaseReleaseWorkflow(WorkflowDescriptor descriptor, ComponentGeneration orders,
                            ComponentGeneration approvals, ComponentGeneration audit,
                            ComponentGeneration release, DocTrailStateMachine auditMachine,
                            ShowcaseReleaseStateMachine releaseMachine) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.orders = orders;
        this.approvals = approvals;
        this.audit = audit;
        this.release = release;
        this.auditMachine = auditMachine;
        this.releaseMachine = releaseMachine;
        if (!descriptor.workflowId().equals(ID) || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(List.of(orders, approvals, audit, release))) {
            throw new IllegalArgumentException("showcase release descriptor mismatch");
        }
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            ShowcaseReleaseCommand.decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_SHOWCASE_RELEASE_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        for (AppMessage source : execution.messages()) {
            ShowcaseReleaseCommand command;
            try {
                command = ShowcaseReleaseCommand.decode(source.getBody());
            } catch (RuntimeException malformed) {
                continue;
            }
            AppStateWriter orderState = context.state(orders);
            AppStateWriter approvalState = context.state(approvals);
            AppStateWriter auditState = context.state(audit);
            AppStateWriter releaseState = context.state(release);
            byte[] orderEntry = orderState.get(command.orderKey()).orElse(null);
            byte[] approvalEntry = approvalState.get(
                    ApprovalsStateMachine.itemKey(command.approvalId())).orElse(null);
            if (orderEntry == null || approvalEntry == null) {
                continue;
            }
            byte[] order = KvRegistryStateMachine.decodeValue(orderEntry);
            byte[] orderHash = Blake2bUtil.blake2bHash256(order);
            ApprovalsStateMachine.Item item = ApprovalsStateMachine.decodeItem(approvalEntry);
            if (item.status() != ApprovalsStateMachine.STATUS_APPROVED
                    || !MessageDigest.isEqual(item.payloadHash(), orderHash)) {
                continue;
            }
            AppMessage auditMessage = routed(source, ShowcaseCompositePreset.AUDIT_TOPIC,
                    DocTrailStateMachine.append(command.releaseId(), orderHash,
                            "showcase-order:" + new String(command.orderKey(), StandardCharsets.UTF_8)));
            if (!auditMachine.validate(auditMessage).isAccepted()) {
                continue;
            }
            if (context.claim(command.releaseId(), command.commandHash())
                    != CompositeWorkflowContext.ClaimResult.CLAIMED) {
                continue;
            }
            auditMachine.apply(
                    com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext
                            .fromValidatedBlock(withMessages(block, List.of(auditMessage))),
                    auditState,
                    com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter
                            .rejecting("document trail does not emit effects"));
            EffectId effectId = context.effects(release).emit(
                    EffectIntent.of(ShowcaseOutboxExecutor.TYPE, order)
                            .scope(ShowcaseReleaseStateMachine.SCOPE_PREFIX + command.releaseId())
                            .gate(FinalityGate.APP_FINAL)
                            .result(ResultPolicy.CHAIN)
                            .expiryBlocks(100)
                            .sourceMessageId(source.getMessageId())
                            .build());
            releaseState.put(ShowcaseReleaseStateMachine.key(command.releaseId()),
                    ShowcaseReleaseStateMachine.pending(
                            effectId, orderHash, command.approvalId()).encode());
        }
    }

    private static AppMessage routed(AppMessage source, String topic, byte[] body) {
        return AppMessage.builder()
                .version(source.getVersion()).messageId(source.getMessageId())
                .chainId(source.getChainId()).topic(topic).sender(source.getSender())
                .senderSeq(source.getSenderSeq()).expiresAt(source.getExpiresAt())
                .body(body).authScheme(source.getAuthScheme()).authProof(source.getAuthProof())
                .build();
    }

    private static AppBlock withMessages(AppBlock block, List<AppMessage> messages) {
        return new AppBlock(block.version(), block.chainId(), block.height(), block.prevHash(),
                block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                AppBlockCodec.messagesRoot(messages), block.stateRoot(), messages,
                block.proposer(), FinalityCert.empty());
    }
}
