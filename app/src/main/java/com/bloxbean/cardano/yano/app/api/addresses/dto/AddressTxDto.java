package com.bloxbean.cardano.yano.app.api.addresses.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Blockfrost-compatible entry of GET /addresses/{address}/transactions. */
public record AddressTxDto(
        @JsonProperty("tx_hash") String txHash,
        @JsonProperty("tx_index") int txIndex,
        @JsonProperty("block_height") long blockHeight,
        @JsonProperty("block_time") long blockTime,
        @JsonProperty("slot") long slot) {
}
