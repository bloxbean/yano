package com.bloxbean.cardano.yano.appchain.composite;

import java.util.List;
import java.util.Objects;

/** Consensus-relevant declaration of one atomic cross-component workflow route. */
public record WorkflowDescriptor(
        String workflowId,
        String semanticVersion,
        String topic,
        long fromHeight,
        long untilHeight,
        List<ComponentGeneration> participants,
        int maxEffectsPerBlock
) {
    public WorkflowDescriptor {
        workflowId = CompositeValidation.id(workflowId, "workflowId");
        semanticVersion = CompositeValidation.printable(semanticVersion, "semanticVersion");
        topic = CompositeValidation.route(topic, "workflow topic");
        CompositeValidation.activation(fromHeight, untilHeight, "workflow activation");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (participants.isEmpty() || participants.size() > 16) {
            throw new IllegalArgumentException("workflow participants must contain 1-16 generations");
        }
        if (participants.stream().distinct().count() != participants.size()) {
            throw new IllegalArgumentException("workflow participants must not contain duplicates");
        }
        if (maxEffectsPerBlock < 0 || maxEffectsPerBlock > 1_048_576) {
            throw new IllegalArgumentException("workflow maxEffectsPerBlock must be between 0 and 1048576");
        }
    }

    public boolean activeAt(long height) {
        return height >= fromHeight && (untilHeight == 0 || height < untilHeight);
    }
}
