package com.bloxbean.cardano.yano.api.appchain.state;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical, backend-neutral identity of one app-chain authenticated-state
 * commitment contract (ADR-025).
 *
 * <p>The Yano profile id is consensus identity. {@code commitmentFormatId}
 * names the exact persistence/proof contract supplied by the implementation;
 * it is deliberately not accepted as a genesis alias.</p>
 */
public record StateCommitmentProfile(
        int schemaVersion,
        String id,
        BackendFamily backendFamily,
        String commitmentFormatId,
        String proofEncodingId,
        int rootLength,
        boolean nativeVersioning,
        boolean physicalDelete
) {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final byte[] FINGERPRINT_DOMAIN =
            "yano-state-commitment-format-v1\0".getBytes(StandardCharsets.US_ASCII);

    public StateCommitmentProfile {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("state commitment profile schemaVersion must be 1");
        }
        id = requireIdentifier(id, "id");
        backendFamily = Objects.requireNonNull(backendFamily, "backendFamily");
        commitmentFormatId = requireIdentifier(commitmentFormatId, "commitmentFormatId");
        proofEncodingId = requireIdentifier(proofEncodingId, "proofEncodingId");
        if (rootLength <= 0 || rootLength > 1024) {
            throw new IllegalArgumentException("rootLength must be between 1 and 1024");
        }
    }

    /** Canonical binary descriptor used as input to {@link #formatFingerprint()}. */
    public byte[] canonicalDescriptor() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(schemaVersion).array());
        putText(out, id);
        out.write(backendFamily.code());
        putText(out, commitmentFormatId);
        putText(out, proofEncodingId);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(rootLength).array());
        int flags = (nativeVersioning ? 1 : 0) | (physicalDelete ? 2 : 0);
        out.write(flags);
        return out.toByteArray();
    }

    /** Blake2b-256 fingerprint of the normalized commitment/persistence contract. */
    public byte[] formatFingerprint() {
        byte[] descriptor = canonicalDescriptor();
        byte[] input = new byte[FINGERPRINT_DOMAIN.length + descriptor.length];
        System.arraycopy(FINGERPRINT_DOMAIN, 0, input, 0, FINGERPRINT_DOMAIN.length);
        System.arraycopy(descriptor, 0, input, FINGERPRINT_DOMAIN.length, descriptor.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    private static String requireIdentifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a canonical lowercase identifier");
        }
        return normalized;
    }

    private static void putText(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(Short.BYTES).putShort((short) bytes.length).array());
        out.writeBytes(bytes);
    }

    public enum BackendFamily {
        MPF(0),
        JMT(1);

        private final int code;

        BackendFamily(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
