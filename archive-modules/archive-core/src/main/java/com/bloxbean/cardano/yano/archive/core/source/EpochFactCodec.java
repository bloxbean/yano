package com.bloxbean.cardano.yano.archive.core.source;

public interface EpochFactCodec<T> {
    byte[] encode(T value);
    T decode(byte[] value);
}
