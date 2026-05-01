package com.bloxbean.cardano.yano.wallet.core.tx;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

public record PendingTransaction(
        String txHash,
        String signedCborHex,
        long createdAtEpochMillis,
        Long submittedAtEpochMillis,
        PendingTransactionStatus status,
        String walletId,
        String networkId,
        BigInteger lovelace,
        BigInteger fee,
        String fromAddress,
        String toAddress,
        Long ttlSlot,
        Long confirmedSlot,
        Long confirmedBlock,
        String confirmedBlockHash,
        String lastError) {

    public PendingTransaction {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("txHash is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (walletId == null || walletId.isBlank()) {
            throw new IllegalArgumentException("walletId is required");
        }
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId is required");
        }
        lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
        fee = fee == null ? BigInteger.ZERO : fee;
    }

    public static PendingTransaction fromDraft(QuickAdaTxDraft draft, String walletId, String networkId) {
        Objects.requireNonNull(draft, "draft is required");
        return new PendingTransaction(
                draft.txHash(),
                draft.cborHex(),
                Instant.now().toEpochMilli(),
                null,
                PendingTransactionStatus.DRAFTED,
                walletId,
                networkId,
                draft.lovelace(),
                draft.fee(),
                draft.fromAddress(),
                draft.toAddress(),
                draft.ttlSlot(),
                null,
                null,
                null,
                null);
    }

    public PendingTransaction markPending(long submittedAtEpochMillis) {
        return new PendingTransaction(
                txHash,
                signedCborHex,
                createdAtEpochMillis,
                submittedAtEpochMillis,
                PendingTransactionStatus.PENDING,
                walletId,
                networkId,
                lovelace,
                fee,
                fromAddress,
                toAddress,
                ttlSlot,
                null,
                null,
                null,
                null);
    }

    public PendingTransaction markConfirmed(long slot, long blockNumber, String blockHash) {
        return new PendingTransaction(
                txHash,
                signedCborHex,
                createdAtEpochMillis,
                submittedAtEpochMillis,
                PendingTransactionStatus.CONFIRMED,
                walletId,
                networkId,
                lovelace,
                fee,
                fromAddress,
                toAddress,
                ttlSlot,
                slot,
                blockNumber,
                blockHash,
                null);
    }

    public PendingTransaction markRolledBack(long targetSlot) {
        return new PendingTransaction(
                txHash,
                signedCborHex,
                createdAtEpochMillis,
                submittedAtEpochMillis,
                PendingTransactionStatus.ROLLED_BACK,
                walletId,
                networkId,
                lovelace,
                fee,
                fromAddress,
                toAddress,
                ttlSlot,
                null,
                null,
                null,
                "Rolled back after target slot " + targetSlot);
    }

    public PendingTransaction markExpired(long currentSlot) {
        return new PendingTransaction(
                txHash,
                signedCborHex,
                createdAtEpochMillis,
                submittedAtEpochMillis,
                PendingTransactionStatus.EXPIRED,
                walletId,
                networkId,
                lovelace,
                fee,
                fromAddress,
                toAddress,
                ttlSlot,
                confirmedSlot,
                confirmedBlock,
                confirmedBlockHash,
                "Expired at slot " + currentSlot);
    }

    public PendingTransaction markFailed(String errorMessage) {
        return new PendingTransaction(
                txHash,
                signedCborHex,
                createdAtEpochMillis,
                submittedAtEpochMillis,
                PendingTransactionStatus.FAILED,
                walletId,
                networkId,
                lovelace,
                fee,
                fromAddress,
                toAddress,
                ttlSlot,
                confirmedSlot,
                confirmedBlock,
                confirmedBlockHash,
                errorMessage);
    }

    public boolean awaitsConfirmation() {
        return status == PendingTransactionStatus.SUBMITTED
                || status == PendingTransactionStatus.PENDING
                || status == PendingTransactionStatus.ROLLED_BACK;
    }

    public boolean confirmedAfter(long slot) {
        return status == PendingTransactionStatus.CONFIRMED
                && confirmedSlot != null
                && confirmedSlot > slot;
    }

    public boolean isExpiredAt(long currentSlot) {
        return awaitsConfirmation() && ttlSlot != null && ttlSlot > 0 && currentSlot > ttlSlot;
    }
}
