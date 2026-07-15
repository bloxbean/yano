package com.bloxbean.cardano.yano.appchain.integration.internal;

import org.bouncycastle.crypto.digests.Blake2bDigest;

/**
 * Minimal unkeyed BLAKE2b-256 adapter for the connector wire contract.
 *
 * @hidden internal implementation detail; not part of the connector SDK API
 */
public final class Blake2b256 {
    private Blake2b256() {
    }

    public static byte[] hash(byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        Blake2bDigest digest = new Blake2bDigest(256);
        digest.update(input, 0, input.length);
        byte[] output = new byte[32];
        digest.doFinal(output, 0);
        return output;
    }
}
