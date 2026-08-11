package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadCaseTest {
    @Test
    void payloadCaseUsesDeepImmutableValueSemantics() {
        byte[] input = {1, 2, 3};
        PayloadCase first = new PayloadCase(
                "malformed", input, ConnectorErrorCode.INVALID_PAYLOAD);
        PayloadCase equal = new PayloadCase(
                "malformed", new byte[]{1, 2, 3}, ConnectorErrorCode.INVALID_PAYLOAD);
        Map<PayloadCase, String> indexed = new HashMap<>();
        indexed.put(first, "expected");

        input[0] = 9;
        byte[] returned = first.payload();
        returned[1] = 9;

        assertThat(first).isEqualTo(equal);
        assertThat(first.hashCode()).isEqualTo(equal.hashCode());
        assertThat(indexed).containsEntry(equal, "expected");
        assertThat(first.payload()).containsExactly(1, 2, 3);
    }
}
