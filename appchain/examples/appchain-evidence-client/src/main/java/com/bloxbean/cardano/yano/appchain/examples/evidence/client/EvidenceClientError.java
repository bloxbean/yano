package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

/** Stable fail-closed error categories exposed by the evidence companion. */
public enum EvidenceClientError {
    /** Caller input does not satisfy the frozen evidence contract. */
    INVALID_ARGUMENT,
    /** The node request failed or returned an unusable HTTP response. */
    TRANSPORT_FAILURE,
    /** The response names a chain other than the configured chain. */
    WRONG_CHAIN,
    /** The response was produced by a different state machine. */
    WRONG_STATE_MACHINE,
    /** The committed domain response is malformed or does not answer the request. */
    RESPONSE_MISMATCH,
    /** A state leaf that the committed query returned has no inclusion proof. */
    PROOF_MISSING,
    /** A proof has a wrong key/value or fails cryptographic verification. */
    PROOF_INVALID,
    /** The query/proof snapshot changed during every permitted attempt. */
    SNAPSHOT_RACE_EXHAUSTED
}
