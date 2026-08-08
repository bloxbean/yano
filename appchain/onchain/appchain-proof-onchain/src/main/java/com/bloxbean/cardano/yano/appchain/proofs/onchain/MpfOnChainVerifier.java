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

/** Bounded MPF inclusion verifier reusable by application validators. */
@SpendingValidator
public final class MpfOnChainVerifier {
    public static final long PATH_NIBBLES = 64;
    public static final long MAX_KEY_BYTES = 256;
    public static final long MAX_VALUE_BYTES = 8 * 1024;
    public static final long MAX_FOLDS = 32;

    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedStateKey;

    public record Fold(
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
            JulcList<Fold> folds
    ) {
    }

    private MpfOnChainVerifier() {
    }

    record AnchorRoot(byte[] stateRoot) {
    }

    /** Minimal reference validator for an exact fact under the unique anchor reference input. */
    @Entrypoint
    public static boolean validate(PlutusData datum, Proof proof, ScriptContext context) {
        Optional<AnchorRoot> anchor = acceptedAnchor(context);
        return anchor.isPresent()
                && Builtins.equalsByteString(proof.key(), expectedStateKey)
                && verifyInclusion(proof, anchor.get().stateRoot());
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
        if (Builtins.constrTag(value) != 0) {
            return Optional.empty();
        }
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
        for (Fold fold : proof.folds()) {
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
