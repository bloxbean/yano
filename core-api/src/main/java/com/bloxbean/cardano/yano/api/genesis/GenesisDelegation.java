package com.bloxbean.cardano.yano.api.genesis;

import java.util.Locale;

public record GenesisDelegation(int stakeCredentialType,
                                String stakeCredentialHash,
                                String poolHash) {
    public static final int KEY_HASH = 0;
    public static final int SCRIPT_HASH = 1;

    public GenesisDelegation {
        stakeCredentialHash = normalizeHex(stakeCredentialHash);
        poolHash = normalizeHex(poolHash);
    }

    public GenesisDelegation(String stakeCredentialHash, String poolHash) {
        this(KEY_HASH, stakeCredentialHash, poolHash);
    }

    private static String normalizeHex(String value) {
        if (value == null || value.isBlank()) return null;
        return value.toLowerCase(Locale.ROOT);
    }
}
