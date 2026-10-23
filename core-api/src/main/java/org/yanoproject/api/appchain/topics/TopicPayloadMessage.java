package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;

import java.util.Objects;

/**
 * The payload view of a message on a registered topic (ADR app-layer/039 D7a): the original envelope's
 * {@code messageId}, chain, topic, sender and sequence, with {@link #getBody()} returning the verified
 * payload instead of the frame. Machines use the provided {@code messageId} and never recompute it from
 * this body; the finalized block keeps the framed bytes. {@link AppMessage#equals} compares the class and
 * the {@code messageId}, so a view never equals its original envelope.
 */
public final class TopicPayloadMessage extends AppMessage {
    private final TopicMessageView view;

    private TopicPayloadMessage(AppMessage original, byte[] payload, TopicMessageView view) {
        super(original.getVersion(), original.getMessageId().clone(), original.getChainId(), original.getTopic(),
                original.getSender().clone(), original.getSenderSeq(), original.getExpiresAt(), payload.clone(),
                original.getAuthScheme(), original.getAuthProof().clone());
        this.view = Objects.requireNonNull(view, "view");
    }

    /**
     * The message a machine hook receives after an allowed write check: a payload view on a topic a
     * registry policy governs, otherwise the original message unchanged.
     */
    public static AppMessage of(AppMessage original, TopicWriteRules.Result result) {
        if (!result.allowed()) {
            throw new IllegalArgumentException("only an allowed write has a payload view");
        }
        if (result.outcome() == null || result.outcome().kind() != OutcomeKind.POLICY) {
            return original;
        }
        return new TopicPayloadMessage(original, result.payload(),
                new TopicMessageView(result.writer(), result.outcome()));
    }

    /** A defensive copy that keeps the view (execution contexts snapshot every message they hand out). */
    public TopicPayloadMessage copy() {
        return new TopicPayloadMessage(this, getBody(), view);
    }

    TopicMessageView view() {
        return view;
    }
}
