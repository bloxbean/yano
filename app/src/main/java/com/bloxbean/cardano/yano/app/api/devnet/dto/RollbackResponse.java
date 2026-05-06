package com.bloxbean.cardano.yano.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RollbackResponse(
    @JsonProperty("message") String message,
    @JsonProperty("slot") long slot,
    @JsonProperty("block_number") long blockNumber
) {}
