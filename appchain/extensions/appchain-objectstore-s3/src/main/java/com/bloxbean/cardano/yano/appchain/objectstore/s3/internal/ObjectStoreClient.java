package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import java.util.Optional;

/**
 * Minimal provider-neutral S3-compatible boundary used by the executor.
 * Implementations must bound every listing and body read before allocation.
 */
public interface ObjectStoreClient extends AutoCloseable {
    /**
     * Returns the destination bucket's versioning state.
     * @param bucket allowlisted destination bucket
     * @return observed versioning state
     */
    BucketVersioning bucketVersioning(String bucket);

    /**
     * Lists only the exact object key, within the supplied entry bound.
     * @param bucket allowlisted destination bucket
     * @param exactKey exact composed key
     * @param maxEntries hard response-entry bound
     * @return bounded prior-version observation
     */
    VersionInventory listVersions(String bucket, String exactKey, int maxEntries);

    /**
     * Returns current or exact-version metadata, or empty only for not-found.
     * @param bucket allowlisted bucket
     * @param key exact composed key
     * @param versionId exact version, or {@code null} for current
     * @return bounded metadata, or empty only when absent
     */
    Optional<StoredObjectMetadata> head(String bucket, String key, String versionId);

    /**
     * Reads one current or exact version with a hard pre-allocation/stream bound.
     * @param bucket allowlisted bucket
     * @param key exact composed key
     * @param versionId exact version, or {@code null} for current
     * @param maxBytes hard response-body bound
     * @return one ownership-transferring bounded provider body
     */
    StoredObject get(String bucket, String key, String versionId, long maxBytes);

    /**
     * Atomically creates the destination only when no current object exists.
     * The call is synchronous: the caller retains ownership of {@code bytes},
     * must not mutate it during the call, and wipes it after this method returns.
     * @param request immutable conditional-create request
     * @param bytes caller-owned body bytes
     * @return mandatory provider version acknowledgement
     */
    PutAcknowledgement putIfAbsent(PutRequest request, byte[] bytes);

    /** Releases all adapter resources. */
    @Override
    void close();
}
