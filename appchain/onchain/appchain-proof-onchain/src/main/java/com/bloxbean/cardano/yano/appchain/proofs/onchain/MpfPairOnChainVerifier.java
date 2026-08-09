package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/** Compiled same-root Cardano-history predicate plus authenticated completeness verifier. */
@SpendingValidator
public final class MpfPairOnChainVerifier {
    private static final long PATH_NIBBLES = 64;
    private static final long MAX_KEY_BYTES = 256;
    private static final long MAX_VALUE_BYTES = 8 * 1024;
    private static final long MAX_FOLDS = 32;

    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;

    public record Fold(BigInteger cursor, byte[] prefix, BigInteger nibble,
                       byte[] neighbor1, byte[] neighbor2, byte[] neighbor3,
                       byte[] neighbor4, byte[] branchValueHash) { }

    public record Proof(byte[] key, byte[] value, byte[] leafSuffix,
                        JulcList<Fold> folds, BigInteger terminalCursor,
                        byte[] conflictingKeyHash, byte[] conflictingValueHash) { }

    /**
     * Predicates: 0-4 stake min/pool/min+pool/exact+pool/absence;
     * 5 exact proposal action/status/reason; 6 DRep minimum; 7 DRep exact.
     */
    public record ProofPair(Proof fact, Proof completeness,
                            byte[] factKey, byte[] completenessKey,
                            BigInteger predicate, BigInteger coin,
                            byte[] poolHash) { }

    private record UInt(BigInteger value, long next) { }
    private record AnchorRoot(byte[] stateRoot) { }

    private MpfPairOnChainVerifier() {
    }

    @Entrypoint
    public static boolean validate(PlutusData datum,
                                   ProofPair proofs,
                                   ScriptContext context) {
        Optional<AnchorRoot> anchor = acceptedAnchor(context);
        return anchor.isPresent() && verifyAtRoot(proofs, anchor.get().stateRoot());
    }

    /** Reusable semantic pair check after an application has authenticated the anchor root. */
    public static boolean verifyAtRoot(ProofPair proofs, byte[] stateRoot) {
        long mode = proofs.predicate().longValue();
        return Builtins.lengthOfByteString(stateRoot) == 32
                && Builtins.equalsByteString(proofs.fact().key(), proofs.factKey())
                && Builtins.equalsByteString(proofs.completeness().key(),
                proofs.completenessKey())
                && (mode == 4 ? verifyAbsence(proofs.fact(), stateRoot)
                : verify(proofs.fact(), stateRoot))
                && verify(proofs.completeness(), stateRoot)
                && completeness(proofs.completeness().value(), mode)
                && semantic(proofs.fact().value(), mode, proofs.coin(), proofs.poolHash());
    }

    private static Optional<AnchorRoot> acceptedAnchor(ScriptContext context) {
        Optional<AnchorRoot> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(input.resolved().value(), anchorThreadPolicyId,
                    anchorThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(input.resolved(), anchorScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = anchor(input.resolved().datum());
            }
        }
        return count.equals(BigInteger.ONE) ? result : Optional.empty();
    }

