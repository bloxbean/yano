package com.bloxbean.cardano.yano.api.appchain.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Default {@link MessageCodec} — CBOR serialization of POJOs/records via Jackson
 * (ADR app-layer/006 E1.3). Compact, schema-flexible, and a good fit for the
 * blob-first envelope. Applications that need protobuf or a hand-rolled format
 * supply their own {@code MessageCodec} instead.
 * <p>
 * For forward-compatible evolution prefer records with defaulted fields (Jackson
 * ignores unknown properties by default here), or wrap payloads in your own
 * {@code {version, ...}} shape.
 *
 * @param <T> the application message type
 */
public final class JacksonCborCodec<T> implements MessageCodec<T> {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory())
            .findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Class<T> type;

    private JacksonCborCodec(Class<T> type) {
        this.type = type;
    }

    public static <T> JacksonCborCodec<T> of(Class<T> type) {
        return new JacksonCborCodec<>(type);
    }

    @Override
    public byte[] encode(T value) {
        try {
            return CBOR_MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException("CBOR encode of " + type.getName() + " failed", e);
        }
    }

    @Override
    public T decode(byte[] body) {
        try {
            return CBOR_MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("CBOR decode of " + type.getName() + " failed", e);
        }
    }

    @Override
    public Class<T> type() {
        return type;
    }
}
