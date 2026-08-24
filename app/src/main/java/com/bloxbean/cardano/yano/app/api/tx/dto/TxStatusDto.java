package com.bloxbean.cardano.yano.app.api.tx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wallet-facing transaction status (ADR-033 M2): distinguishes a transaction
 * waiting in this node's mempool from one already in a block, so clients can
 * poll one endpoint instead of interpreting 404s.
 *
 * <p>Semantics: {@code confirmations} is depth-style (0 = in the tip block),
 * matching this API's block responses. When archive history is enabled,
 * {@code unknown} is returned only after complete transaction coverage was
 * searched; an uncovered range is reported explicitly as unavailable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxStatusDto(
        @JsonProperty("tx_hash") String txHash,
        @JsonProperty("status") String status,
        @JsonProperty("block_height") Long blockHeight,
        @JsonProperty("block_hash") String blockHash,
        @JsonProperty("slot") Long slot,
        @JsonProperty("block_time") Long blockTime,
        @JsonProperty("confirmations") Long confirmations) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_BLOCK = "in_block";
    public static final String STATUS_UNKNOWN = "unknown";

    public static TxStatusDto pending(String txHash) {
        return new TxStatusDto(txHash, STATUS_PENDING, null, null, null, null, null);
    }

    public static TxStatusDto unknown(String txHash) {
        return new TxStatusDto(txHash, STATUS_UNKNOWN, null, null, null, null, null);
    }

    public static TxStatusDto inBlock(String txHash, long blockHeight, String blockHash,
                                      long slot, long blockTime, long confirmations) {
        return new TxStatusDto(txHash, STATUS_IN_BLOCK, blockHeight, blockHash, slot, blockTime, confirmations);
    }
}
