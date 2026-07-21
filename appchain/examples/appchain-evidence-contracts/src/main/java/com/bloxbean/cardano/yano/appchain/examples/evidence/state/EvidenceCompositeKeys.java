package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Lightweight proof-client mapping for evidence-local keys in composite profile v1. */
public final class EvidenceCompositeKeys {
    public static final String STATE_MACHINE_ID = "composite";
    public static final String COMPONENT_ID = "evidence";
    public static final int MAX_PHYSICAL_KEY_BYTES = 256;
    private static final byte[] DOMAIN = "yano-composite-state-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMPONENT = COMPONENT_ID.getBytes(StandardCharsets.US_ASCII);

    private EvidenceCompositeKeys() {
    }

    public static byte[] physicalKey(byte[] evidenceLocalKey) {
        byte[] local = Objects.requireNonNull(evidenceLocalKey, "evidenceLocalKey").clone();
        if (local.length == 0 || local.length > 65_535) {
            throw new IllegalArgumentException("evidenceLocalKey must contain 1-65535 bytes");
        }
        int size = DOMAIN.length + 1 + COMPONENT.length + 2 + local.length;
        if (size > MAX_PHYSICAL_KEY_BYTES) {
            throw new IllegalArgumentException("composite evidence key exceeds 256 bytes");
        }
        return ByteBuffer.allocate(size).put(DOMAIN).put((byte) COMPONENT.length)
                .put(COMPONENT).putShort((short) local.length).put(local).array();
    }
}
