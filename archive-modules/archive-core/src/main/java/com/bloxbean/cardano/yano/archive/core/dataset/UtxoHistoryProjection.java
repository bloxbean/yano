package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.EnumMap;
import java.util.Map;

/**
 * Enabled UTXO-history row families and their first eligible block.
 *
 * <p>A missing table is disabled. The activation cutoff sits beside the switch so a
 * newly enabled table joins the projection from that block forward, and its absence from
 * earlier blocks is a recorded fact rather than a gap to be filled later. Nothing
 * backfills: the projection records each block once, as it is applied.</p>
 */
public record UtxoHistoryProjection(Map<Table, Long> activationBlocks) {
    public UtxoHistoryProjection {
        EnumMap<Table, Long> copy = new EnumMap<>(Table.class);
        copy.putAll(activationBlocks);
        copy.forEach((table, block) -> {
            if (block < 0) throw new IllegalArgumentException("negative activation block for " + table);
        });
        activationBlocks = Map.copyOf(copy);
    }

    public static UtxoHistoryProjection all() {
        EnumMap<Table, Long> starts = new EnumMap<>(Table.class);
        for (Table table : Table.values()) starts.put(table, 0L);
        return new UtxoHistoryProjection(starts);
    }

    public boolean includes(Table table, long blockNumber) {
        Long activation = activationBlocks.get(table);
        return activation != null && blockNumber >= activation;
    }

    public boolean enabled(Table table) {
        return activationBlocks.containsKey(table);
    }

    public enum Table {
        TRANSACTION_OUTPUTS("transaction_outputs"),
        TRANSACTION_OUTPUT_ASSETS("transaction_output_assets"),
        TRANSACTION_INPUTS("transaction_inputs"),
        TRANSACTION_DATUMS("transaction_datums"),
        TRANSACTION_REDEEMERS("transaction_redeemers");

        private final String physicalName;

        Table(String physicalName) {
            this.physicalName = physicalName;
        }

        public String physicalName() {
            return physicalName;
        }

        public String configName() {
            return physicalName.replace('_', '-');
        }
    }
}
