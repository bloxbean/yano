package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;

import java.util.Objects;

/** Immutable consensus facts for one command selected from an app block. */
public record TransitionContext(
        long height,
        long timestamp,
        int originalMessageIndex,
        byte[] messageId,
        String topic,
        byte[] sender
) {
    public TransitionContext {
        if (height < 0 || originalMessageIndex < 0) {
            throw new IllegalArgumentException("height and originalMessageIndex must be non-negative");
        }
        messageId = Objects.requireNonNull(messageId, "messageId").clone();
        sender = Objects.requireNonNull(sender, "sender").clone();
        topic = topic != null ? topic : "";
    }

    public static TransitionContext of(AppBlock block, int originalMessageIndex, AppMessage message) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(message, "message");
        return new TransitionContext(block.height(), block.timestamp(), originalMessageIndex,
                message.getMessageId(), message.getTopic(), message.getSender());
    }

    @Override public byte[] messageId() { return messageId.clone(); }
    @Override public byte[] sender() { return sender.clone(); }
}
