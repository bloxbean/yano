package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;

import java.util.Objects;

/**
 * One structurally valid L1 observation at its original position in the
 * globally sequenced app block.
 */
public record SequencedL1Observation(
        int originalMessageIndex,
        byte[] messageId,
        L1Observation observation
) {
    public SequencedL1Observation {
        if (originalMessageIndex < 0) {
            throw new IllegalArgumentException("originalMessageIndex must be non-negative");
        }
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.length != 32) {
            throw new IllegalArgumentException("messageId must be 32 bytes");
        }
        messageId = messageId.clone();
        observation = Objects.requireNonNull(observation, "observation");
    }

    @Override
    public byte[] messageId() {
        return messageId.clone();
    }
}
