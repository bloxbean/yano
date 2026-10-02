package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A voter, a key of the body's voting procedures: {@code [0, keyhash]} / {@code [1, scripthash]} committee hot
 * credential, {@code [2, keyhash]} / {@code [3, scripthash]} DRep, {@code [4, keyhash]} stake pool.
 *
 * <p>Ordered as Haskell's derived {@code Ord Voter} (Conway/Governance/Procedures.hs:338-342): committee voters,
 * then DReps, then stake pools; within a credential kind script hashes before key hashes
 * ({@code Ord Credential}), then by hash bytes. Voting redeemer indices follow this order.</p>
 *
 * @param tag  the CDDL tag 0–4
 * @param hash the 28-byte hash
 */
public record RawVoter(int tag, byte[] hash) implements Comparable<RawVoter> {

    public RawVoter {
        if (tag < 0 || tag > 4) {
            throw new TxDecodingException("unknown voter tag " + tag);
        }
        Objects.requireNonNull(hash, "hash");
        if (hash.length != RawCredential.HASH_LENGTH) {
            throw new TxDecodingException("a voter hash is 28 bytes");
        }
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }

    /** @return true for a committee or DRep script credential (tags 1, 3) */
    public boolean isScript() {
        return tag == 1 || tag == 3;
    }

    /** @return true for a stake pool voter */
    public boolean isStakePool() {
        return tag == 4;
    }

    /** Reads a voter. */
    static RawVoter read(CborSpan item) {
        List<CborSpan> fields = StrictCbor.array(item, 2, "a voter is a two-element array");
        long tag = StrictCbor.unsignedLong(fields.get(0));
        if (tag > 4) {
            throw new TxDecodingException("unknown voter tag " + tag);
        }
        return new RawVoter((int) tag, StrictCbor.definiteBytes(fields.get(1)));
    }

    private int kind() {
        return tag / 2;
    }

    @Override
    public int compareTo(RawVoter other) {
        if (kind() != other.kind()) {
            return Integer.compare(kind(), other.kind());
        }
        if (isScript() != other.isScript()) {
            return isScript() ? -1 : 1;
        }
        return Arrays.compareUnsigned(hash, other.hash);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RawVoter other && tag == other.tag && Arrays.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        return 31 * tag + Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        String kind = switch (tag) {
            case 0, 1 -> "CommitteeVoter";
            case 2, 3 -> "DRepVoter";
            default -> "StakePoolVoter";
        };
        return kind + " " + (tag == 4 ? "" : isScript() ? "ScriptHashObj " : "KeyHashObj ")
                + HexUtil.encodeHexString(hash);
    }
}
