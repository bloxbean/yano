package com.bloxbean.cardano.yano.appchain.history.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfOnChainVerifier;
import com.bloxbean.cardano.yano.appchain.proofs.onchain.MpfAnchorLib;

import java.math.BigInteger;

/** Product-bound protocol-parameter equality and unsigned-range validator. */
@SpendingValidator
public final class CardanoHistoryParametersValidator {
    public static final int DOCUMENT_EXACT = 0;
    public static final int UINT_EXACT = 1;
    public static final int UINT_MINIMUM = 2;
    public static final int UINT_MAXIMUM = 3;

    @Param static byte[] anchorThreadPolicyId;
    @Param static byte[] anchorThreadAssetName;
    @Param static byte[] anchorScriptHash;
    @Param static byte[] expectedChainGenesisId;
    @Param static byte[] expectedApplicationId;
    @Param static byte[] expectedCommitmentProfileId;
    @Param static byte[] expectedFormatFingerprint;
    @Param static byte[] expectedStateKey;
    @Param static BigInteger expectedEpoch;
    @Param static BigInteger predicate;
    @Param static BigInteger fieldIndex;
    @Param static BigInteger expectedValue;
    @Param static byte[] expectedDocument;

    private CardanoHistoryParametersValidator() { }

    @Entrypoint
    public static boolean validate(PlutusData datum, MpfOnChainVerifier.Proof proof,
                                   ScriptContext context) {
        var anchor = MpfAnchorLib.acceptedAnchor(context, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
        return anchor.isPresent()
                && Builtins.equalsByteString(proof.key(), expectedStateKey)
                && MpfOnChainVerifier.verifyInclusion(proof, anchor.get().stateRoot())
                && matches(proof.value(), expectedEpoch, predicate, fieldIndex,
                expectedValue, expectedDocument);
    }

    /** Semantic/root verifier used by applications that authenticate the anchor separately. */
    public static boolean verifyAtRoot(MpfOnChainVerifier.Proof proof, byte[] root,
                                       byte[] key, BigInteger epoch, BigInteger mode,
                                       BigInteger index, BigInteger expected,
                                       byte[] document) {
        return Builtins.equalsByteString(proof.key(), key)
                && MpfOnChainVerifier.verifyInclusion(proof, root)
                && matches(proof.value(), epoch, mode, index, expected, document);
    }

    private static boolean matches(byte[] value, BigInteger epoch, BigInteger mode,
                                   BigInteger index, BigInteger expected, byte[] document) {
        if (epoch.signum() < 0 || mode.signum() < 0
                || mode.compareTo(BigInteger.valueOf(3)) > 0
                || Builtins.lengthOfByteString(value) < 4
                || Builtins.indexByteString(value, 0) != 152
                || Builtins.indexByteString(value, 1) != 56) return false;
        UInt version = readUInt(value, 2);
        UInt encodedEpoch = readUInt(value, version.next());
        if (!version.value().equals(BigInteger.ONE)
                || !encodedEpoch.value().equals(epoch)) return false;
        if (mode.equals(BigInteger.ZERO)) {
            return Builtins.lengthOfByteString(document) > 0
                    && Builtins.equalsByteString(value, document);
        }
        if (index.compareTo(BigInteger.valueOf(2)) < 0
                || index.compareTo(BigInteger.TEN) > 0 || expected.signum() < 0) return false;
        UInt current = encodedEpoch;
        long cursor = current.next();
        long field = 2;
        while (field <= index.longValue()) {
            current = readUInt(value, cursor);
            cursor = current.next();
            field += 1;
        }
        return mode.equals(BigInteger.ONE) ? current.value().equals(expected)
                : mode.equals(BigInteger.valueOf(2)) ? current.value().compareTo(expected) >= 0
                : current.value().compareTo(expected) <= 0;
    }

    private static UInt readUInt(byte[] value, long offset) {
        if (offset < 0 || offset >= Builtins.lengthOfByteString(value)) {
            return new UInt(BigInteger.valueOf(-1), -1);
        }
        long head = Builtins.indexByteString(value, offset);
        long additional = head % 32;
        if (head / 32 != 0) return new UInt(BigInteger.valueOf(-1), -1);
        if (additional < 24) return new UInt(BigInteger.valueOf(additional), offset + 1);
        long width = additional == 24 ? 1 : additional == 25 ? 2
                : additional == 26 ? 4 : additional == 27 ? 8 : -1;
        if (width < 0 || offset + 1 + width > Builtins.lengthOfByteString(value)) {
            return new UInt(BigInteger.valueOf(-1), -1);
        }
        BigInteger result = BigInteger.ZERO;
        long cursor = 0;
        while (cursor < width) {
            result = result.multiply(BigInteger.valueOf(256)).add(BigInteger.valueOf(
                    Builtins.indexByteString(value, offset + 1 + cursor)));
            cursor += 1;
        }
        return new UInt(result, offset + 1 + width);
    }

    private record UInt(BigInteger value, long next) { }
}
