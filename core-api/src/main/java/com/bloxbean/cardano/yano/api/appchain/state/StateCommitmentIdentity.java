package com.bloxbean.cardano.yano.api.appchain.state;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical chain-generation identity of the selected authenticated-state
 * commitment (ADR-025).
 *
 * <p>Absence of all three settings denotes a pre-ADR legacy MPF ledger. A
 * partially configured identity is never accepted.</p>
 */
public record StateCommitmentIdentity(
        int schemaVersion,
        StateCommitmentProfile profile,
        byte[] genesisId,
        boolean legacy
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String PROFILE_SETTING = "state.commitment-profile";
    public static final String FINGERPRINT_SETTING = "state.format-fingerprint";
    public static final String GENESIS_ID_SETTING = "state.genesis-id";

    private static final byte[] DIGEST_DOMAIN =
            "yano-state-commitment-identity-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MARKER_KEY =
            "~yano/state-commitment/v1".getBytes(StandardCharsets.US_ASCII);

    public StateCommitmentIdentity {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("state commitment identity schemaVersion must be 1");
        }
        profile = Objects.requireNonNull(profile, "profile");
        genesisId = Objects.requireNonNull(genesisId, "genesisId").clone();
        if (legacy) {
            if (!StateCommitmentProfiles.MPF.id().equals(profile.id()) || genesisId.length != 0) {
                throw new IllegalArgumentException("legacy state identity must be MPF without genesis id");
            }
        } else if (genesisId.length != 32) {
            throw new IllegalArgumentException("state commitment genesisId must contain 32 bytes");
        }
    }

    @Override
    public byte[] genesisId() {
        return genesisId.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateCommitmentIdentity identity
                && schemaVersion == identity.schemaVersion
                && legacy == identity.legacy
                && profile.equals(identity.profile)
                && Arrays.equals(genesisId, identity.genesisId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(schemaVersion, profile, legacy);
        return 31 * result + Arrays.hashCode(genesisId);
    }

    public static StateCommitmentIdentity legacyMpf() {
        return new StateCommitmentIdentity(
                SCHEMA_VERSION, StateCommitmentProfiles.MPF, new byte[0], true);
    }

    public static StateCommitmentIdentity explicit(
            StateCommitmentProfile profile,
            byte[] genesisId
    ) {
        return new StateCommitmentIdentity(SCHEMA_VERSION, profile, genesisId, false);
    }

    /** Resolve the closed profile identity from dynamic chain settings. */
    public static StateCommitmentIdentity fromSettings(Map<String, String> settings) {
        Map<String, String> source = settings != null ? settings : Map.of();
        boolean anyConfigured = source.containsKey(PROFILE_SETTING)
                || source.containsKey(FINGERPRINT_SETTING)
                || source.containsKey(GENESIS_ID_SETTING);
        if (!anyConfigured) {
            return legacyMpf();
        }
        String profileId = normalized(source.get(PROFILE_SETTING));
        String fingerprintHex = normalized(source.get(FINGERPRINT_SETTING));
        String genesisHex = normalized(source.get(GENESIS_ID_SETTING));
        if (profileId == null || fingerprintHex == null || genesisHex == null) {
            throw new IllegalArgumentException(
                    "state commitment profile, fingerprint, and genesis id must be configured together");
        }
        StateCommitmentProfile profile = StateCommitmentProfiles.require(profileId);
        byte[] fingerprint = parseCanonicalHex(fingerprintHex, 32, FINGERPRINT_SETTING);
        if (!Arrays.equals(fingerprint, profile.formatFingerprint())) {
            throw new IllegalArgumentException(
                    "state commitment format fingerprint does not match selected profile");
        }
        return explicit(profile, parseCanonicalHex(genesisHex, 32, GENESIS_ID_SETTING));
    }

    /** Exact settings required to recreate this explicit chain generation. */
    public Map<String, String> settings() {
        if (legacy) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PROFILE_SETTING, profile.id());
        values.put(FINGERPRINT_SETTING,
                java.util.HexFormat.of().formatHex(profile.formatFingerprint()));
        values.put(GENESIS_ID_SETTING, java.util.HexFormat.of().formatHex(genesisId));
        return Map.copyOf(values);
    }

    /** Canonical retained marker bytes for explicit ADR-025 generations. */
    public byte[] canonicalBytes() {
        if (legacy) {
            throw new IllegalStateException("legacy MPF identity has no retained marker bytes");
        }
        byte[] profileBytes = profile.id().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(Integer.BYTES + Short.BYTES + profileBytes.length + 64)
                .putInt(schemaVersion)
                .putShort((short) profileBytes.length)
                .put(profileBytes)
                .put(profile.formatFingerprint())
                .put(genesisId)
                .array();
    }

    public byte[] digest() {
        if (legacy) {
            return new byte[0];
        }
        byte[] canonical = canonicalBytes();
        byte[] input = new byte[DIGEST_DOMAIN.length + canonical.length];
        System.arraycopy(DIGEST_DOMAIN, 0, input, 0, DIGEST_DOMAIN.length);
        System.arraycopy(canonical, 0, input, DIGEST_DOMAIN.length, canonical.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    public static byte[] markerKey() {
        return MARKER_KEY.clone();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "state commitment settings must not contain surrounding whitespace");
        }
        return value;
    }

    private static byte[] parseCanonicalHex(String value, int bytes, String name) {
        if (value.length() != bytes * 2) {
            throw new IllegalArgumentException(name + " must contain " + bytes + " bytes of hex");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException(name + " must be canonical lowercase hex");
            }
        }
        return java.util.HexFormat.of().parseHex(value);
    }
}
