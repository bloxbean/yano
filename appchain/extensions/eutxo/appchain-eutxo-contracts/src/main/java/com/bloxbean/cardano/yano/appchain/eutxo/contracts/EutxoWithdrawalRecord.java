package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Objects;

/** Consensus status of one irrevocable withdrawal claim. */
public record EutxoWithdrawalRecord(
        EutxoWithdrawalClaim claim,
        Status status,
        String settlementTransactionId,
        long confirmedSlot,
        byte[] confirmedBlockHash,
        long updatedHeight
) {
    public enum Status {
        PENDING,
        CONFIRMED
    }

    public EutxoWithdrawalRecord {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(status, "status");
        settlementTransactionId = Objects.requireNonNullElse(
                settlementTransactionId, "").trim();
        confirmedBlockHash = Objects.requireNonNullElse(
                confirmedBlockHash, new byte[0]).clone();
        if (updatedHeight < 0 || confirmedSlot < 0) {
            throw new IllegalArgumentException("withdrawal heights and slots cannot be negative");
        }
        if (status == Status.PENDING
                && (!settlementTransactionId.isEmpty()
                || confirmedSlot != 0 || confirmedBlockHash.length != 0)) {
            throw new IllegalArgumentException(
                    "pending withdrawal cannot contain L1 confirmation");
        }
        if (status == Status.CONFIRMED
                && (settlementTransactionId.length() != 64
                || confirmedBlockHash.length != 32)) {
            throw new IllegalArgumentException(
                    "confirmed withdrawal requires canonical L1 identity");
        }
    }

    public static EutxoWithdrawalRecord pending(
            EutxoWithdrawalClaim claim,
            long height
    ) {
        return new EutxoWithdrawalRecord(
                claim, Status.PENDING, "", 0, new byte[0], height);
    }

    public EutxoWithdrawalRecord confirm(
            String transactionId,
            long slot,
            byte[] blockHash,
            long height
    ) {
        if (status == Status.CONFIRMED) {
            if (!settlementTransactionId.equals(transactionId)
                    || confirmedSlot != slot
                    || !java.util.Arrays.equals(confirmedBlockHash, blockHash)) {
                throw new IllegalStateException(
                        "withdrawal is already bound to another L1 settlement");
            }
            return this;
        }
        return new EutxoWithdrawalRecord(
                claim, Status.CONFIRMED, transactionId, slot, blockHash, height);
    }

    @Override
    public byte[] confirmedBlockHash() {
        return confirmedBlockHash.clone();
    }

    public byte[] encode() {
        return EutxoCbor.encodeWithdrawalRecord(this);
    }

    public static EutxoWithdrawalRecord decode(byte[] bytes) {
        return EutxoCbor.decodeWithdrawalRecord(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoWithdrawalRecord record
                && claim.equals(record.claim)
                && status == record.status
                && settlementTransactionId.equals(record.settlementTransactionId)
                && confirmedSlot == record.confirmedSlot
                && java.util.Arrays.equals(confirmedBlockHash, record.confirmedBlockHash)
                && updatedHeight == record.updatedHeight;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                claim, status, settlementTransactionId, confirmedSlot, updatedHeight);
        return 31 * result + java.util.Arrays.hashCode(confirmedBlockHash);
    }
}
