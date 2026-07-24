package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical immutable Cardano-shaped protocol parameters for one L2 chain. */
public record EutxoL2ParameterSnapshot(
        String chainId,
        String ledgerProfileDigest,
        String validityProfileDigest,
        String authorizationProfile,
        String authorizationProfileDigest,
        int maxTransactionBytes,
        int maxInputs,
        int maxOutputs,
        String digest
) {
    private static final int VERSION = 1;

    public EutxoL2ParameterSnapshot {
        chainId = text(chainId, "chainId", 63);
        ledgerProfileDigest = digest(
                ledgerProfileDigest, "ledgerProfileDigest");
        validityProfileDigest = digest(
                validityProfileDigest, "validityProfileDigest");
        authorizationProfile = text(
                authorizationProfile, "authorizationProfile", 63);
        authorizationProfileDigest = digest(
                authorizationProfileDigest, "authorizationProfileDigest");
        if (maxTransactionBytes < 1 || maxInputs < 1 || maxOutputs < 1) {
            throw new IllegalArgumentException(
                    "L2 parameter bounds must be positive");
        }
        String computed = computeDigest(
                chainId, ledgerProfileDigest, validityProfileDigest,
                authorizationProfile, authorizationProfileDigest,
                maxTransactionBytes, maxInputs, maxOutputs);
        if (digest == null || digest.isBlank()) {
            digest = computed;
        } else if (!computed.equals(digest)) {
            throw new IllegalArgumentException(
                    "L2 parameter digest mismatch");
        }
    }

    public static EutxoL2ParameterSnapshot create(
            String chainId,
            EutxoProfile profile,
            EutxoValidityCommitmentEngine engine
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(engine, "engine");
        return new EutxoL2ParameterSnapshot(
                chainId,
                profile.digestHex(),
                engine.profileDigest(),
                engine.authorizationProfile(),
                engine.authorizationProfileDigest(),
                profile.maxTransactionBytes(),
                profile.maxInputs(),
                profile.maxOutputs(),
                "");
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                write(output, chainId);
                write(output, ledgerProfileDigest);
                write(output, validityProfileDigest);
                write(output, authorizationProfile);
                write(output, authorizationProfileDigest);
                output.writeInt(maxTransactionBytes);
                output.writeInt(maxInputs);
                output.writeInt(maxOutputs);
                write(output, digest);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "in-memory L2 parameter encoding failed", impossible);
        }
    }

    public static EutxoL2ParameterSnapshot decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < 32 || encoded.length > 2_048) {
            throw new IllegalArgumentException(
                    "invalid L2 parameter snapshot size");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported L2 parameter snapshot");
            }
            EutxoL2ParameterSnapshot snapshot =
                    new EutxoL2ParameterSnapshot(
                            read(input, 63),
                            read(input, 64),
                            read(input, 64),
                            read(input, 63),
                            read(input, 64),
                            input.readInt(),
                            input.readInt(),
                            input.readInt(),
                            read(input, 64));
            if (input.available() != 0
                    || !Arrays.equals(encoded, snapshot.encode())) {
                throw new IllegalArgumentException(
                        "non-canonical L2 parameter snapshot");
            }
            return snapshot;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "invalid L2 parameter snapshot", failure);
        }
    }

    private static String computeDigest(
            String chainId,
            String ledger,
            String validity,
            String authorizationProfile,
            String authorization,
            int maxBytes,
            int maxInputs,
            int maxOutputs
    ) {
        String canonical = String.join("\n",
                "yano:eutxo:l2-parameters:v1",
                chainId,
                ledger,
                validity,
                authorizationProfile,
                authorization,
                "minFeeA=0",
                "minFeeB=0",
                "maxTransactionBytes=" + maxBytes,
                "maxInputs=" + maxInputs,
                "maxOutputs=" + maxOutputs,
                "coinsPerUtxoByte=0",
                "collateral=0");
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static void write(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum) {
            throw new IllegalArgumentException(
                    "invalid L2 parameter field length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException(
                    "truncated L2 parameter snapshot");
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String text(
            String value,
            String label,
            int maximum
    ) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        value = Objects.requireNonNull(value, label);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be lowercase 32-byte hex");
        }
        return value;
    }
}
