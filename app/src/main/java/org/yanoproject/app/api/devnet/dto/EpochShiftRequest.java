package org.yanoproject.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EpochShiftRequest(
    @JsonProperty("epochs") int epochs
) {}
