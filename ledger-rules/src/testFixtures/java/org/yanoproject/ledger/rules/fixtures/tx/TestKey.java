package org.yanoproject.ledger.rules.fixtures.tx;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;

/**
 * Deterministic Ed25519 test keys: the seeds of Amaru's corpus test credentials
 * ({@code common/test-credentials}: {@code dev-42} is {@code 0x42} × 32, {@code dev-aa} is {@code 0xAA} × 32), and two
 * more keys that are not in the corpus ({@code 0x77} × 32, {@code 0xBB} × 32) for the mutation world's certificate
 * state (a registered stake account, pool, DRep and committee members), and {@code 0xCC} × 32 for an account without
 * delegations. Public test material; never hold funds with
 * them.
 */
public enum TestKey {
    DEV_42((byte) 0x42),
    DEV_AA((byte) 0xAA),
    DEV_77((byte) 0x77),
    DEV_BB((byte) 0xBB),
    /** A registered stake account without any delegation (the mutation world's ConwayWdrlNotDelegatedToDRep). */
    DEV_CC((byte) 0xCC);

    private final SecretKey secretKey;
    private final byte[] verificationKey;
    private final String keyHash;

    TestKey(byte seedByte) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, seedByte);
        try {
            this.secretKey = SecretKey.create(seed);
            VerificationKey vkey = KeyGenUtil.getPublicKeyFromPrivateKey(secretKey);
            this.verificationKey = vkey.getBytes();
            this.keyHash = KeyGenUtil.getKeyHash(vkey);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public SecretKey secretKey() {
        return secretKey;
    }

    public byte[] verificationKey() {
        return verificationKey.clone();
    }

    /** @return the blake2b-224 key hash, hex */
    public String keyHash() {
        return keyHash;
    }

    /** @return the enterprise (payment-only) address of this key on {@code network} */
    public String enterpriseAddress(Network network) {
        return AddressProvider.getEntAddress(Credential.fromKey(HexUtil.decodeHexString(keyHash)), network).toBech32();
    }
}
