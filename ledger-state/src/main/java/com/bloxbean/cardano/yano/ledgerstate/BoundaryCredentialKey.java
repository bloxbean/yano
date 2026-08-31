package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.Arrays;

/**
 * Fixed-width credential identity used by bounded epoch-boundary working sets.
 * Persistence keys remain unchanged; this class only avoids repeatedly converting
 * {@code type:hexHash} strings back into bytes while a pool is processed.
 */
final class BoundaryCredentialKey {
    static final int HASH_LENGTH = 28;
    static final int SUFFIX_LENGTH = 1 + HASH_LENGTH;

    private final byte[] suffix;
    private final int hashCode;

    private BoundaryCredentialKey(byte[] suffix) {
        if (suffix.length != SUFFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "Credential suffix must be " + SUFFIX_LENGTH + " bytes");
        }
        this.suffix = suffix;
        this.hashCode = Arrays.hashCode(suffix);
    }

    static BoundaryCredentialKey of(int credentialType, String credentialHash) {
        byte[] hash = HexUtil.decodeHexString(credentialHash);
        if (hash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "Credential hash must be " + HASH_LENGTH + " bytes");
        }
        byte[] suffix = new byte[SUFFIX_LENGTH];
        suffix[0] = (byte) credentialType;
        System.arraycopy(hash, 0, suffix, 1, HASH_LENGTH);
        return new BoundaryCredentialKey(suffix);
    }

    static BoundaryCredentialKey fromAddress(String address) {
        int separator = address.indexOf(':');
        if (separator < 0) {
            return of(0, address);
        }
        int credentialType = Integer.parseInt(address.substring(0, separator));
        return of(credentialType, address.substring(separator + 1));
    }

    static BoundaryCredentialKey fromKey(byte[] key, int suffixOffset) {
        if (suffixOffset < 0 || key.length < suffixOffset + SUFFIX_LENGTH) {
            throw new IllegalArgumentException("Credential suffix is outside the source key");
        }
        return new BoundaryCredentialKey(
                Arrays.copyOfRange(key, suffixOffset, suffixOffset + SUFFIX_LENGTH));
    }

    int credentialType() {
        return suffix[0] & 0xFF;
    }

    String address() {
        return credentialType() + ":" + credentialHash();
    }

    String credentialHash() {
        return HexUtil.encodeHexString(Arrays.copyOfRange(suffix, 1, SUFFIX_LENGTH));
    }

    byte[] storageKey(byte prefix) {
        byte[] key = new byte[1 + SUFFIX_LENGTH];
        key[0] = prefix;
        copySuffixTo(key, 1);
        return key;
    }

    void copySuffixTo(byte[] target, int offset) {
        System.arraycopy(suffix, 0, target, offset, SUFFIX_LENGTH);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BoundaryCredentialKey that)) return false;
        return Arrays.equals(suffix, that.suffix);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return address();
    }
}
