package org.yanoproject.api.utxo.index;

/** Capture union is frozen at startup; transaction subjects are optional work. */
public record IndexRequirements(boolean consumedAddresses, boolean transactionSubjects,
                                boolean requiresFullUtxoStorage) {
    public static final IndexRequirements NONE = new IndexRequirements(false, false, false);
}
