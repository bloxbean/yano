package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

/** Provider-neutral destination-bucket versioning state. */
public enum BucketVersioning {
    /** The provider reports versioning as enabled. */
    ENABLED,
    /** The provider reports versioning as disabled or suspended. */
    DISABLED,
    /** The provider could not establish the versioning state. */
    UNKNOWN
}
