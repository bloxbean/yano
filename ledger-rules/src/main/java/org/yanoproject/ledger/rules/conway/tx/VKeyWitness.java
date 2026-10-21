package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * A vkey witness {@code [vkey, signature]} ({@code WitVKey}).
 *
 * <p>Ordered as Haskell's {@code Ord WitVKey} (Keys/WitVKey.hs:58-68): by key hash, then by the hash of the
 * signature; the witness set is a {@code Set}, so failures list witnesses in this order.</p>
 *
 * @param vkey      the 32-byte Ed25519 verification key
 * @param signature the 64-byte signature over the transaction id
 */
public record VKeyWitness(byte[] vkey, byte[] signature) implements Comparable<VKeyWitness> {

    public VKeyWitness {
        vkey = Objects.requireNonNull(vkey, "vkey").clone();
        signature = Objects.requireNonNull(signature, "signature").clone();
    }

    @Override
    public byte[] vkey() {
        return vkey.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /** @return {@code hashKey vkey}: blake2b-224 of the key */
    public byte[] keyHash() {
        return Hashes.blake2b224(vkey);
    }

    public String keyHashHex() {
        return HexUtil.encodeHexString(keyHash());
    }

    @Override
    public int compareTo(VKeyWitness other) {
        int byKey = Arrays.compareUnsigned(keyHash(), other.keyHash());
        return byKey != 0 ? byKey
                : Arrays.compareUnsigned(Hashes.blake2b256(signature), Hashes.blake2b256(other.signature));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VKeyWitness other && Arrays.equals(vkey, other.vkey)
                && Arrays.equals(signature, other.signature);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(vkey) + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "VKey " + HexUtil.encodeHexString(vkey);
    }
}
