package com.bloxbean.cardano.yano.appchain.examples.evidence;

/** Stable names and hard bounds for the evidence-registry v1 domain contract. */
public final class EvidenceContract {
    /** Stable state-machine identity used by configuration, queries, and proofs. */
    public static final String STATE_MACHINE_ID = "evidence-registry";
    /** App-message topic accepted by the evidence state machine. */
    public static final String COMMAND_TOPIC = "evidence.command.v1";
    /** Committed query path for a record lookup. */
    public static final String GET_QUERY_PATH = "evidence/get";
    /** Kafka media type used for {@code evidence.available} v1 events. */
    public static final String EVENT_CONTENT_TYPE = "application/cbor";
    /** Wire schema version. This is independent of an evidence business version. */
    public static final int SCHEMA_VERSION = 1;
    /** Maximum canonical outer command size. */
    public static final int MAX_COMMAND_BYTES = 4_096;
    /** Maximum canonical immutable record size. */
    public static final int MAX_RECORD_BYTES = 8_192;
    /** Maximum canonical query response size. */
    public static final int MAX_QUERY_RESPONSE_BYTES = 16_384;
    /** Maximum evidence identifier length; identifiers are printable ASCII. */
    public static final int MAX_EVIDENCE_ID_BYTES = 63;
    /** Size of public keys, message ids, and BLAKE2b-256 commitments. */
    public static final int HASH_BYTES = 32;
    /**
     * Frozen v1 ordinal ceiling for an effect reference.
     *
     * <p>This matches the Effect Runtime bound used when v1 was defined but
     * remains an evidence-contract invariant if framework constants evolve.</p>
     */
    public static final int MAX_EFFECTS_PER_BLOCK = 1_048_576;

    private EvidenceContract() {
    }
}
