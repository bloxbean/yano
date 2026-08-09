package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

/** Compiled same-root pair verifier for facts that require an authenticated completeness leaf. */
@SpendingValidator
public final class MpfPairOnChainVerifier {
    private static final long PATH_NIBBLES = 64;
    private static final long MAX_KEY_BYTES = 256;
    private static final long MAX_VALUE_BYTES = 8 * 1024;
    private static final long MAX_FOLDS = 32;

    public record Fold(BigInteger cursor, byte[] prefix, BigInteger nibble,
                       byte[] neighbor1, byte[] neighbor2, byte[] neighbor3,
                       byte[] neighbor4, byte[] branchValueHash) { }

    public record Proof(byte[] key, byte[] value, byte[] leafSuffix,
                        JulcList<Fold> folds) { }

    public record ProofPair(Proof fact, Proof completeness) { }

    private MpfPairOnChainVerifier() {
    }

    @Entrypoint
    public static boolean validate(PlutusData stateRootDatum,
                                   ProofPair proofs,
                                   ScriptContext context) {
        byte[] stateRoot = Builtins.unBData(stateRootDatum);
        return Builtins.lengthOfByteString(stateRoot) == 32
                && verify(proofs.fact(), stateRoot)
                && verify(proofs.completeness(), stateRoot);
    }

    private static boolean verify(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long valueLength = Builtins.lengthOfByteString(proof.value());
        long encodedLength = Builtins.lengthOfByteString(proof.leafSuffix());
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || valueLength < 1 || valueLength > MAX_VALUE_BYTES
                || encodedLength < 1 || encodedLength > 33
                || proof.folds().size() > MAX_FOLDS) return false;
        byte[] pathHash = Builtins.blake2b_256(proof.key());
        long marker = Builtins.indexByteString(proof.leafSuffix(), 0);
        boolean odd = marker == 0;
        long suffixLength = odd ? 1 + (encodedLength - 2) * 2 : (encodedLength - 1) * 2;
        long cursorEnd = PATH_NIBBLES - suffixLength;
        if ((marker != 0 && marker != 255) || (odd && encodedLength < 2)
                || cursorEnd < 0 || (odd && Builtins.indexByteString(
                proof.leafSuffix(), 1) != pathNibble(pathHash, cursorEnd))
                || !Builtins.equalsByteString(Builtins.sliceByteString(
                odd ? 2 : 1, encodedLength - (odd ? 2 : 1), proof.leafSuffix()),
                Builtins.sliceByteString(odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1), pathHash))) return false;
        byte[] child = hash(proof.leafSuffix(), Builtins.blake2b_256(proof.value()));
        boolean valid = true;
        for (Fold fold : proof.folds()) {
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(pathHash, cursor, fold.prefix())
                    || pathNibble(pathHash, cursor + prefixLength) != nibble
                    || !hashLength(fold.neighbor1()) || !hashLength(fold.neighbor2())
                    || !hashLength(fold.neighbor3()) || !hashLength(fold.neighbor4())
                    || (Builtins.lengthOfByteString(fold.branchValueHash()) != 0
                    && !hashLength(fold.branchValueHash()))) {
                valid = false;
                break;
            }
            byte[] merkle = aggregate(nibble, child, fold.neighbor1(), fold.neighbor2(),
                    fold.neighbor3(), fold.neighbor4());
            if (Builtins.lengthOfByteString(fold.branchValueHash()) == 32) {
                merkle = hash(merkle, hash(Builtins.consByteString(
                        255, Builtins.emptyByteString()), fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return valid && cursorEnd == 0 && Builtins.equalsByteString(expectedRoot, child);
    }

    private static boolean validPrefix(byte[] pathHash, long cursor, byte[] prefix) {
        long index = 0;
        boolean valid = true;
        while (index < Builtins.lengthOfByteString(prefix)) {
            long nibble = Builtins.indexByteString(prefix, index);
            if (nibble < 0 || nibble > 15 || nibble != pathNibble(pathHash, cursor + index)) {
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

    private static byte[] aggregate(long nibble, byte[] me, byte[] a,
                                    byte[] b, byte[] c, byte[] d) {
        if (nibble == 0) return hash(hash(hash(hash(me, d), c), b), a);
        if (nibble == 1) return hash(hash(hash(hash(d, me), c), b), a);
        if (nibble == 2) return hash(hash(hash(c, hash(me, d)), b), a);
        if (nibble == 3) return hash(hash(hash(c, hash(d, me)), b), a);
        if (nibble == 4) return hash(hash(b, hash(hash(me, d), c)), a);
        if (nibble == 5) return hash(hash(b, hash(hash(d, me), c)), a);
        if (nibble == 6) return hash(hash(b, hash(c, hash(me, d))), a);
        if (nibble == 7) return hash(hash(b, hash(c, hash(d, me))), a);
        if (nibble == 8) return hash(a, hash(hash(hash(me, d), c), b));
        if (nibble == 9) return hash(a, hash(hash(hash(d, me), c), b));
        if (nibble == 10) return hash(a, hash(hash(c, hash(me, d)), b));
        if (nibble == 11) return hash(a, hash(hash(c, hash(d, me)), b));
        if (nibble == 12) return hash(a, hash(b, hash(hash(me, d), c)));
        if (nibble == 13) return hash(a, hash(b, hash(hash(d, me), c)));
        if (nibble == 14) return hash(a, hash(b, hash(c, hash(me, d))));
        return hash(a, hash(b, hash(c, hash(d, me))));
    }

    private static boolean hashLength(byte[] value) {
        return Builtins.lengthOfByteString(value) == 32;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }
}