    private static Optional<AnchorRoot> anchor(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return anchorData(inline.datum());
        }
        return Optional.empty();
    }

    private static Optional<AnchorRoot> anchorData(PlutusData value) {
        if (Builtins.constrTag(value) != 0) return Optional.empty();
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] genesis = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] application = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] profile = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] fingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger height = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] blockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData f10 = Builtins.tailList(f9);
        PlutusData trailing = Builtins.tailList(f10);
        if (!version.equals(BigInteger.ONE)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chainId) < 1
                || Builtins.lengthOfByteString(chainId) > 128
                || height.signum() < 0
                || Builtins.lengthOfByteString(blockHash) != 32
                || Builtins.lengthOfByteString(stateRoot) != 32
                || !Builtins.equalsByteString(genesis, expectedChainGenesisId)
                || !Builtins.equalsByteString(application, expectedApplicationId)
                || !Builtins.equalsByteString(profile, expectedCommitmentProfileId)
                || !Builtins.equalsByteString(fingerprint, expectedFormatFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new AnchorRoot(stateRoot));
    }

    private static boolean atScriptAddress(TxOut output, byte[] scriptHash) {
        PlutusData credential = output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        byte[] observed = Builtins.unBData(Builtins.headList(fields));
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(observed, scriptHash);
    }

    private static boolean semantic(byte[] value, long mode,
                                    BigInteger expected, byte[] auxiliary) {
        if (mode == 4) return true;
        if (mode >= 0 && mode <= 3) {
            return stakePredicate(value, BigInteger.valueOf(mode), expected, auxiliary);
        }
        if (mode == 5) return proposalPredicate(value, expected, auxiliary);
        if (mode == 6 || mode == 7) return drepPredicate(value, expected, mode == 7);
        return false;
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

    private static boolean completeness(byte[] value, long mode) {
        return mode >= 0 && mode <= 4 ? stakeComplete(value)
                : mode == 5 ? proposalComplete(value)
                : (mode == 6 || mode == 7) && drepComplete(value);
    }

    /** Strict canonical CBOR [v,epoch,semantics,total,chunkSize,chunks,root,received,complete]. */
    private static boolean stakeComplete(byte[] value) {
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

    /** Strict canonical CBOR [v,epoch,total,root,received,complete]. */
    private static boolean proposalComplete(byte[] value) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 38 || Builtins.indexByteString(value, 0) != 134) return false;
        UInt version = readUInt(value, 1);
        UInt epoch = readUInt(value, version.next());
        UInt total = readUInt(value, epoch.next());
        long rootOffset = total.next();
        if (!version.value().equals(BigInteger.ONE) || epoch.value().signum() < 0
                || total.value().signum() < 0 || rootOffset + 34 > length
                || Builtins.indexByteString(value, rootOffset) != 88
                || Builtins.indexByteString(value, rootOffset + 1) != 32) return false;
        UInt received = readUInt(value, rootOffset + 34);
        UInt flag = readUInt(value, received.next());
        return flag.next() == length && received.value().equals(total.value())
                && flag.value().equals(BigInteger.ONE);
    }

    /** Strict canonical CBOR [v,epoch,total,chunkSize,chunks,root,received,complete]. */
    private static boolean drepComplete(byte[] value) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 40 || Builtins.indexByteString(value, 0) != 136) return false;
        UInt version = readUInt(value, 1);
        UInt epoch = readUInt(value, version.next());
        UInt total = readUInt(value, epoch.next());
        UInt chunkSize = readUInt(value, total.next());
        UInt chunks = readUInt(value, chunkSize.next());
        long rootOffset = chunks.next();
        if (!version.value().equals(BigInteger.ONE) || epoch.value().signum() < 0
                || total.value().signum() < 0 || chunkSize.value().signum() <= 0
                || chunks.value().signum() < 0 || rootOffset + 34 > length
                || Builtins.indexByteString(value, rootOffset) != 88
                || Builtins.indexByteString(value, rootOffset + 1) != 32) return false;
        UInt received = readUInt(value, rootOffset + 34);
        UInt flag = readUInt(value, received.next());
        return flag.next() == length && received.value().equals(chunks.value())
                && flag.value().equals(BigInteger.ONE);
    }

    /** Exact canonical [actionType,status,reason,proposedEpoch,expiresAfterEpoch]. */
    private static boolean proposalPredicate(byte[] value, BigInteger expectedAction,
                                             byte[] expectedStatusReason) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 6 || Builtins.indexByteString(value, 0) != 133
                || expectedAction.signum() < 0
                || Builtins.lengthOfByteString(expectedStatusReason) != 2) return false;
        UInt action = readUInt(value, 1);
        UInt status = readUInt(value, action.next());
        UInt reason = readUInt(value, status.next());
        UInt proposed = readUInt(value, reason.next());
        UInt expires = readUInt(value, proposed.next());
        return expires.next() == length && action.value().equals(expectedAction)
                && status.value().longValue()
                == Builtins.indexByteString(expectedStatusReason, 0)
                && reason.value().longValue()
                == Builtins.indexByteString(expectedStatusReason, 1)
                && proposed.value().signum() >= 0
                && expires.value().compareTo(proposed.value()) >= 0;
    }

    private static boolean drepPredicate(byte[] value, BigInteger expected, boolean exact) {
        if (expected.signum() < 0) return false;
        UInt amount = readUInt(value, 0);
        return amount.next() == Builtins.lengthOfByteString(value)
                && (exact ? amount.value().equals(expected)
                : amount.value().compareTo(expected) >= 0);
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
