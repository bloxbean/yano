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
 * Refundable L1 deposit intent placed at the staging validator.
 *
 * <p>The staging outpoint is deliberately absent: a transaction cannot commit
 * its own body hash inside one of its outputs. The acceptance validator derives
 * the actual outpoint from its own input and places it in
 * {@link EutxoVaultDatum}.</p>
 */
public record EutxoStagingDatum(
        int abiVersion,
        String chainId,
        String l2Address,
        byte[] depositNonce,
        byte[] depositorKeyHash,
        long refundDeadline,
        EutxoL2KeyBinding l2KeyBinding
) {
    public static final int ABI_VERSION = 2;

    public EutxoStagingDatum(
            int abiVersion,
            String chainId,
            String l2Address,
            byte[] depositNonce,
            byte[] depositorKeyHash,
            long refundDeadline
    ) {
        this(abiVersion, chainId, l2Address, depositNonce,
                depositorKeyHash, refundDeadline, EutxoL2KeyBinding.none());
    }

    public EutxoStagingDatum {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO staging datum ABI");
        }
        chainId = text(chainId, "chainId", 128);
        l2Address = text(l2Address, "l2Address", 256);
        depositNonce = bytes(
                depositNonce, "deposit nonce", 32);
        depositorKeyHash = bytes(
                depositorKeyHash, "depositor key hash", 28);
        l2KeyBinding = Objects.requireNonNull(
                l2KeyBinding, "l2KeyBinding");
        if (refundDeadline < 0) {
            throw new IllegalArgumentException(
                    "refund deadline cannot be negative");
        }
    }

    @Override
    public byte[] depositNonce() {
        return depositNonce.clone();
    }

    @Override
    public byte[] depositorKeyHash() {
        return depositorKeyHash.clone();
    }

    public byte[] encode() {
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(abiVersion),
                        textBytes(chainId),
                        textBytes(l2Address),
                        BytesPlutusData.of(depositNonce),
                        BytesPlutusData.of(depositorKeyHash),
                        BigIntPlutusData.of(refundDeadline),
                        textBytes(l2KeyBinding.authorizationProfile()),
                        BigIntPlutusData.of(l2KeyBinding.keyEpoch()),
                        BytesPlutusData.of(l2KeyBinding.publicKey())))
                .build()
                .serializeToBytes();
    }

    public static EutxoStagingDatum decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        if (cbor.length == 0
                || cbor.length > EutxoProfile.V1.maxOutputCborBytes()) {
            throw new IllegalArgumentException(
                    "staging datum exceeds the EUTxO datum bound");
        }
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 0) {
                throw new IllegalArgumentException(
                        "staging datum must use constructor zero");
            }
            List<PlutusData> fields =
                    constructor.getData().getPlutusDataList();
            if (fields.size() != 9) {
                throw new IllegalArgumentException(
                        "staging datum must contain nine fields");
            }
            return new EutxoStagingDatum(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    string(fields.get(2), "L2 address"),
                    rawBytes(fields.get(3), "deposit nonce"),
                    rawBytes(fields.get(4), "depositor key hash"),
                    integer(fields.get(5), "refund deadline")
                            .longValueExact(),
                    new EutxoL2KeyBinding(
                            string(fields.get(6), "authorization profile"),
                            integer(fields.get(7), "L2 key epoch")
                                    .longValueExact(),
                            rawBytes(fields.get(8), "L2 public key")));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid EUTxO staging datum", failure);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoStagingDatum datum
                && abiVersion == datum.abiVersion
                && chainId.equals(datum.chainId)
                && l2Address.equals(datum.l2Address)
                && java.util.Arrays.equals(
                depositNonce, datum.depositNonce)
                && java.util.Arrays.equals(
                depositorKeyHash, datum.depositorKeyHash)
                && refundDeadline == datum.refundDeadline
                && l2KeyBinding.equals(datum.l2KeyBinding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, l2Address, refundDeadline,
                l2KeyBinding);
        result = 31 * result
                + java.util.Arrays.hashCode(depositNonce);
        return 31 * result
                + java.util.Arrays.hashCode(depositorKeyHash);
    }

    private static BytesPlutusData textBytes(String value) {
        return BytesPlutusData.of(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(
            byte[] value,
            String field,
            int expectedLength
    ) {
        Objects.requireNonNull(value, field);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(
                    field + " must contain " + expectedLength + " bytes");
        }
        return value.clone();
    }

    private static byte[] rawBytes(PlutusData data, String field) {
        if (!(data instanceof BytesPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be Plutus bytes");
        }
        return value.getValue();
    }

    private static String string(PlutusData data, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(
                            rawBytes(data, field)))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    field + " must be canonical UTF-8", failure);
        }
    }

    private static java.math.BigInteger integer(
            PlutusData data,
            String field
    ) {
        if (!(data instanceof BigIntPlutusData value)) {
            throw new IllegalArgumentException(
                    field + " must be a Plutus integer");
        }
        return value.getValue();
    }

    private static String text(
            String value,
            String field,
            int maximum
    ) {
        String normalized =
                Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length
                > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
