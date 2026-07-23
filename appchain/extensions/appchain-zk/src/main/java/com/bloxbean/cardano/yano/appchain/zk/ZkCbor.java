package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

/** Frozen structural bounds for member-supplied first-party ZK envelopes. */
final class ZkCbor {
    private static final CborStructurePreflight.Limits BODY_LIMITS =
            new CborStructurePreflight.Limits(
                    AppChainConfig.MAX_MESSAGE_BYTES, 4, 8_192, 8_192,
                    AppChainConfig.MAX_MESSAGE_BYTES);

    private ZkCbor() {
    }

    static void requireBody(byte[] bytes) {
        if (!CborStructurePreflight.accepts(bytes, BODY_LIMITS)) {
            throw new IllegalArgumentException("invalid bounded ZK body CBOR");
        }
    }
}
