package com.bloxbean.cardano.yano.app.api.utxos.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmountDtoTest {
    @Test
    void serializesQuantityAsLosslessBlockfrostStyleString() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new AmountDto("lovelace", "18446744073709551615"));
        assertThat(json).contains("\"quantity\":\"18446744073709551615\"");
    }
}
