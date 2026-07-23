package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Inline datum on an L2 output that is consumed into an irrevocable
 * withdrawal claim.
 *
 * <p>Wire shape:
 * {@code Constr(1, [1, chain-id, bridge-epoch, L1-destination, nonce])}.</p>
 */
public record EutxoWithdrawalDatum(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        String destinationAddress,
        byte[] nonce
) {
    public static final int ABI_VERSION = 1;

    public EutxoWithdrawalDatum {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException("unsupported EUTxO withdrawal datum ABI");
        }
        chainId = text(chainId, "chainId", 128);
        if (bridgeEpoch < 0) {
            throw new IllegalArgumentException("bridge epoch cannot be negative");
        }
        destinationAddress = text(destinationAddress, "destinationAddress", 256);
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        if (nonce.length != 32) {
            throw new IllegalArgumentException("withdrawal nonce must contain 32 bytes");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    public byte[] encode() {
        return ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(abiVersion),
                        bytes(chainId),
                        BigIntPlutusData.of(bridgeEpoch),
                        bytes(destinationAddress),
                        BytesPlutusData.of(nonce)))
                .build()
                .serializeToBytes();
    }

    public static EutxoWithdrawalDatum decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        if (cbor.length == 0 || cbor.length > EutxoProfile.V1.maxOutputCborBytes()) {
            throw new IllegalArgumentException("withdrawal datum exceeds the EUTxO datum bound");
        }
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 1) {
                throw new IllegalArgumentException(
                        "withdrawal datum must use constructor one");
            }
            List<PlutusData> fields = constructor.getData().getPlutusDataList();
            if (fields.size() != 5) {
                throw new IllegalArgumentException(
                        "withdrawal datum must contain five fields");
            }
            return new EutxoWithdrawalDatum(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    integer(fields.get(2), "bridge epoch").longValueExact(),
                    string(fields.get(3), "destination address"),
                    bytes(fields.get(4), "withdrawal nonce"));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid EUTxO withdrawal datum", failure);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoWithdrawalDatum datum
                && abiVersion == datum.abiVersion
                && chainId.equals(datum.chainId)
                && bridgeEpoch == datum.bridgeEpoch
                && destinationAddress.equals(datum.destinationAddress)
                && java.util.Arrays.equals(nonce, datum.nonce);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, bridgeEpoch, destinationAddress);
        return 31 * result + java.util.Arrays.hashCode(nonce);
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

    private static java.math.BigInteger integer(PlutusData data, String field) {
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
