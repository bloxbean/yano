package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.codec.MessageCodec;

import java.util.Objects;

/**
 * Convenience base for state machines that work with a typed message body
 * instead of raw bytes (ADR app-layer/006 E1.3). Wraps a {@link MessageCodec};
 * the framework still only ever sees opaque {@code byte[]} — decoding happens
 * here, at the edge.
 * <p>
 * Subclasses override {@link #applyMessage} (and optionally
 * {@link #validateMessage}). Bodies that fail to decode are rejected at
 * admission and skipped in apply — subclasses see only well-formed payloads.
 *
 * @param <T> the application message type
 */
public abstract class TypedAppStateMachine<T> implements AppStateMachine {

    private final MessageCodec<T> codec;

    protected TypedAppStateMachine(MessageCodec<T> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    protected final MessageCodec<T> codec() {
        return codec;
    }

    @Override
    public final AdmissionResult validate(AppMessage message) {
        T payload;
        try {
            payload = codec.decode(message.getBody());
        } catch (Exception e) {
            return AdmissionResult.reject("Undecodable " + codec.type().getSimpleName()
                    + " body: " + e.getMessage());
        }
        return validateMessage(payload, message);
    }

    @Override
    public final void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            T payload;
            try {
                payload = codec.decode(message.getBody());
            } catch (Exception e) {
                continue; // filtered at admission; deterministic skip
            }
            applyMessage(payload, message, block, writer);
        }
    }

    /** Fast, side-effect-free admission of a decoded payload. */
    protected AdmissionResult validateMessage(T payload, AppMessage envelope) {
        return AdmissionResult.accept();
    }

    /**
     * Deterministic transition for one finalized message. {@code envelope}
     * exposes sender/topic/sequence; {@code block} exposes height/timestamp
     * (the consensus clock — use it instead of wall-clock).
     */
    protected abstract void applyMessage(T payload, AppMessage envelope, AppBlock block, AppStateWriter writer);
}
