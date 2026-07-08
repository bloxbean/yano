package com.bloxbean.cardano.yano.appchain.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Client-side CBOR codec for typed message payloads (ADR app-layer/006 E1.3),
 * wire-compatible with the node-side {@code JacksonCborCodec}.
 * <p>
 * This is an intentional near-duplicate of core-api's {@code JacksonCborCodec}:
 * the client SDK deliberately does NOT depend on core-api (which pulls in yaci
 * and the full node stack), so the small codec is copied rather than shared.
 * The two MUST stay byte-compatible — any change to the Jackson mapper config
 * here must be mirrored in {@code JacksonCborCodec} (and vice versa).
 * <p>
 * Use with
 * {@link AppChainClient#submitTyped} / {@link AppChainClient#subscribeTyped}:
 *
 * <pre>
 * CborCodec&lt;Order&gt; codec = CborCodec.of(Order.class);
 * client.submitTyped("orders", new Order(...), codec::encode);
 * client.subscribeTyped(-1, "orders", codec::decode, (order, msg) -&gt; handle(order));
 * </pre>
 *
 * @param <T> the application message type
 */
public final class CborCodec<T> {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory())
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Class<T> type;

    private CborCodec(Class<T> type) {
        this.type = type;
    }

    public static <T> CborCodec<T> of(Class<T> type) {
        return new CborCodec<>(type);
    }

    public byte[] encode(T value) {
        try {
            return CBOR_MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException("CBOR encode of " + type.getName() + " failed", e);
        }
    }

    public T decode(byte[] body) {
        try {
            return CBOR_MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("CBOR decode of " + type.getName() + " failed", e);
        }
    }
}
