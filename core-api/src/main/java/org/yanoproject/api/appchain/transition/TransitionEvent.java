package org.yanoproject.api.appchain.transition;

import java.util.Objects;

/**
 * Immutable, ephemeral output of an approved transition, consumed by composition before commit.
 * The host plan adapter does not persist or independently hash these events. A binding workflow may derive
 * further plans from them or include event identifiers in its authenticated receipt. Construction verifies
 * canonical scalar-map encoding; semantic field validation belongs to the kernel's event descriptor.
 *
 * @param eventId versioned logical event identifier, independent of Java implementation class names
 * @param payload canonical scalar map, defensively copied on construction and access
 */
public record TransitionEvent(String eventId, byte[] payload) {
    public static final int MAX_PAYLOAD_BYTES = 65_536;

    public TransitionEvent {
        Objects.requireNonNull(eventId, "eventId");
        if (!eventId.matches("[a-z][a-z0-9.-]{0,126}")) {
            throw new IllegalArgumentException("invalid transition event id");
        }
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("transition event payload exceeds limit");
        }
        payload = payload.clone();
        TransitionScalars.decode(payload);
    }

    @Override public byte[] payload() { return payload.clone(); }
}
