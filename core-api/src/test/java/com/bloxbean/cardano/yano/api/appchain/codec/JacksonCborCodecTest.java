package com.bloxbean.cardano.yano.api.appchain.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonCborCodecTest {

    public record Order(String id, long qty, String symbol) {
    }

    @Test
    void roundTrip_record() {
        JacksonCborCodec<Order> codec = JacksonCborCodec.of(Order.class);
        Order order = new Order("o-1", 42, "ADA");

        byte[] encoded = codec.encode(order);
        Order decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(order);
        assertThat(codec.type()).isEqualTo(Order.class);
        // CBOR is compact vs JSON-ish text
        assertThat(encoded.length).isLessThan(64);
    }

    @Test
    void unknownProperties_ignored_forForwardCompat() {
        // Encode a wider record, decode into a narrower one
        record WideOrder(String id, long qty, String symbol, String extra) {
        }
        byte[] wide = JacksonCborCodec.of(WideOrder.class)
                .encode(new WideOrder("o-2", 7, "ADA", "future-field"));

        Order decoded = JacksonCborCodec.of(Order.class).decode(wide);
        assertThat(decoded.id()).isEqualTo("o-2");
        assertThat(decoded.qty()).isEqualTo(7);
    }
}
