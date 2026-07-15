package com.bloxbean.cardano.yano.appchain.integration.ipfs;

import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

/** Shared allowlist for every CID persisted or executed by the v1 IPFS connector contract. */
public final class IpfsV1Policy {
    /** Multicodec value for raw binary content. */
    public static final long RAW_CODEC = 0x55;
    /** Canonical byte length of an allowed v1 CID. */
    public static final int CANONICAL_BINARY_LENGTH = 36;

    private IpfsV1Policy() {
    }

    /**
     * Enforces the frozen raw-or-dag-pb/SHA2-256 v1 CID allowlist.
     *
     * @param cid the CID to validate
     * @return the same CID when allowed
     */
    public static CanonicalCid requireAllowed(CanonicalCid cid) {
        if (cid == null
                || (cid.codec() != RAW_CODEC && cid.codec() != CanonicalCid.DAG_PB_CODEC)
                || cid.multihashCode() != CanonicalCid.SHA2_256_MULTIHASH
                || cid.digestLength() != CanonicalCid.SHA2_256_DIGEST_LENGTH
                || cid.bytes().length != CANONICAL_BINARY_LENGTH) {
            throw CanonicalCbor.malformed();
        }
        return cid;
    }
}
