package com.bloxbean.cardano.yano.api.appchain.observation;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Domain-separated cryptographic identities for observation protocol v1. */
public final class ObservationHashes {
    private static final byte[] DEFINITION = domain("definition");
    private static final byte[] PROFILE = domain("profile");
    private static final byte[] SUBSCRIPTION = domain("subscription");
    private static final byte[] REPORT = domain("report");
    private static final byte[] CERTIFICATE = domain("certificate");
    private static final byte[] RESULT = domain("result");
    private static final byte[] REPORTER_SET = domain("reporter-set");

    private ObservationHashes() {
    }

    public static byte[] definitionDigest(ObservationDefinition definition) {
        return digest(DEFINITION, definition.encode());
    }

    public static byte[] profileDigest(ObservationProfileV1 profile) {
        return digest(PROFILE, profile.encode());
    }

    public static byte[] subscriptionId(byte[] genesisId, long creationHeight, int ordinal,
                                        byte[] definitionDigest, byte[] parameters) {
        Objects.requireNonNull(genesisId, "genesisId");
        Objects.requireNonNull(definitionDigest, "definitionDigest");
        Objects.requireNonNull(parameters, "parameters");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(SUBSCRIPTION);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(genesisId.length);
                out.write(genesisId);
                out.writeLong(creationHeight);
                out.writeInt(ordinal);
                out.writeInt(definitionDigest.length);
                out.write(definitionDigest);
                out.writeInt(parameters.length);
                out.write(parameters);
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static byte[] reportSigningDigest(ObservationReport report) {
        return digest(REPORT, report.encodeWithoutSignature());
    }

    public static byte[] certificateDigest(ObservationCertificate certificate) {
        return digest(CERTIFICATE, certificate.encode());
    }

    public static byte[] resultId(byte[] subscriptionId, long roundNumber,
                                  byte[] definitionDigest, ObservationResultStatus status,
                                  byte[] valueDigest) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(RESULT);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(subscriptionId);
                out.write(canonicalUnsigned(roundNumber));
                out.write(definitionDigest);
                out.writeByte(status.code());
                out.write(valueDigest);
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static byte[] digest(byte[] value) {
        return Blake2bUtil.blake2bHash256(Objects.requireNonNull(value, "value"));
    }

    public static byte[] reporterSetDigest(List<byte[]> reporterKeys) {
        List<byte[]> keys = new ArrayList<>(Objects.requireNonNull(reporterKeys, "reporterKeys"));
        keys.replaceAll(key -> ObservationCbor.fixed(key, 32, "reporter key"));
        keys.sort(Arrays::compareUnsigned);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(REPORTER_SET);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(keys.size());
                byte[] previous = null;
                for (byte[] key : keys) {
                    if (previous != null && Arrays.equals(previous, key)) {
                        throw new IllegalArgumentException("duplicate reporter key");
                    }
                    out.write(key);
                    previous = key;
                }
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static byte[] digest(byte[] domain, byte[] value) {
        byte[] input = new byte[domain.length + value.length];
        System.arraycopy(domain, 0, input, 0, domain.length);
        System.arraycopy(value, 0, input, domain.length, value.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    private static byte[] domain(String kind) {
        return ("yano/observation/" + kind + "/v1\0")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] canonicalUnsigned(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned value must be nonnegative");
        }
        return CborSerializationUtil.serialize(new UnsignedInteger(value));
    }
}
