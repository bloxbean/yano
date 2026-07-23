package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

/** Provider-neutral server-side encryption modes supported by the v1 profile. */
public enum EncryptionMode {
    /** No server-side encryption request (local-demo only). */
    NONE,
    /** Provider-managed S3-compatible server-side encryption. */
    SSE_S3,
    /** KMS-backed S3-compatible server-side encryption. */
    SSE_KMS
}
