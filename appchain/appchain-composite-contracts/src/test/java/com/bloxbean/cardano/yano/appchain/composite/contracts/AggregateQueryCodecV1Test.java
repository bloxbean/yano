package com.bloxbean.cardano.yano.appchain.composite.contracts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateQueryCodecV1Test {
    @Test
    void roundTripsCanonicalClientWireWithoutPluginDependencies() {
        List<AggregateQueryCodecV1.Subquery> expected = List.of(
                new AggregateQueryCodecV1.Subquery("evidence", "get", new byte[]{1, 2}));
        byte[] request = AggregateQueryCodecV1.encodeRequest(expected,
                AggregateQueryLimitsV1.DEFAULT);
        assertThat(AggregateQueryCodecV1.decodeRequest(
                request, AggregateQueryLimitsV1.DEFAULT)).isEqualTo(expected);
    }

    @Test
    void responseDecoderEnforcesCompleteConfiguredEnvelopeBound() {
        byte[] encoded = AggregateQueryCodecV1.encodeResponse(List.of(
                new AggregateQueryCodecV1.Result("evidence", "get", new byte[]{1})),
                AggregateQueryLimitsV1.DEFAULT);
        AggregateQueryLimitsV1 small = new AggregateQueryLimitsV1(1, 64, encoded.length - 1);

        assertThatThrownBy(() -> AggregateQueryCodecV1.decodeResponse(encoded, small))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding exceeds");
    }

    @Test
    void responseEncoderRejectsAnEmptyNonCanonicalEnvelope() {
        assertThatThrownBy(() -> AggregateQueryCodecV1.encodeResponse(
                List.of(), AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result count");
    }
}
