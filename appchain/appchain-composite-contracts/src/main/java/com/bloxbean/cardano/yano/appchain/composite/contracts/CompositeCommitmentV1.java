package com.bloxbean.cardano.yano.appchain.composite.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * No-SPI client contract for composite-v1 profile and component-state commitments.
 */
public final class CompositeCommitmentV1 {
    public static final String STATE_MACHINE_ID = "composite";
    public static final int DIGEST_BYTES = 32;
    public static final int MAX_PHYSICAL_KEY_BYTES = 256;
    public static final int MAX_PROFILE_BYTES = 64 * 1024;
    private static final Pattern COMPONENT_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final byte[] PROFILE_MARKER = "~composite/profile/v1"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMPONENT_DOMAIN = "yano-composite-state-v1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROFILE_EPOCH_DOMAIN = "~composite/profile-epoch/v1/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CURRENT_PROFILE_EPOCH = "~composite/profile-epoch/v1/current"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGEST_DOMAIN = "yano-composite-profile-v1"
            .getBytes(StandardCharsets.US_ASCII);

    private CompositeCommitmentV1() {
    }

    /** Authenticated state key whose value is the canonical effective profile. */
    public static byte[] profileMarkerKey() {
        return PROFILE_MARKER.clone();
    }

    /** Authenticated pointer to the current governed profile epoch number. */
    public static byte[] currentProfileEpochKey() {
        return CURRENT_PROFILE_EPOCH.clone();
    }

    /** Authenticated append-only governed profile-epoch record key. */
    public static byte[] profileEpochKey(long epochNumber) {
        if (epochNumber < 0) throw new IllegalArgumentException("epochNumber must be non-negative");
        return ByteBuffer.allocate(PROFILE_EPOCH_DOMAIN.length + Long.BYTES)
                .put(PROFILE_EPOCH_DOMAIN).putLong(epochNumber).array();
    }

    /** Maps one component-local key to its authenticated composite state key. */
    public static byte[] componentKey(String componentId, byte[] localKey) {
        if (componentId == null || !COMPONENT_ID.matcher(componentId).matches()) {
            throw new IllegalArgumentException(
                    "componentId must match [a-z][a-z0-9-]{0,62}");
        }
        byte[] id = componentId.getBytes(StandardCharsets.US_ASCII);
        byte[] local = Objects.requireNonNull(localKey, "localKey").clone();
        if (local.length == 0 || local.length > 65_535) {
            throw new IllegalArgumentException("localKey must contain 1-65535 bytes");
        }
        int size = COMPONENT_DOMAIN.length + 1 + id.length + 2 + local.length;
        if (size > MAX_PHYSICAL_KEY_BYTES) {
            throw new IllegalArgumentException("composite physical state key exceeds 256 bytes");
        }
        return ByteBuffer.allocate(size).put(COMPONENT_DOMAIN).put((byte) id.length)
                .put(id).putShort((short) local.length).put(local).array();
    }

    /** Domain-separated SHA-256 identity of canonical effective-profile bytes. */
    public static byte[] profileDigest(byte[] canonicalProfile) {
        byte[] profile = Objects.requireNonNull(canonicalProfile, "canonicalProfile").clone();
        if (profile.length == 0 || profile.length > MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("canonicalProfile must contain 1-65536 bytes");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_DOMAIN);
            return digest.digest(profile);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
