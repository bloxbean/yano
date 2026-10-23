package org.yanoproject.api.appchain.topics;

/**
 * Refusal codes of the topic registry (ADR app-layer/039 D11). They are HTTP 400 codes at ingress;
 * {@link #receiptOnly()} codes are also recorded as receipts for commands already in a block.
 */
public enum TopicRefusal {
    TOPIC_UNREGISTERED,
    TOPIC_RETIRED,
    TOPIC_FRAME_INVALID,
    TOPIC_WRITER_DENIED,
    TOPIC_DELEGATION_INVALID,
    TOPIC_PROOF_EXPIRED,
    TOPIC_PROOF_REPLAYED,
    TOPIC_PAYLOAD_TOO_LARGE,
    TOPIC_SCHEMA_REJECTED,
    TOPIC_COMMAND_INVALID,
    TOPIC_COMMAND_DENIED,
    TOPIC_COMMAND_STALE,
    TOPIC_COMMAND_EXPIRED,
    TOPIC_COMMAND_LIMIT,
    TOPIC_COMMAND_SUPERSEDED;

    /** Outcomes that depend on other authorized commands and never invalidate a block (D5). */
    public boolean receiptOnly() {
        return this == TOPIC_COMMAND_STALE || this == TOPIC_COMMAND_LIMIT || this == TOPIC_COMMAND_SUPERSEDED;
    }
}
