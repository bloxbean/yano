package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;

import java.util.Objects;

/** Minimal key-controlled testnet wallet for Cardano-shaped L2 payments. */
public record EutxoKeyWallet(
        SecretKey signingKey,
        VerificationKey verificationKey,
        String address
) {
    public EutxoKeyWallet {
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(verificationKey, "verificationKey");
        Objects.requireNonNull(address, "address");
    }

    public static EutxoKeyWallet fromSeed(byte[] seed) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != 32) {
            throw new IllegalArgumentException("wallet seed must contain 32 bytes");
        }
        try {
            SecretKey signingKey = SecretKey.create(seed.clone());
            VerificationKey verificationKey =
                    KeyGenUtil.getPublicKeyFromPrivateKey(signingKey);
            String address = AddressProvider.getEntAddress(
                    Credential.fromKey(Blake2bUtil.blake2bHash224(
                            verificationKey.getBytes())),
                    Networks.testnet()).toBech32();
            return new EutxoKeyWallet(signingKey, verificationKey, address);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot create EUTxO key wallet", failure);
        }
    }
}
