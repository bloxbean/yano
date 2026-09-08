package com.bloxbean.cardano.yano.runtime.wallet;

/** Reusable SipHash-2-4 state. Confined to one filter construction/query. */
final class SipHash24 {
    private final long k0;
    private final long k1;
    private long v0;
    private long v1;
    private long v2;
    private long v3;

    SipHash24(byte[] key) {
        if (key.length != 16) throw new IllegalArgumentException("SipHash key must be 16 bytes");
        k0 = littleEndian(key, 0);
        k1 = littleEndian(key, 8);
    }

    long hash(byte[] bytes) {
        v0 = 0x736f6d6570736575L ^ k0;
        v1 = 0x646f72616e646f6dL ^ k1;
        v2 = 0x6c7967656e657261L ^ k0;
        v3 = 0x7465646279746573L ^ k1;
        int offset = 0;
        while (offset + 8 <= bytes.length) {
            compress(littleEndian(bytes, offset));
            offset += 8;
        }
        long tail = (long) bytes.length << 56;
        for (int i = 0; offset + i < bytes.length; i++) {
            tail |= (bytes[offset + i] & 255L) << (8 * i);
        }
        compress(tail);
        v2 ^= 255;
        round();
        round();
        round();
        round();
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private void compress(long word) {
        v3 ^= word;
        round();
        round();
        v0 ^= word;
    }

    private void round() {
        v0 += v1;
        v1 = Long.rotateLeft(v1, 13) ^ v0;
        v0 = Long.rotateLeft(v0, 32);
        v2 += v3;
        v3 = Long.rotateLeft(v3, 16) ^ v2;
        v0 += v3;
        v3 = Long.rotateLeft(v3, 21) ^ v0;
        v2 += v1;
        v1 = Long.rotateLeft(v1, 17) ^ v2;
        v2 = Long.rotateLeft(v2, 32);
    }

    private static long littleEndian(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (bytes[offset + i] & 255L) << (8 * i);
        return value;
    }
}
