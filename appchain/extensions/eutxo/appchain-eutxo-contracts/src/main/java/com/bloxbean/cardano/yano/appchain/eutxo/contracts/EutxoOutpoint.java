package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Locale;
import java.util.Objects;

/** Canonical Cardano transaction output reference. */
public record EutxoOutpoint(String transactionId, int index)
        implements Comparable<EutxoOutpoint> {

    public EutxoOutpoint {
        transactionId = Objects.requireNonNull(transactionId, "transactionId")
                .trim().toLowerCase(Locale.ROOT);
        if (!transactionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "transactionId must be 32-byte lowercase hexadecimal");
        }
        if (index < 0 || index > 65_535) {
            throw new IllegalArgumentException("output index must be between 0 and 65535");
        }
    }

    public static EutxoOutpoint parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.lastIndexOf('#');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("outpoint must use <tx-id>#<index>");
        }
        try {
            return new EutxoOutpoint(
                    value.substring(0, separator),
                    Integer.parseInt(value.substring(separator + 1)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("outpoint index must be a decimal integer", failure);
        }
    }

    @Override
    public String toString() {
        return transactionId + "#" + index;
    }

    @Override
    public int compareTo(EutxoOutpoint other) {
        int transactionOrder = transactionId.compareTo(other.transactionId);
        return transactionOrder != 0 ? transactionOrder : Integer.compare(index, other.index);
    }
}
