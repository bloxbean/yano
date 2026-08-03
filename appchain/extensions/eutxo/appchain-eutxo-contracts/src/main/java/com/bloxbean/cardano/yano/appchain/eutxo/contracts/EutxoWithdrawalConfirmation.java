package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.math.BigInteger;
import java.util.Objects;

/** Exact stable L1 settlement evidence emitted by the bridge observer. */
public record EutxoWithdrawalConfirmation(
        int abiVersion,
        String chainId,
        long bridgeEpoch,
        String claimId,
        String settlementTransactionId,
        int payoutIndex,
        String destinationAddress,
        BigInteger lovelace,
        EutxoOutpoint continuingVaultOutpoint,
        BigInteger continuingVaultLovelace,
        long l1Slot,
        byte[] l1BlockHash
) {
    public static final int ABI_VERSION = 1;

    public EutxoWithdrawalConfirmation {
        if (abiVersion != ABI_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported EUTxO withdrawal confirmation ABI");
        }
        chainId = text(chainId, "chainId", 128);
        if (bridgeEpoch < 0 || payoutIndex < 0 || l1Slot < 0) {
            throw new IllegalArgumentException(
                    "bridge epoch, payout index, and L1 slot cannot be negative");
        }
        claimId = canonicalHash(claimId, "claimId");
        settlementTransactionId = canonicalHash(
                settlementTransactionId, "settlementTransactionId");
        destinationAddress = text(destinationAddress, "destinationAddress", 256);
        lovelace = positive(lovelace, "lovelace");
        Objects.requireNonNull(continuingVaultOutpoint, "continuingVaultOutpoint");
        continuingVaultLovelace = nonNegative(
                continuingVaultLovelace, "continuingVaultLovelace");
        l1BlockHash = Objects.requireNonNull(l1BlockHash, "l1BlockHash").clone();
        if (l1BlockHash.length != 32) {
            throw new IllegalArgumentException("L1 block hash must contain 32 bytes");
        }
    }

    @Override
    public byte[] l1BlockHash() {
        return l1BlockHash.clone();
    }

    public byte[] encode() {
        return EutxoCbor.encodeWithdrawalConfirmation(this);
    }

    public static EutxoWithdrawalConfirmation decode(byte[] bytes) {
        return EutxoCbor.decodeWithdrawalConfirmation(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoWithdrawalConfirmation confirmation
                && abiVersion == confirmation.abiVersion
                && chainId.equals(confirmation.chainId)
                && bridgeEpoch == confirmation.bridgeEpoch
                && claimId.equals(confirmation.claimId)
                && settlementTransactionId.equals(confirmation.settlementTransactionId)
                && payoutIndex == confirmation.payoutIndex
                && destinationAddress.equals(confirmation.destinationAddress)
                && lovelace.equals(confirmation.lovelace)
                && continuingVaultOutpoint.equals(confirmation.continuingVaultOutpoint)
                && continuingVaultLovelace.equals(confirmation.continuingVaultLovelace)
                && l1Slot == confirmation.l1Slot
                && java.util.Arrays.equals(l1BlockHash, confirmation.l1BlockHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                abiVersion, chainId, bridgeEpoch, claimId, settlementTransactionId,
                payoutIndex, destinationAddress, lovelace, continuingVaultOutpoint,
                continuingVaultLovelace, l1Slot);
        return 31 * result + java.util.Arrays.hashCode(l1BlockHash);
    }

    private static String canonicalHash(String value, String field) {
        String hash = text(value, field, 64);
        if (hash.length() != 64
                || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be 32-byte lowercase hex");
        }
        try {
            java.util.HexFormat.of().parseHex(hash);
            return hash;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    field + " must be 32-byte lowercase hex", failure);
        }
    }

    private static String text(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }

    private static BigInteger positive(BigInteger value, String field) {
        BigInteger checked = nonNegative(value, field);
        if (checked.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }

    private static BigInteger nonNegative(BigInteger value, String field) {
        BigInteger checked = Objects.requireNonNull(value, field);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return checked;
    }
}
