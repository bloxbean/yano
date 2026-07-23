package com.bloxbean.cardano.yano.appchain.examples.evidence.command;

/** One canonical command accepted on the {@code evidence.command.v1} topic. */
public sealed interface EvidenceCommandV1
        permits SubmitEvidenceCommandV1, NotifyEvidenceCommandV1,
        RepublishEvidenceCommandV1 {

    /** Returns the stable command operation. */
    EvidenceCommandOperation operation();

    /** Returns the bounded business identifier. */
    String evidenceId();

    /** Returns the positive immutable business version. */
    long businessVersion();

    /** Returns the strict canonical CBOR command. */
    byte[] encode();
}
