package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * A bootstrap (Byron) witness {@code [vkey, signature, chain_code, attributes]} (Keys/Bootstrap.hs).
 *
 * <p>Ordered as Haskell's {@code Ord BootstrapWitness}: by {@link #keyHash()} (Keys/Bootstrap.hs:108-109).</p>
 *
 * @param vkey       the 32-byte Ed25519 verification key
 * @param signature  the 64-byte signature over the transaction id
 * @param chainCode  the chain code bytes (any length before protocol version 12, Keys/Bootstrap.hs:72-78)
 * @param attributes the Byron address attributes bytes
 */
public record BootstrapWitness(byte[] vkey, byte[] signature, byte[] chainCode, byte[] attributes)
        implements Comparable<BootstrapWitness> {

    /** {@code "\131\00\130\00\88\64"}: list(3), 0, list(2), 0, bytes(64) (Keys/Bootstrap.hs:129-130). */
    private static final byte[] PREFIX = {(byte) 0x83, 0x00, (byte) 0x82, 0x00, 0x58, 0x40};

    public BootstrapWitness {
        vkey = Objects.requireNonNull(vkey, "vkey").clone();
        signature = Objects.requireNonNull(signature, "signature").clone();
        chainCode = Objects.requireNonNull(chainCode, "chainCode").clone();
        attributes = Objects.requireNonNull(attributes, "attributes").clone();
    }

    @Override
    public byte[] vkey() {
        return vkey.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    @Override
    public byte[] chainCode() {
        return chainCode.clone();
    }

    @Override
    public byte[] attributes() {
        return attributes.clone();
    }

    /**
     * {@code bootstrapWitKeyHash} (Keys/Bootstrap.hs:112-150): the address root the witness stands for,
     * blake2b-224 of SHA3-256 of the constant prefix, the key, the chain code and the attributes, concatenated
     * as stored.
     */
    public byte[] keyHash() {
        byte[] payload = new byte[PREFIX.length + vkey.length + chainCode.length + attributes.length];
        int offset = 0;
        for (byte[] part : new byte[][]{PREFIX, vkey, chainCode, attributes}) {
            System.arraycopy(part, 0, payload, offset, part.length);
            offset += part.length;
        }
        return Hashes.blake2b224(Hashes.sha3256(payload));
    }

    public String keyHashHex() {
        return HexUtil.encodeHexString(keyHash());
    }

    @Override
    public int compareTo(BootstrapWitness other) {
        return Arrays.compareUnsigned(keyHash(), other.keyHash());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BootstrapWitness other && Arrays.equals(vkey, other.vkey)
                && Arrays.equals(signature, other.signature) && Arrays.equals(chainCode, other.chainCode)
                && Arrays.equals(attributes, other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(vkey), Arrays.hashCode(signature), Arrays.hashCode(chainCode),
                Arrays.hashCode(attributes));
    }

    @Override
    public String toString() {
        return "VKey " + HexUtil.encodeHexString(vkey);
    }
}
