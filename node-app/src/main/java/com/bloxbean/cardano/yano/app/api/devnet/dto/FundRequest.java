package com.bloxbean.cardano.yano.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record FundRequest(
    @JsonProperty("address") String address,
    @JsonProperty("ada") BigDecimal ada
) {}
