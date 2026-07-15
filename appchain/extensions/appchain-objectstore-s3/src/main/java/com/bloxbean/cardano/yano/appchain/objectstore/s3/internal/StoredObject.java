package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded provider body with explicit single-owner transfer semantics.
 *
 * <p>The constructor takes ownership of {@code bytes} without cloning. The
 * executor calls {@link #takeBytes()} exactly once and wipes that array after
 * hashing/synchronous upload. This prevents several simultaneous 16 MiB
 * copies while keeping transfer ownership explicit inside the private adapter
 * boundary.</p>
 */
public final class StoredObject {
    private final StoredObjectMetadata metadata;
    private byte[] bytes;

    /**
     * Takes ownership of an exact provider response body.
     * @param metadata immutable response metadata
     * @param bytes exact response array whose ownership is transferred
     */
    public StoredObject(StoredObjectMetadata metadata, byte[] bytes) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        if (bytes == null || bytes.length != metadata.contentLength()) {
            throw new IllegalArgumentException("provider body length does not match metadata");
        }
        this.bytes = bytes;
    }

    /**
     * Returns immutable metadata associated with the response body.
     *
     * @return response metadata
     */
    public StoredObjectMetadata metadata() {
        return metadata;
    }

    /**
     * Transfers the sole body-array ownership to the caller.
     *
     * @return the exact bounded body; the caller must wipe it
     */
    public synchronized byte[] takeBytes() {
        if (bytes == null) {
            throw new IllegalStateException("object body ownership was already transferred");
        }
        byte[] transferred = bytes;
        bytes = null;
        return transferred;
    }

    /**
     * Returns a defensive body snapshot before ownership transfer.
     *
     * @return body copy
     */
    public synchronized byte[] bytes() {
        if (bytes == null) {
            throw new IllegalStateException("object body ownership was already transferred");
        }
        return bytes.clone();
    }

    /** Wipes an unclaimed body when a caller abandons the response. */
    public synchronized void wipe() {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
            bytes = null;
        }
    }
}
