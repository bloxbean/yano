package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.LinkedHashMap;
import java.util.Map;

/** Subject scopes materialized by the address-transaction projection. */
public record AddressTransactionSubjects(boolean address, boolean paymentCredential,
                                         boolean stakeCredential) {
    public static final String ADDRESS = "address";
    public static final String PAYMENT_CREDENTIAL = "payment_credential";
    public static final String STAKE_CREDENTIAL = "stake_credential";

    public AddressTransactionSubjects {
        if (!address && !paymentCredential && !stakeCredential) {
            throw new IllegalArgumentException("address-transactions requires at least one enabled subject");
        }
    }

    public static AddressTransactionSubjects all() {
        return new AddressTransactionSubjects(true, true, true);
    }

    public static AddressTransactionSubjects fromConfig(Map<String, Boolean> subjects) {
        Map<String, Boolean> selected = subjects == null ? Map.of() : subjects;
        return new AddressTransactionSubjects(
                selected.getOrDefault("address", true),
                selected.getOrDefault("payment-credential", true),
                selected.getOrDefault("stake-credential", true));
    }

    public boolean includes(String subjectType) {
        return switch (subjectType) {
            case ADDRESS -> address;
            case PAYMENT_CREDENTIAL -> paymentCredential;
            case STAKE_CREDENTIAL -> stakeCredential;
            default -> false;
        };
    }

    public Map<String, Boolean> asConfigMap() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("payment-credential", paymentCredential);
        result.put("stake-credential", stakeCredential);
        return Map.copyOf(result);
    }

    /** Stable persisted value used to prevent silent historical completeness changes. */
    public String fingerprint() {
        StringBuilder result = new StringBuilder();
        if (address) result.append(ADDRESS);
        if (paymentCredential) append(result, PAYMENT_CREDENTIAL);
        if (stakeCredential) append(result, STAKE_CREDENTIAL);
        return result.toString();
    }

    private static void append(StringBuilder result, String value) {
        if (!result.isEmpty()) result.append(',');
        result.append(value);
    }
}
