package com.bloxbean.cardano.yano.api.appchain.state;

/** Maps one application-level claim to an exact authenticated key and value decoder. */
public interface StateProofSubject<T> {
    int SCHEMA_VERSION = 1;

    int schemaVersion();

    String subjectType();

    byte[] canonicalKey();

    T decodePresentValue(byte[] canonicalValue);
}
