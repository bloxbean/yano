package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Canonical Plutus claim committed under the app-chain MPF root.
 *
 * <p>The ordinary withdrawal record remains the operator-facing lifecycle
 * record. The MPF stores a compact deterministic digest; the proof redeemer
 * carries this Plutus-native preimage so an L1 validator can recompute the
 * digest and independently compare the payout address and amount.</p>
 */
public record EutxoWithdrawalCommitment(
        int abiVersion,
        byte[] chainId,
        long bridgeEpoch,
        long settlementSequence,
        byte[] claimId,
        byte[] destinationPlutusData,
        BigInteger lovelace
) {
    public static final int ABI_VERSION = 1;
    public static final int PLUTUS_CONSTR = 3;
    public static final byte[] DOMAIN =
            "yano-eutxo-withdrawal-v1".getBytes(StandardCharsets.US_ASCII);

    public EutxoWithdrawalCommitment {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO withdrawal commitment ABI");
        }
        chainId = Objects.requireNonNull(chainId, "chainId").clone();
        if (chainId.length < 1 || chainId.length > 128) {
            throw new IllegalArgumentException(
                    "commitment chain id must contain 1-128 bytes");
        }
        if (bridgeEpoch < 0 || settlementSequence < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch and settlement sequence cannot be negative");
        }
        claimId = exact(claimId, 32, "claim id");
        destinationPlutusData = Objects.requireNonNull(
                destinationPlutusData, "destinationPlutusData").clone();
        validatePlutusAddressData(destinationPlutusData);
        lovelace = Objects.requireNonNull(lovelace, "lovelace");
        if (lovelace.signum() <= 0
                || lovelace.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "withdrawal commitment lovelace must fit a positive signed 64-bit integer");
        }
    }

    public static EutxoWithdrawalCommitment fromClaim(
            EutxoWithdrawalClaim claim
    ) {
        Objects.requireNonNull(claim, "claim");
        return new EutxoWithdrawalCommitment(
                ABI_VERSION,
                claim.chainId().getBytes(StandardCharsets.UTF_8),
                claim.bridgeEpoch(),
                claim.settlementSequence(),
                HexFormat.of().parseHex(claim.claimId()),
                plutusAddress(claim.destinationAddress()).serializeToBytes(),
                claim.lovelace());
    }

    public PlutusData toPlutusData() {
        try {
            return ConstrPlutusData.of(
                    PLUTUS_CONSTR,
                    BigIntPlutusData.of(abiVersion),
                    BytesPlutusData.of(chainId),
                    BigIntPlutusData.of(bridgeEpoch),
                    BigIntPlutusData.of(settlementSequence),
                    BytesPlutusData.of(claimId),
                    PlutusData.deserialize(destinationPlutusData),
                    BigIntPlutusData.of(lovelace));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "cannot encode withdrawal commitment", failure);
        }
    }

    public byte[] encode() {
        return digest();
    }

    public byte[] digest() {
        byte[] destination = destinationFingerprint(destinationPlutusData);
        ByteBuffer fields = ByteBuffer.allocate(
                DOMAIN.length + 32 + Long.BYTES + Long.BYTES
                        + 32 + 32 + Long.BYTES);
        fields.put(DOMAIN);
        fields.put(Blake2bUtil.blake2bHash256(chainId));
        fields.putLong(bridgeEpoch);
        fields.putLong(settlementSequence);
        fields.put(claimId);
        fields.put(destination);
        fields.putLong(lovelace.longValueExact());
        return Blake2bUtil.blake2bHash256(fields.array());
    }

    public boolean matchesDestination(String bech32Address) {
        return Arrays.equals(
                destinationPlutusData,
                plutusAddressData(bech32Address));
    }

    public static byte[] plutusAddressData(String bech32Address) {
        return plutusAddress(bech32Address).serializeToBytes();
    }

    @Override
    public byte[] chainId() {
        return chainId.clone();
    }

    @Override
    public byte[] claimId() {
        return claimId.clone();
    }

    @Override
    public byte[] destinationPlutusData() {
        return destinationPlutusData.clone();
    }

    /**
     * Convert the Shelley address forms supported by the v1 bridge profile to
     * the ledger's Plutus Address representation. Network identity is enforced
     * by the Cardano ledger and is not present in Plutus Address data.
     */
    static PlutusData plutusAddress(String bech32Address) {
        Objects.requireNonNull(bech32Address, "bech32Address");
        byte[] raw;
        try {
            raw = Bech32.decode(bech32Address).data;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "withdrawal destination must be a Shelley bech32 address",
                    failure);
        }
        if (raw.length < 29) {
            throw new IllegalArgumentException(
                    "withdrawal destination is not a supported Shelley address");
        }
        int type = (raw[0] & 0xF0) >>> 4;
        boolean paymentScript;
        PlutusData stakeOption;
        switch (type) {
            case 0, 1, 2, 3 -> {
                if (raw.length != 57) {
                    throw new IllegalArgumentException(
                            "base withdrawal address must contain 57 bytes");
                }
                paymentScript = type == 1 || type == 3;
                boolean stakeScript = type == 2 || type == 3;
                PlutusData stakeCredential = credential(
                        Arrays.copyOfRange(raw, 29, 57), stakeScript);
                PlutusData inlineStake =
                        ConstrPlutusData.of(0, stakeCredential);
                stakeOption = ConstrPlutusData.of(0, inlineStake);
            }
            case 6, 7 -> {
                if (raw.length != 29) {
                    throw new IllegalArgumentException(
                            "enterprise withdrawal address must contain 29 bytes");
                }
                paymentScript = type == 7;
                stakeOption = ConstrPlutusData.of(1);
            }
            default -> throw new IllegalArgumentException(
                    "v1 proof withdrawal supports base and enterprise addresses");
        }
        PlutusData payment = credential(
                Arrays.copyOfRange(raw, 1, 29), paymentScript);
        return ConstrPlutusData.of(0, payment, stakeOption);
    }

    private static PlutusData credential(byte[] hash, boolean script) {
        return ConstrPlutusData.of(
                script ? 1 : 0, BytesPlutusData.of(hash));
    }

    static void validatePlutusAddressData(byte[] encodedAddress) {
        Objects.requireNonNull(encodedAddress, "encodedAddress");
        try {
            PlutusData decoded = PlutusData.deserialize(encodedAddress);
            if (!(decoded instanceof ConstrPlutusData)
                    || !Arrays.equals(
                    encodedAddress, decoded.serializeToBytes())) {
                throw new IllegalArgumentException(
                        "destination must contain canonical Plutus address data");
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "destination must contain canonical Plutus address data",
                    failure);
        }
        destinationFingerprint(encodedAddress);
    }

    private static byte[] destinationFingerprint(byte[] encodedAddress) {
        try {
            ConstrPlutusData address =
                    (ConstrPlutusData) PlutusData.deserialize(
                            encodedAddress);
            List<PlutusData> addressFields =
                    address.getData().getPlutusDataList();
            if (address.getAlternative() != 0
                    || addressFields.size() != 2) {
                throw new IllegalArgumentException(
                        "destination has the wrong Plutus Address shape");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(58);
            appendCredential(output, (ConstrPlutusData) addressFields.get(0));
            ConstrPlutusData stake =
                    (ConstrPlutusData) addressFields.get(1);
            if (stake.getAlternative() == 1
                    && stake.getData().getPlutusDataList().isEmpty()) {
                output.write(0);
            } else if (stake.getAlternative() == 0
                    && stake.getData().getPlutusDataList().size() == 1) {
                ConstrPlutusData inline = (ConstrPlutusData)
                        stake.getData().getPlutusDataList().getFirst();
                if (inline.getAlternative() != 0
                        || inline.getData().getPlutusDataList().size() != 1) {
                    throw new IllegalArgumentException(
                            "pointer staking credentials are not in the v1 bridge profile");
                }
                output.write(1);
                appendCredential(output, (ConstrPlutusData)
                        inline.getData().getPlutusDataList().getFirst());
            } else {
                throw new IllegalArgumentException(
                        "destination has the wrong staking credential shape");
            }
            return Blake2bUtil.blake2bHash256(output.toByteArray());
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot fingerprint the Plutus destination", failure);
        }
    }

    private static void appendCredential(
            ByteArrayOutputStream output,
            ConstrPlutusData credential
    ) {
        if ((credential.getAlternative() != 0
                && credential.getAlternative() != 1)
                || credential.getData().getPlutusDataList().size() != 1
                || !(credential.getData().getPlutusDataList().getFirst()
                instanceof BytesPlutusData hash)
                || hash.getValue().length != 28) {
            throw new IllegalArgumentException(
                    "destination credential is outside the v1 bridge profile");
        }
        output.write((int) credential.getAlternative());
        output.writeBytes(hash.getValue());
    }

    private static byte[] exact(byte[] value, int length, String field) {
        byte[] copy = Objects.requireNonNull(value, field).clone();
        if (copy.length != length) {
            throw new IllegalArgumentException(
                    field + " must contain " + length + " bytes");
        }
        return copy;
    }
}
