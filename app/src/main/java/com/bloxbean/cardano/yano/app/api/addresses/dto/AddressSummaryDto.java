package com.bloxbean.cardano.yano.app.api.addresses.dto;

import com.bloxbean.cardano.yano.app.api.utxos.dto.AmountDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Blockfrost-compatible response of GET /addresses/{address}. */
public record AddressSummaryDto(
        @JsonProperty("address") String address,
        @JsonProperty("amount") List<AmountDto> amount,
        @JsonProperty("stake_address") String stakeAddress,
        @JsonProperty("type") String type,
        @JsonProperty("script") boolean script) {
}
