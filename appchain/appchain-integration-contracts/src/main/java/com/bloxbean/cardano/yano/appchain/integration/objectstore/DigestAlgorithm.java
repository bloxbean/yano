package com.bloxbean.cardano.yano.appchain.integration.objectstore;

/** Content digest algorithms frozen by the object.put v1 contract. */
public enum DigestAlgorithm {
    /** SHA-256, encoded as wire code {@code 1}. */
    SHA_256(1, 32);

    private final int code;
    private final int digestBytes;

    DigestAlgorithm(int code, int digestBytes) {
        this.code = code;
        this.digestBytes = digestBytes;
    }

    /**
     * Returns the compact command wire code.
     *
     * @return the positive algorithm code
     */
    public int code() {
        return code;
    }

    /**
     * Returns the required digest length.
     *
     * @return the digest length in bytes
     */
    public int digestBytes() {
        return digestBytes;
    }

    /**
     * Resolves a command wire code.
     *
     * @param code the unsigned algorithm code
     * @return the matching digest algorithm
     */
    public static DigestAlgorithm fromCode(long code) {
        if (code == SHA_256.code) {
            return SHA_256;
        }
        throw com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor.malformed();
    }
}
