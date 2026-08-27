package com.bloxbean.cardano.yano.app.api.utxos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Amount DTO matching Yaci Store's response format.
 * Lovelace is represented as unit="lovelace", native assets as unit=policyId+assetName.
 */
public record AmountDto(
        @JsonProperty("unit") String unit,
        @JsonProperty("quantity") String quantity
) {}
