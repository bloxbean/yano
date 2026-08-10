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

/** Product-bound named protocol-parameter equality and unsigned-range validator. */
@SpendingValidator
public final class CardanoHistoryParametersValidator {
    public static final int DOCUMENT_EXACT = 0;
    public static final int UINT_EXACT = 1;
    public static final int UINT_MINIMUM = 2;
    public static final int UINT_MAXIMUM = 3;
    public static final int UINT_RANGE = 4;

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
    @Param static BigInteger expectedType;
    @Param static BigInteger expectedValue;
    @Param static BigInteger expectedMaximum;
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
                && matches(proof.value(), expectedEpoch, predicate, expectedType,
                expectedValue, expectedMaximum, expectedDocument);
    }

    /** Semantic/root verifier used by applications that authenticate the anchor separately. */
    public static boolean verifyAtRoot(MpfOnChainVerifier.Proof proof, byte[] root,
                                       byte[] key, BigInteger epoch, BigInteger mode,
                                       BigInteger type, BigInteger expected, BigInteger maximum,
                                       byte[] document) {
        return Builtins.equalsByteString(proof.key(), key)
                && MpfOnChainVerifier.verifyInclusion(proof, root)
                && matches(proof.value(), epoch, mode, type, expected, maximum, document);
    }

    private static boolean matches(byte[] value, BigInteger epoch, BigInteger mode,
                                   BigInteger type, BigInteger expected, BigInteger maximum,
                                   byte[] document) {
        if (epoch.signum() < 0 || mode.signum() < 0
                || mode.compareTo(BigInteger.valueOf(4)) > 0) return false;
        if (mode.equals(BigInteger.ZERO)) {
            return Builtins.lengthOfByteString(document) > 0
                    && Builtins.equalsByteString(value, document);
        }
        if (Builtins.lengthOfByteString(value) < 7 || Builtins.indexByteString(value, 0) != 133
                || expected.signum() < 0 || maximum.signum() < 0) return false;
        UInt version = readUInt(value, 1);
        UInt encodedEpoch = readUInt(value, version.next());
        long afterId = skipText(value, encodedEpoch.next());
        UInt encodedType = readUInt(value, afterId);
        UInt current = readUInt(value, encodedType.next());
        if (!version.value().equals(BigInteger.ONE) || !encodedEpoch.value().equals(epoch)
                || afterId < 0 || !encodedType.value().equals(type)
                || !(type.equals(BigInteger.ZERO) || type.equals(BigInteger.valueOf(2)))
                || current.next() != Builtins.lengthOfByteString(value)) return false;
        return mode.equals(BigInteger.ONE) ? current.value().equals(expected)
                : mode.equals(BigInteger.valueOf(2)) ? current.value().compareTo(expected) >= 0
                : mode.equals(BigInteger.valueOf(3)) ? current.value().compareTo(expected) <= 0
                : current.value().compareTo(expected) >= 0
                && current.value().compareTo(maximum) <= 0 && expected.compareTo(maximum) <= 0;
    }

    private static long skipText(byte[] value, long offset) {
        if (offset < 0 || offset >= Builtins.lengthOfByteString(value)) return -1;
        long head = Builtins.indexByteString(value, offset);
        if (head / 32 != 3) return -1;
        long additional = head % 32;
        if (additional < 24) {
            return additional < 1 || offset + 1 + additional > Builtins.lengthOfByteString(value)
                    ? -1 : offset + 1 + additional;
        }
        if (additional == 24 && offset + 2 <= Builtins.lengthOfByteString(value)) {
            long length = Builtins.indexByteString(value, offset + 1);
            return length < 24 || length > 64 || offset + 2 + length > Builtins.lengthOfByteString(value)
                    ? -1 : offset + 2 + length;
        }
        return -1;
    }

    private static UInt readUInt(byte[] value, long offset) {
        if (offset < 0 || offset >= Builtins.lengthOfByteString(value)) {
            return new UInt(BigInteger.valueOf(-1), -1);
        }
        long head = Builtins.indexByteString(value, offset);
        // ProtocolParamsCanonicalCodec writes Java BigInteger values as the canonical
        // positive-bignum form: tag(2) followed by a definite byte string. Keep accepting
        // ordinary major-type-0 integers for the leaf version, epoch and type discriminator.
        if (head == 194) return readPositiveBignum(value, offset + 1);
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

    private static UInt readPositiveBignum(byte[] value, long offset) {
        if (offset < 0 || offset >= Builtins.lengthOfByteString(value)) {
            return new UInt(BigInteger.valueOf(-1), -1);
        }
        long head = Builtins.indexByteString(value, offset);
        if (head / 32 != 2) return new UInt(BigInteger.valueOf(-1), -1);
        long additional = head % 32;
        if (additional < 24) {
            return readMagnitude(value, offset + 1, additional);
        }
        if (additional == 24 && offset + 2 <= Builtins.lengthOfByteString(value)) {
            long width = Builtins.indexByteString(value, offset + 1);
            return width < 24 ? new UInt(BigInteger.valueOf(-1), -1)
                    : readMagnitude(value, offset + 2, width);
        }
        return new UInt(BigInteger.valueOf(-1), -1);
    }

    private static UInt readMagnitude(byte[] value, long payload, long width) {
        // Protocol parameters are bounded integers. A 32-byte ceiling keeps on-chain work
        // predictable while leaving ample room beyond current Cardano values.
        if (width < 1 || width > 32 || payload + width > Builtins.lengthOfByteString(value)) {
            return new UInt(BigInteger.valueOf(-1), -1);
        }
        BigInteger result = BigInteger.ZERO;
        long cursor = 0;
        while (cursor < width) {
            result = result.multiply(BigInteger.valueOf(256)).add(BigInteger.valueOf(
                    Builtins.indexByteString(value, payload + cursor)));
            cursor += 1;
        }
        return new UInt(result, payload + width);
    }

    private record UInt(BigInteger value, long next) { }
}
