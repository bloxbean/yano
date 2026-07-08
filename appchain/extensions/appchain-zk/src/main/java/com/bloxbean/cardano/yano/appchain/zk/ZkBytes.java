package com.bloxbean.cardano.yano.appchain.zk;

import java.math.BigInteger;

/** Small byte helpers shared by the ZK CBOR bodies. */
final class ZkBytes {

    private ZkBytes() {
    }

    /** Big-endian unsigned encoding of a non-negative field element (no sign byte). */
    static byte[] toUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
