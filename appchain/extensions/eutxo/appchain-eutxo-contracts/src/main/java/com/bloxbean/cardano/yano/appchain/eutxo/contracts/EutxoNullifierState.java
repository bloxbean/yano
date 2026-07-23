package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Singleton bridge cursor. Claims settle in their committed sequence, giving
 * the simple v1 bridge exact replay protection without an unbounded datum.
 */
public record EutxoNullifierState(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        long nextSettlementSequence,
        long generation
) {
    public static final int ABI_VERSION = 1;

    public EutxoNullifierState {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO nullifier-state ABI");
        }
        chainId = Objects.requireNonNull(chainId, "chainId").trim();
        int chainBytes = chainId.getBytes(StandardCharsets.UTF_8).length;
        if (chainBytes < 1 || chainBytes > 128) {
            throw new IllegalArgumentException(
                    "nullifier chain id must contain 1-128 UTF-8 bytes");
        }
        if (bridgeEpoch < 0 || nextSettlementSequence < 0 || generation < 0) {
            throw new IllegalArgumentException(
                    "nullifier epoch, sequence, and generation cannot be negative");
        }
    }

    public EutxoNullifierState advance(long settledSequence) {
        if (settledSequence != nextSettlementSequence) {
            throw new IllegalArgumentException(
                    "claims must settle in committed sequence");
        }
        return new EutxoNullifierState(
                abiVersion,
                chainId,
                bridgeEpoch,
                Math.addExact(nextSettlementSequence, 1),
                generation);
    }

    public PlutusData toPlutusData() {
        return ConstrPlutusData.of(
                0,
                BigIntPlutusData.of(abiVersion),
                BytesPlutusData.of(chainId.getBytes(StandardCharsets.UTF_8)),
                BigIntPlutusData.of(bridgeEpoch),
                BigIntPlutusData.of(nextSettlementSequence),
                BigIntPlutusData.of(generation));
    }

    public byte[] encode() {
        return toPlutusData().serializeToBytes();
    }

    public static EutxoNullifierState decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 0
                    || !Arrays.equals(cbor, decoded.serializeToBytes())) {
                throw new IllegalArgumentException(
                        "nullifier state must use canonical constructor zero");
            }
            List<PlutusData> fields =
                    constructor.getData().getPlutusDataList();
            if (fields.size() != 5) {
                throw new IllegalArgumentException(
                        "nullifier state must contain five fields");
            }
            return new EutxoNullifierState(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    integer(fields.get(2), "bridge epoch").longValueExact(),
                    integer(fields.get(3), "next sequence").longValueExact(),
                    integer(fields.get(4), "generation").longValueExact());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid nullifier-state datum", failure);
        }
    }

    private static byte[] bytes(PlutusData data, String field) {
        if (!(data instanceof BytesPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be Plutus bytes");
        }
        return value.getValue();
    }

    private static BigInteger integer(PlutusData data, String field) {
        if (!(data instanceof BigIntPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be a Plutus integer");
        }
        return value.getValue();
    }

    private static String string(PlutusData data, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes(data, field)))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    field + " must be canonical UTF-8", failure);
        }
    }
}
