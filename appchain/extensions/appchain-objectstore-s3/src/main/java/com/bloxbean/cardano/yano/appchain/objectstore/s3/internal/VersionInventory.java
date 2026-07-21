package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

/**
 * Bounded exact-key version-history observation.
 *
 * @param entriesExamined number of exact-key entries inspected
 * @param anyVersionOrDeleteMarker whether any prior version or marker exists
 */
public record VersionInventory(int entriesExamined, boolean anyVersionOrDeleteMarker) {
    /** Validates the bounded count returned by the adapter. */
    public VersionInventory {
        if (entriesExamined < 0 || entriesExamined > 64) {
            throw new IllegalArgumentException("version inventory is not bounded");
        }
    }
}
