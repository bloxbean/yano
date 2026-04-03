package com.bloxbean.cardano.yano.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SnapshotRequest(
    @JsonProperty("name") String name
) {}
