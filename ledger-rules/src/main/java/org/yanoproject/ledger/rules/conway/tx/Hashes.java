package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The ledger's hash functions over original bytes. */
public final class Hashes {

    private Hashes() {
    }

    /** blake2b-224: key hashes ({@code hashKey}) and bootstrap address roots. */
    public static byte[] blake2b224(byte[] data) {
        return Blake2bUtil.blake2bHash224(data);
    }

    /** blake2b-256: transaction ids, datum hashes, auxiliary-data hashes, script integrity hashes. */
    public static byte[] blake2b256(byte[] data) {
        return Blake2bUtil.blake2bHash256(data);
    }

    /** SHA3-256, for bootstrap witness key hashes ({@code bootstrapWitKeyHash}). */
    public static byte[] sha3256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is not available", e);
        }
    }
}
