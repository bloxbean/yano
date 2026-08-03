package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Objects;

/** Bounded deterministic transaction-attempt outcome. */
public record EutxoReceipt(
        Status status,
        String transactionId,
        byte[] appMessageId,
        long appHeight,
        int ordinal,
        long l1Slot,
        String code,
        String detail
) {
    public enum Status {
        ACCEPTED,
        REJECTED
    }

    public EutxoReceipt {
        Objects.requireNonNull(status, "status");
        transactionId = transactionId == null || transactionId.isBlank()
                ? "" : new EutxoOutpoint(transactionId, 0).transactionId();
        appMessageId = Objects.requireNonNull(appMessageId, "appMessageId").clone();
        if (appMessageId.length != 32) {
            throw new IllegalArgumentException("app message id must contain 32 bytes");
        }
        if (appHeight < 1 || ordinal < 0 || l1Slot < 0) {
            throw new IllegalArgumentException("receipt position must be non-negative");
        }
        code = bounded(code, "code", 64);
        detail = bounded(detail, "detail", 192);
    }

    @Override
    public byte[] appMessageId() {
        return appMessageId.clone();
    }

    public byte[] encode() {
        return EutxoCbor.encodeReceipt(this);
    }

    public static EutxoReceipt decode(byte[] bytes) {
        return EutxoCbor.decodeReceipt(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoReceipt receipt
                && status == receipt.status
                && transactionId.equals(receipt.transactionId)
                && java.util.Arrays.equals(appMessageId, receipt.appMessageId)
                && appHeight == receipt.appHeight
                && ordinal == receipt.ordinal
                && l1Slot == receipt.l1Slot
                && code.equals(receipt.code)
                && detail.equals(receipt.detail);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                status, transactionId, appHeight, ordinal, l1Slot, code, detail);
        return 31 * result + java.util.Arrays.hashCode(appMessageId);
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }
}
