package com.bloxbean.cardano.yano.appchain.integration;

/** Hard, non-operator-tunable limits of the ADR-013 v1 wire contracts. */
public final class ConnectorLimits {
    /** Wire schema version implemented by this module. */
    public static final int SCHEMA_VERSION = 1;
    /** Maximum UTF-8 byte length of a connector alias. */
    public static final int MAX_ALIAS_BYTES = 63;
    /** Maximum UTF-8 byte length of a media type. */
    public static final int MAX_CONTENT_TYPE_BYTES = 127;
    /** Maximum UTF-8 byte length of an object-store key. */
    public static final int MAX_OBJECT_KEY_BYTES = 512;
    /** Maximum encoded byte length of an authenticated external reference. */
    public static final int MAX_EXTERNAL_REF_BYTES = 128;
    /** Byte length of all v1 BLAKE2b-256 and SHA-256 commitments. */
    public static final int HASH_BYTES = 32;
    /** Maximum encoded byte length of one archived detail document. */
    public static final int MAX_DETAIL_DOCUMENT_BYTES = 8_192;
    /** Maximum ASCII byte length of a normalized public failure code. */
    public static final int MAX_FAILURE_CODE_BYTES = 64;

    private ConnectorLimits() {
    }
}
