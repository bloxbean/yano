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
 * Inline datum carried from refundable staging into an accepted vault output.
 * Wire shape: {@code Constr(0, [2, chain, owner, nonce, stagingTx, index,
 * deadline, depositor, authorizationProfile, keyEpoch, publicKey])}.
 */
public record EutxoVaultDatum(
        int abiVersion,
        String chainId,
        String l2Address,
        byte[] depositNonce,
        EutxoOutpoint stagingOutpoint,
        long refundDeadline,
        byte[] depositorKeyHash,
        EutxoL2KeyBinding l2KeyBinding
) {
    public static final int ABI_VERSION = 2;

    public EutxoVaultDatum(
            int abiVersion,
            String chainId,
            String l2Address,
            byte[] depositNonce,
            EutxoOutpoint stagingOutpoint,
            long refundDeadline
    ) {
        this(abiVersion, chainId, l2Address, depositNonce,
                stagingOutpoint, refundDeadline,
                new byte[28], EutxoL2KeyBinding.none());
    }

    public EutxoVaultDatum {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException("unsupported EUTxO vault datum ABI");
        }
        chainId = text(chainId, "chainId", 128);
        l2Address = text(l2Address, "l2Address", 256);
        depositNonce = Objects.requireNonNull(depositNonce, "depositNonce").clone();
        if (depositNonce.length != 32) {
            throw new IllegalArgumentException("deposit nonce must contain 32 bytes");
        }
        Objects.requireNonNull(stagingOutpoint, "stagingOutpoint");
        depositorKeyHash = Objects.requireNonNull(
                depositorKeyHash, "depositorKeyHash").clone();
        if (depositorKeyHash.length != 28) {
            throw new IllegalArgumentException(
                    "depositor key hash must contain 28 bytes");
        }
        l2KeyBinding = Objects.requireNonNull(
                l2KeyBinding, "l2KeyBinding");
        if (refundDeadline < 0) {
            throw new IllegalArgumentException("refund deadline cannot be negative");
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
                        bytes(chainId),
                        bytes(l2Address),
                        BytesPlutusData.of(depositNonce),
                        BytesPlutusData.of(java.util.HexFormat.of()
                                .parseHex(stagingOutpoint.transactionId())),
                        BigIntPlutusData.of(stagingOutpoint.index()),
                        BigIntPlutusData.of(refundDeadline),
                        BytesPlutusData.of(depositorKeyHash),
                        bytes(l2KeyBinding.authorizationProfile()),
                        BigIntPlutusData.of(l2KeyBinding.keyEpoch()),
                        BytesPlutusData.of(l2KeyBinding.publicKey())))
                .build()
                .serializeToBytes();
    }

    public static EutxoVaultDatum decode(byte[] cbor) {
        Objects.requireNonNull(cbor, "cbor");
        if (cbor.length == 0 || cbor.length > EutxoProfile.V1.maxOutputCborBytes()) {
            throw new IllegalArgumentException("vault datum exceeds the EUTxO datum bound");
        }
        try {
            PlutusData decoded = PlutusData.deserialize(cbor);
            if (!(decoded instanceof ConstrPlutusData constructor)
                    || constructor.getAlternative() != 0) {
                throw new IllegalArgumentException("vault datum must use constructor zero");
            }
            List<PlutusData> fields = constructor.getData().getPlutusDataList();
            if (fields.size() != 11) {
                throw new IllegalArgumentException("vault datum must contain eleven fields");
            }
            return new EutxoVaultDatum(
                    integer(fields.get(0), "ABI version").intValueExact(),
                    string(fields.get(1), "chain id"),
                    string(fields.get(2), "L2 address"),
                    bytes(fields.get(3), "deposit nonce"),
                    new EutxoOutpoint(
                            java.util.HexFormat.of().formatHex(
                                    bytes(fields.get(4), "staging transaction id")),
                            integer(fields.get(5), "staging output index").intValueExact()),
                    integer(fields.get(6), "refund deadline").longValueExact(),
                    bytes(fields.get(7), "depositor key hash"),
                    new EutxoL2KeyBinding(
                            string(fields.get(8), "authorization profile"),
                            integer(fields.get(9), "L2 key epoch").longValueExact(),
                            bytes(fields.get(10), "L2 public key")));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid EUTxO vault datum", failure);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoVaultDatum datum
                && abiVersion == datum.abiVersion
                && chainId.equals(datum.chainId)
                && l2Address.equals(datum.l2Address)
                && java.util.Arrays.equals(depositNonce, datum.depositNonce)
                && stagingOutpoint.equals(datum.stagingOutpoint)
                && refundDeadline == datum.refundDeadline
                && java.util.Arrays.equals(
                depositorKeyHash, datum.depositorKeyHash)
                && l2KeyBinding.equals(datum.l2KeyBinding);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, l2Address, stagingOutpoint,
                refundDeadline, l2KeyBinding);
        result = 31 * result + java.util.Arrays.hashCode(depositNonce);
        return 31 * result + java.util.Arrays.hashCode(depositorKeyHash);
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

    private static String text(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maxLength + " characters");
        }
        return normalized;
    }

}
