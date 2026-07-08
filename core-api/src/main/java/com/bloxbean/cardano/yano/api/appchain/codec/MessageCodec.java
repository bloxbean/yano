package com.bloxbean.cardano.yano.api.appchain.codec;

/**
 * Typed codec for app-message bodies (ADR app-layer/006 E1.3). The framework
 * stays blob-first — the codec lives strictly at the application edges
 * (submission and state-machine apply); the transport, sequencer and ledger
 * only ever see the encoded {@code byte[]}.
 * <p>
 * Applications register their own codec (protobuf, JSON, a hand-rolled binary
 * format, or the provided {@code JacksonCborCodec}) so they work with typed
 * objects instead of raw bytes.
 *
 * @param <T> the application message type
 */
public interface MessageCodec<T> {

    byte[] encode(T value);

    T decode(byte[] body);

    /** The concrete type this codec handles. */
    Class<T> type();
}
