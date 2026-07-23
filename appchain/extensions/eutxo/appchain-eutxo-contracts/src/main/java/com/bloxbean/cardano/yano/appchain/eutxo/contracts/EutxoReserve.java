package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.math.BigInteger;
import java.util.Objects;

/** Atomic per-asset bridge reserve counters committed under the Yano MPF root. */
public record EutxoReserve(
        String assetId,
        BigInteger stableVault,
        BigInteger spendableMirrored,
        BigInteger pendingWithdrawals,
        BigInteger confirmedWithdrawals
) {
    public static final String LOVELACE = "lovelace";

    public EutxoReserve {
        assetId = Objects.requireNonNull(assetId, "assetId").trim();
        if (assetId.isEmpty() || assetId.length() > 120) {
            throw new IllegalArgumentException("asset id must contain 1-120 characters");
        }
        stableVault = nonNegative(stableVault, "stableVault");
        spendableMirrored = nonNegative(spendableMirrored, "spendableMirrored");
        pendingWithdrawals = nonNegative(pendingWithdrawals, "pendingWithdrawals");
        confirmedWithdrawals = nonNegative(confirmedWithdrawals, "confirmedWithdrawals");
        requireInvariant(
                stableVault, spendableMirrored,
                pendingWithdrawals, confirmedWithdrawals);
    }

    public static EutxoReserve empty(String assetId) {
        return new EutxoReserve(
                assetId, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO);
    }

    public EutxoReserve credit(BigInteger quantity) {
        BigInteger amount = positive(quantity, "deposit quantity");
        return new EutxoReserve(
                assetId,
                stableVault.add(amount),
                spendableMirrored.add(amount),
                pendingWithdrawals,
                confirmedWithdrawals);
    }

    public void requireInvariant() {
        requireInvariant(
                stableVault, spendableMirrored,
                pendingWithdrawals, confirmedWithdrawals);
    }

    private static void requireInvariant(
            BigInteger stableVault,
            BigInteger spendableMirrored,
            BigInteger pendingWithdrawals,
            BigInteger confirmedWithdrawals
    ) {
        BigInteger liabilities = spendableMirrored.add(pendingWithdrawals);
        if (!stableVault.equals(liabilities)) {
            throw new IllegalArgumentException(
                    "stable vault inventory must equal mirrored bridge liabilities");
        }
    }

    public byte[] encode() {
        return EutxoCbor.encodeReserve(this);
    }

    public static EutxoReserve decode(byte[] bytes) {
        return EutxoCbor.decodeReserve(bytes);
    }

    private static BigInteger nonNegative(BigInteger value, String field) {
        BigInteger checked = Objects.requireNonNull(value, field);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return checked;
    }

    private static BigInteger positive(BigInteger value, String field) {
        BigInteger checked = nonNegative(value, field);
        if (checked.signum() == 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return checked;
    }
}
