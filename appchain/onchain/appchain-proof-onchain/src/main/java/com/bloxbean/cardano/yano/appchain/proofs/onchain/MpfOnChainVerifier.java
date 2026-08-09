package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** Bounded MPF inclusion verifier reusable by application validators. */
@OnchainLibrary
public final class MpfOnChainVerifier {
    public static final long PATH_NIBBLES = 64;
    public static final long MAX_KEY_BYTES = 256;
    public static final long MAX_VALUE_BYTES = 8 * 1024;
    public static final long MAX_FOLDS = 32;

    public record InclusionFold(
            BigInteger cursor,
            byte[] prefix,
            BigInteger nibble,
            byte[] neighbor1,
            byte[] neighbor2,
            byte[] neighbor3,
            byte[] neighbor4,
            byte[] branchValueHash
    ) {
    }

    public record Proof(
            byte[] key,
            byte[] value,
            byte[] leafSuffix,
            JulcList<InclusionFold> folds
    ) {
    }

    private MpfOnChainVerifier() {
    }

    /** Reconstruct the exact mpf-blake2b256-v1 root under hostile redeemer bounds. */
    public static boolean verifyInclusion(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long valueLength = Builtins.lengthOfByteString(proof.value());
        long encodedLength = Builtins.lengthOfByteString(proof.leafSuffix());
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || valueLength < 1 || valueLength > MAX_VALUE_BYTES
                || encodedLength < 1 || encodedLength > 33
                || proof.folds().size() > MAX_FOLDS) {
            return false;
        }
        byte[] pathHash = Builtins.blake2b_256(proof.key());
        long marker = Builtins.indexByteString(proof.leafSuffix(), 0);
        boolean odd = marker == 0;
        long suffixLength = odd
                ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        long cursorEnd = PATH_NIBBLES - suffixLength;
        if ((marker != 0 && marker != 255)
                || (odd && encodedLength < 2)
                || cursorEnd < 0
                || (odd && Builtins.indexByteString(
                proof.leafSuffix(), 1) != pathNibble(pathHash, cursorEnd))
                || !Builtins.equalsByteString(
                Builtins.sliceByteString(
                        odd ? 2 : 1,
                        encodedLength - (odd ? 2 : 1),
                        proof.leafSuffix()),
                Builtins.sliceByteString(
                        odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1),
                        pathHash))) {
            return false;
        }
        byte[] child = commitLeaf(
                proof.leafSuffix(), Builtins.blake2b_256(proof.value()));
        boolean valid = true;
        for (InclusionFold fold : proof.folds()) {
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(pathHash, cursor, fold.prefix())
                    || pathNibble(pathHash, cursor + prefixLength) != nibble
                    || !hashLength(fold.neighbor1())
                    || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3())
                    || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] merkle = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                    fold.neighbor3(), fold.neighbor4());
            if (Builtins.lengthOfByteString(fold.branchValueHash()) == 32) {
                merkle = hash(merkle, commitLeaf(
                        Builtins.consByteString(255, Builtins.emptyByteString()),
                        fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid && cursorEnd == 0
                && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean validPrefix(byte[] pathHash, long cursor, byte[] prefix) {
        long length = Builtins.lengthOfByteString(prefix);
        long index = 0;
        boolean valid = true;
        while (index < length) {
            long nibble = Builtins.indexByteString(prefix, index);
            if (nibble < 0 || nibble > 15
                    || nibble != pathNibble(pathHash, cursor + index)) {
                valid = false;
                break;
            }
            index += 1;
        }
        return valid;
    }

    private static long pathNibble(byte[] pathHash, long index) {
        long value = Builtins.indexByteString(pathHash, index / 2);
        return index % 2 == 0 ? value / 16 : value % 16;
    }

    private static byte[] commitLeaf(byte[] encodedSuffix, byte[] valueHash) {
        return hash(encodedSuffix, valueHash);
    }

    private static byte[] aggregate(long nibble, byte[] me, byte[] lvl1,
                                    byte[] lvl2, byte[] lvl3, byte[] lvl4) {
        if (nibble == 0) return hash(hash(hash(hash(me, lvl4), lvl3), lvl2), lvl1);
        if (nibble == 1) return hash(hash(hash(hash(lvl4, me), lvl3), lvl2), lvl1);
        if (nibble == 2) return hash(hash(hash(lvl3, hash(me, lvl4)), lvl2), lvl1);
        if (nibble == 3) return hash(hash(hash(lvl3, hash(lvl4, me)), lvl2), lvl1);
        if (nibble == 4) return hash(hash(lvl2, hash(hash(me, lvl4), lvl3)), lvl1);
        if (nibble == 5) return hash(hash(lvl2, hash(hash(lvl4, me), lvl3)), lvl1);
        if (nibble == 6) return hash(hash(lvl2, hash(lvl3, hash(me, lvl4))), lvl1);
        if (nibble == 7) return hash(hash(lvl2, hash(lvl3, hash(lvl4, me))), lvl1);
        if (nibble == 8) return hash(lvl1, hash(hash(hash(me, lvl4), lvl3), lvl2));
        if (nibble == 9) return hash(lvl1, hash(hash(hash(lvl4, me), lvl3), lvl2));
        if (nibble == 10) return hash(lvl1, hash(hash(lvl3, hash(me, lvl4)), lvl2));
        if (nibble == 11) return hash(lvl1, hash(hash(lvl3, hash(lvl4, me)), lvl2));
        if (nibble == 12) return hash(lvl1, hash(lvl2, hash(hash(me, lvl4), lvl3)));
        if (nibble == 13) return hash(lvl1, hash(lvl2, hash(hash(lvl4, me), lvl3)));
        if (nibble == 14) return hash(lvl1, hash(lvl2, hash(lvl3, hash(me, lvl4))));
        return hash(lvl1, hash(lvl2, hash(lvl3, hash(lvl4, me))));
    }

    private static boolean hashLength(byte[] value) {
        return Builtins.lengthOfByteString(value) == 32;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }
}
