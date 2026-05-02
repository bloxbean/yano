package com.bloxbean.cardano.yano.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TimeAdvanceRequest(
    @JsonProperty("slots") Integer slots,
    @JsonProperty("seconds") Integer seconds,
    @JsonProperty("epochs") Integer epochs
) {}
