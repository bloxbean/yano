package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

import java.math.BigInteger;

/** Compiled same-root stake predicate plus authenticated completeness verifier. */
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
                        JulcList<Fold> folds, BigInteger terminalCursor,
                        byte[] conflictingKeyHash, byte[] conflictingValueHash) { }

    /** 0=min amount, 1=pool, 2=min amount+pool, 3=exact amount+pool. */
    public record ProofPair(Proof fact, Proof completeness,
                            byte[] factKey, byte[] completenessKey,
                            BigInteger predicate, BigInteger coin,
                            byte[] poolHash) { }

    private record UInt(BigInteger value, long next) { }

    private MpfPairOnChainVerifier() {
    }

    @Entrypoint
    public static boolean validate(PlutusData stateRootDatum,
                                   ProofPair proofs,
                                   ScriptContext context) {
        byte[] stateRoot = Builtins.unBData(stateRootDatum);
        long mode = proofs.predicate().longValue();
        return Builtins.lengthOfByteString(stateRoot) == 32
                && Builtins.equalsByteString(proofs.fact().key(), proofs.factKey())
                && Builtins.equalsByteString(proofs.completeness().key(),
                proofs.completenessKey())
                && (mode == 4 ? verifyAbsence(proofs.fact(), stateRoot)
                : verify(proofs.fact(), stateRoot))
                && verify(proofs.completeness(), stateRoot)
                && complete(proofs.completeness().value())
                && (mode == 4 || stakePredicate(proofs.fact().value(), proofs.predicate(),
                proofs.coin(), proofs.poolHash()));
    }

    private static boolean verifyAbsence(Proof proof, byte[] expectedRoot) {
        long keyLength = Builtins.lengthOfByteString(proof.key());
        long suffixLength = Builtins.lengthOfByteString(proof.leafSuffix());
        long cursorEnd = proof.terminalCursor().longValue();
        if (Builtins.lengthOfByteString(expectedRoot) != 32
                || keyLength < 1 || keyLength > MAX_KEY_BYTES
                || cursorEnd < 0 || cursorEnd > PATH_NIBBLES
                || proof.folds().size() > MAX_FOLDS) return false;
        byte[] queryPath = Builtins.blake2b_256(proof.key());
        boolean missing = suffixLength == 0;
        if (missing) {
            if (Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 0
                    || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 0) return false;
        } else {
            if (suffixLength > 33
                    || Builtins.lengthOfByteString(proof.conflictingKeyHash()) != 32
                    || Builtins.lengthOfByteString(proof.conflictingValueHash()) != 32
                    || Builtins.equalsByteString(queryPath, proof.conflictingKeyHash())
                    || !validLeafSuffix(proof.conflictingKeyHash(), cursorEnd,
                    proof.leafSuffix())) return false;
        }
        byte[] child = missing ? new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0}
                : hash(proof.leafSuffix(), proof.conflictingValueHash());
        boolean valid = true;
        for (Fold fold : proof.folds()) {
            long cursor = fold.cursor().longValue();
            long prefixLength = Builtins.lengthOfByteString(fold.prefix());
            long nibble = fold.nibble().longValue();
            if (cursor < 0 || nibble < 0 || nibble > 15
                    || cursor + prefixLength + 1 != cursorEnd
                    || !validPrefix(queryPath, cursor, fold.prefix())
                    || pathNibble(queryPath, cursor + prefixLength) != nibble
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

    private static boolean validLeafSuffix(byte[] pathHash, long cursorEnd,
                                           byte[] encoded) {
        long encodedLength = Builtins.lengthOfByteString(encoded);
        long marker = Builtins.indexByteString(encoded, 0);
        boolean odd = marker == 0;
        long suffixNibbles = odd ? 1 + (encodedLength - 2) * 2
                : (encodedLength - 1) * 2;
        return (marker == 0 || marker == 255) && (!odd || encodedLength >= 2)
                && cursorEnd + suffixNibbles == PATH_NIBBLES
                && (!odd || Builtins.indexByteString(encoded, 1)
                == pathNibble(pathHash, cursorEnd))
                && Builtins.equalsByteString(Builtins.sliceByteString(
                odd ? 2 : 1, encodedLength - (odd ? 2 : 1), encoded),
                Builtins.sliceByteString(odd ? (cursorEnd + 1) / 2 : cursorEnd / 2,
                        encodedLength - (odd ? 2 : 1), pathHash));
    }

    private static boolean stakePredicate(byte[] value, BigInteger predicate,
                                          BigInteger expectedCoin, byte[] expectedPool) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 32 || Builtins.indexByteString(value, 0) != 130
                || expectedCoin.signum() < 0) return false;
        UInt coin = readUInt(value, 1);
        long poolOffset = coin.next();
        if (coin.value().signum() < 0 || poolOffset + 30 != length
                || Builtins.indexByteString(value, poolOffset) != 88
                || Builtins.indexByteString(value, poolOffset + 1) != 28) return false;
        byte[] pool = Builtins.sliceByteString(poolOffset + 2, 28, value);
        boolean amount = coin.value().compareTo(expectedCoin) >= 0;
        boolean exact = coin.value().equals(expectedCoin);
        boolean delegated = Builtins.lengthOfByteString(expectedPool) == 28
                && Builtins.equalsByteString(pool, expectedPool);
        long mode = predicate.longValue();
        return mode == 0 ? amount
                : mode == 1 ? delegated
                : mode == 2 ? amount && delegated
                : mode == 3 && exact && delegated;
    }

    /** Strict canonical CBOR [v,epoch,semantics,total,chunkSize,chunks,root,received,complete]. */
    private static boolean complete(byte[] value) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 42 || Builtins.indexByteString(value, 0) != 137) return false;
        UInt version = readUInt(value, 1);
        UInt epoch = readUInt(value, version.next());
        UInt semantics = readUInt(value, epoch.next());
        UInt total = readUInt(value, semantics.next());
        UInt chunkSize = readUInt(value, total.next());
        UInt chunks = readUInt(value, chunkSize.next());
        long rootOffset = chunks.next();
        if (!version.value().equals(BigInteger.ONE)
                || !semantics.value().equals(BigInteger.ZERO)
                || epoch.value().signum() < 0 || total.value().signum() < 0
                || chunkSize.value().signum() <= 0 || chunks.value().signum() < 0
                || rootOffset + 34 > length
                || Builtins.indexByteString(value, rootOffset) != 88
                || Builtins.indexByteString(value, rootOffset + 1) != 32) return false;
        UInt received = readUInt(value, rootOffset + 34);
        UInt flag = readUInt(value, received.next());
        return flag.next() == length
                && received.value().equals(chunks.value())
                && flag.value().equals(BigInteger.ONE);
    }

    /** Returns value=-1 on malformed or non-canonical unsigned CBOR. */
    private static UInt readUInt(byte[] encoded, long offset) {
        long length = Builtins.lengthOfByteString(encoded);
        if (offset < 0 || offset >= length) return new UInt(BigInteger.valueOf(-1), length + 1);
        long head = Builtins.indexByteString(encoded, offset);
        if (head < 24) return new UInt(BigInteger.valueOf(head), offset + 1);
        if (head == 24 && offset + 2 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1);
            return value >= 24 ? new UInt(BigInteger.valueOf(value), offset + 2)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 25 && offset + 3 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1) * 256
                    + Builtins.indexByteString(encoded, offset + 2);
            return value >= 256 ? new UInt(BigInteger.valueOf(value), offset + 3)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 26 && offset + 5 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1) * 16777216
                    + Builtins.indexByteString(encoded, offset + 2) * 65536
                    + Builtins.indexByteString(encoded, offset + 3) * 256
                    + Builtins.indexByteString(encoded, offset + 4);
            return value >= 65536 ? new UInt(BigInteger.valueOf(value), offset + 5)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 27 && offset + 9 <= length) {
            long high = Builtins.indexByteString(encoded, offset + 1);
            long value = high * 72057594037927936L
                    + Builtins.indexByteString(encoded, offset + 2) * 281474976710656L
                    + Builtins.indexByteString(encoded, offset + 3) * 1099511627776L
                    + Builtins.indexByteString(encoded, offset + 4) * 4294967296L
                    + Builtins.indexByteString(encoded, offset + 5) * 16777216L
                    + Builtins.indexByteString(encoded, offset + 6) * 65536L
                    + Builtins.indexByteString(encoded, offset + 7) * 256L
                    + Builtins.indexByteString(encoded, offset + 8);
            return high < 128 && value >= 4294967296L
                    ? new UInt(BigInteger.valueOf(value), offset + 9)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        return new UInt(BigInteger.valueOf(-1), length + 1);
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
