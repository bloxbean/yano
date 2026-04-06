package com.bloxbean.cardano.yano.api.model;

import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;

/**
 * Reusable credential identifier: type (key hash or script hash) + hash.
 * Use as a map key instead of string concatenation ({@code credType + ":" + credHash}).
 */
public record CredentialKey(StakeCredType type, String hash) {

    /** Returns 0 for ADDR_KEYHASH, 1 for SCRIPTHASH. */
    public int typeInt() {
        return type == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    /** Create from raw int type (0=key, 1=script) and hex hash. */
    public static CredentialKey of(int credType, String hash) {
        return new CredentialKey(
                credType == 0 ? StakeCredType.ADDR_KEYHASH : StakeCredType.SCRIPTHASH, hash);
    }

    /**
     * Extract credential from a RocksDB key that has credType(1) + credHash(28) at the given offset.
     * Common layout: [...][credType(1)][credHash(28)] — used by reward_rest, account, MIR keys etc.
     */
    public static CredentialKey fromKeyBytes(byte[] key, int offset) {
        int credType = key[offset] & 0xFF;
        String credHash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                java.util.Arrays.copyOfRange(key, offset + 1, key.length));
        return of(credType, credHash);
    }
}
