package com.bloxbean.cardano.yano.appchain.eutxo.testkit;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;

import java.util.Objects;

/** Deterministic test wallet; never use its seed material for real funds. */
public record EutxoTestWallet(SecretKey signingKey, VerificationKey verificationKey, String address) {

    public EutxoTestWallet {
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(verificationKey, "verificationKey");
        Objects.requireNonNull(address, "address");
    }

    public static EutxoTestWallet fromSeed(byte[] seed) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != 32) {
            throw new IllegalArgumentException("test wallet seed must contain 32 bytes");
        }
        try {
            SecretKey signingKey = SecretKey.create(seed);
            VerificationKey verificationKey = KeyGenUtil.getPublicKeyFromPrivateKey(signingKey);
            String address = AddressProvider.getEntAddress(
                    Credential.fromKey(
                            com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(
                                    verificationKey.getBytes())),
                    Networks.testnet()).toBech32();
            return new EutxoTestWallet(signingKey, verificationKey, address);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot create deterministic EUTxO test wallet", failure);
        }
    }
}
