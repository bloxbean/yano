package com.bloxbean.cardano.yano.app.api.network.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkDto(
        @JsonProperty("supply") SupplyDto supply,
        @JsonProperty("stake") StakeDto stake
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupplyDto(
            @JsonProperty("max") String max,
            @JsonProperty("total") String total,
            @JsonProperty("treasury") String treasury,
            @JsonProperty("reserves") String reserves
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StakeDto(
            @JsonProperty("active") String active
    ) {}
}
