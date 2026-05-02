package com.bloxbean.cardano.yano.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EpochShiftRequest(
    @JsonProperty("epochs") int epochs
) {}
