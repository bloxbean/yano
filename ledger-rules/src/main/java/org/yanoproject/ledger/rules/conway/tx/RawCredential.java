package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * A credential as Haskell's {@code Credential} ({@code Cardano.Ledger.Credential}): a script hash or a key hash,
 * 28 bytes.
 *
 * <p>Ordered as Haskell's derived {@code Ord}: {@code ScriptHashObj} before {@code KeyHashObj}, then by hash bytes.
 * Maps keyed by credentials (withdrawals, voters) are enumerated in this order, which fixes redeemer indices.</p>
 *
 * @param script true for {@code ScriptHashObj}
 * @param hash   the 28-byte hash
 */
public record RawCredential(boolean script, byte[] hash) implements Comparable<RawCredential> {

    public static final int HASH_LENGTH = 28;

    public RawCredential {
        Objects.requireNonNull(hash, "hash");
        if (hash.length != HASH_LENGTH) {
            throw new TxDecodingException("a credential hash is 28 bytes, found " + hash.length);
        }
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    public String hashHex() {
        return HexUtil.encodeHexString(hash);
    }

    /** Reads {@code credential = [0, addr_keyhash] / [1, script_hash]}. */
    static RawCredential read(CborReader reader) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a credential is a two-element array");
        }
        long kind = reader.readUnsignedLong();
        if (kind > 1) {
            throw new TxDecodingException("unknown credential kind " + kind);
        }
        byte[] hash = reader.readDefiniteBytes();
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("a credential is a two-element array");
        }
        return new RawCredential(kind == 1, hash);
    }

    @Override
    public int compareTo(RawCredential other) {
        if (script != other.script) {
            return script ? -1 : 1;
        }
        return Arrays.compareUnsigned(hash, other.hash);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawCredential other && script == other.script && Arrays.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(script) + Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        return (script ? "ScriptHashObj " : "KeyHashObj ") + hashHex();
    }
}
