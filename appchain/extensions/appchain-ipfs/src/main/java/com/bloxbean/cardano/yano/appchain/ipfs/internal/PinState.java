package com.bloxbean.cardano.yano.appchain.ipfs.internal;

/**
 * Provider-neutral observation of one CID's local pin state.
 */
public enum PinState {
    /** The CID is not pinned by the provider. */
    ABSENT,
    /** Only the CID's root block is explicitly pinned. */
    DIRECT,
    /** The CID and its reachable DAG are explicitly pinned. */
    RECURSIVE,
    /** The CID is retained only through an ancestor's recursive pin. */
    INDIRECT;

    /**
     * Returns whether this observation satisfies the requested explicit pin.
     *
     * <p>An indirect pin is deliberately insufficient: removing its ancestor
     * would remove the retention guarantee for this CID. A recursive pin is a
     * stronger explicit pin and therefore also satisfies a direct request.</p>
     *
     * @param recursive whether the requested pin must retain the reachable DAG
     * @return {@code true} only when the requested explicit pin is present
     */
    public boolean satisfies(boolean recursive) {
        return recursive ? this == RECURSIVE : this == DIRECT || this == RECURSIVE;
    }
}
