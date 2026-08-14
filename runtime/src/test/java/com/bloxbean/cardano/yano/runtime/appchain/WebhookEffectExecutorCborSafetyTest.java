package com.bloxbean.cardano.yano.runtime.appchain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEffectExecutorCborSafetyTest {

    @Test
    void deeplyNestedCborFallsBackToOpaquePayloadWithoutRecursiveDecode() {
        byte[] hostile = new byte[10_001];
        Arrays.fill(hostile, 0, hostile.length - 1, (byte) 0x81);
        hostile[hostile.length - 1] = 0;

        WebhookEffectExecutor.WebhookCommand decoded =
                WebhookEffectExecutor.WebhookCommand.decode(hostile);

        assertThat(decoded.url()).isNull();
        assertThat(decoded.contentType()).isEqualTo("application/cbor");
        assertThat(decoded.body()).containsExactly(hostile);
    }
}
