package com.bloxbean.cardano.yano.api.utxo;

import java.math.BigInteger;
import java.util.Objects;

/** One ordered live UTXO balance row. */
public record StakeCredentialBalance(StakeCredentialId credential, BigInteger lovelace) {
    public StakeCredentialBalance {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() <= 0) {
            throw new IllegalArgumentException("lovelace must be positive");
        }
    }
}
