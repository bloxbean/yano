package com.bloxbean.cardano.yano.api.utxo;

import java.util.Arrays;

/** Value-semantic stake credential identifier used by ordered stake-balance APIs. */
public final class StakeCredentialId {
    public static final int HASH_LENGTH = 28;

    private final int credentialType;
    private final byte[] credentialHash;

    public StakeCredentialId(int credentialType, byte[] credentialHash) {
        if (credentialType != 0 && credentialType != 1) {
            throw new IllegalArgumentException("credentialType must be 0 (key) or 1 (script)");
        }
        if (credentialHash == null || credentialHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException("credentialHash must be exactly 28 bytes");
        }
        this.credentialType = credentialType;
        this.credentialHash = credentialHash.clone();
    }

    public int credentialType() {
        return credentialType;
    }

    public byte[] credentialHash() {
        return credentialHash.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof StakeCredentialId other
                && credentialType == other.credentialType
                && Arrays.equals(credentialHash, other.credentialHash);
    }

    @Override
    public int hashCode() {
        return 31 * credentialType + Arrays.hashCode(credentialHash);
    }
}
