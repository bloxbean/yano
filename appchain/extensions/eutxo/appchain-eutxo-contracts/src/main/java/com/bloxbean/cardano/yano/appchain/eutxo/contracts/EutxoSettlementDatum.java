package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Inline datum on a continuing federated vault output. It binds the exact
 * irrevocable claim to the payout that the stable L1 observer confirms.
 */
public record EutxoSettlementDatum(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        String claimId,
        String destinationAddress,
        BigInteger lovelace
) {
    public static final int ABI_VERSION = 1;

    public EutxoSettlementDatum {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException("unsupported EUTxO settlement datum ABI");
        }
        chainId = text(chainId, "chainId", 128);
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        claimId = canonicalHash(claimId);
        destinationAddress = text(destinationAddress, "destinationAddress", 256);
        lovelace = Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() <= 0) {
            throw new IllegalArgumentException("settlement lovelace must be positive");
        }
    }

    public byte[] encode() {
        return ConstrPlutusData.builder()
                .alternative(2)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(abiVersion),
                        bytes(chainId),
                        BigIntPlutusData.of(bridgeEpoch),
                        BytesPlutusData.of(HexFormat.of().parseHex(claimId)),
                        bytes(destinationAddress),
                        BigIntPlutusData.of(lovelace)))
                .build()
                .serializeToBytes();
    }

    public static EutxoSettlementDatum decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 2) {
                throw new IllegalArgumentException(
                        "settlement datum must use constructor two");
            }
            List<PlutusData> fields = constructor.getData().getPlutusDataList();
            if (fields.size() != 6) {
                throw new IllegalArgumentException(
                        "settlement datum must contain six fields");
            }
            return new EutxoSettlementDatum(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    integer(fields.get(2), "bridge epoch").longValueExact(),
                    HexFormat.of().formatHex(bytes(fields.get(3), "claim id")),
                    string(fields.get(4), "destination address"),
                    integer(fields.get(5), "lovelace"));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid EUTxO settlement datum", failure);
        }
    }

    private static String canonicalHash(String value) {
        String normalized = text(value, "claimId", 64);
        if (normalized.length() != 64
                || !normalized.equals(normalized.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("claimId must be 32-byte lowercase hex");
        }
        try {
            HexFormat.of().parseHex(normalized);
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "claimId must be 32-byte lowercase hex", failure);
        }
    }

    private static BytesPlutusData bytes(String value) {
        return BytesPlutusData.of(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(PlutusData data, String field) {
        if (!(data instanceof BytesPlutusData value)) {
            throw new IllegalArgumentException(field + " must be Plutus bytes");
        }
        return value.getValue();
    }

    private static String string(PlutusData data, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes(data, field)))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(field + " must be canonical UTF-8", failure);
        }
    }

    private static BigInteger integer(PlutusData data, String field) {
        if (!(data instanceof BigIntPlutusData value)) {
            throw new IllegalArgumentException(field + " must be a Plutus integer");
        }
        return value.getValue();
    }

    private static String text(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }
}
