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
import java.util.List;
import java.util.Objects;

/** Consensus domain for a Yano L2 transaction, distinct from a Cardano L1 transaction. */
public record EutxoL2Domain(
        String chainId,
        String network,
        String ledgerProfileDigest,
        String validityProfileDigest,
        String authorizationProfile,
        String authorizationProfileDigest,
        byte[] nonce,
        long expiry
) {
    public static final int VERSION = 1;
    private static final byte[] TAG =
            "yano:eutxo:l2-domain:v1".getBytes(StandardCharsets.US_ASCII);

    public EutxoL2Domain {
        chainId = text(chainId, "chain id", 63);
        network = text(network, "network", 16);
        if (!List.of("devnet", "preview", "preprod").contains(network)) {
            throw new IllegalArgumentException("unsupported EUTxO L2 network");
        }
        ledgerProfileDigest = digest(ledgerProfileDigest, "ledger profile digest");
        validityProfileDigest = digest(validityProfileDigest, "validity profile digest");
        authorizationProfile = text(
                authorizationProfile, "authorization profile", 63);
        authorizationProfileDigest = digest(
                authorizationProfileDigest, "authorization profile digest");
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        if (nonce.length != 32) {
            throw new IllegalArgumentException("L2 nonce must contain 32 bytes");
        }
        if (expiry < 1) {
            throw new IllegalArgumentException("L2 expiry must be positive");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    public byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                writeBytes(output, TAG);
                writeText(output, chainId);
                writeText(output, network);
                writeText(output, ledgerProfileDigest);
                writeText(output, validityProfileDigest);
                writeText(output, authorizationProfile);
                writeText(output, authorizationProfileDigest);
                writeBytes(output, nonce);
                output.writeLong(expiry);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory L2 domain encoding failed", impossible);
        }
    }

    public byte[] commitment() {
        return Blake2bUtil.blake2bHash256(canonicalBytes());
    }

    public static EutxoL2Domain decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > 4_096) {
            throw new IllegalArgumentException("invalid L2 domain size");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION
                    || !Arrays.equals(readBytes(input, 128), TAG)) {
                throw new IllegalArgumentException("unsupported L2 domain");
            }
            EutxoL2Domain domain = new EutxoL2Domain(
                    readText(input, 63),
                    readText(input, 16),
                    readText(input, 64),
                    readText(input, 64),
                    readText(input, 63),
                    readText(input, 64),
                    readBytes(input, 32),
                    input.readLong());
            if (input.available() != 0
                    || !Arrays.equals(encoded, domain.canonicalBytes())) {
                throw new IllegalArgumentException("non-canonical L2 domain");
            }
            return domain;
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid L2 domain", failure);
        }
    }

    public void requireExpected(
            String expectedChainId,
            String expectedNetwork,
            String expectedLedgerProfileDigest,
            String expectedValidityProfileDigest,
            String expectedAuthorizationProfile,
            String expectedAuthorizationProfileDigest
    ) {
        if (!chainId.equals(expectedChainId)
                || !network.equals(expectedNetwork)
                || !ledgerProfileDigest.equals(expectedLedgerProfileDigest)
                || !validityProfileDigest.equals(expectedValidityProfileDigest)
                || !authorizationProfile.equals(expectedAuthorizationProfile)
                || !authorizationProfileDigest.equals(
                expectedAuthorizationProfileDigest)) {
            throw new IllegalArgumentException(
                    "L2 transaction domain does not match this chain");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoL2Domain domain
                && chainId.equals(domain.chainId)
                && network.equals(domain.network)
                && ledgerProfileDigest.equals(domain.ledgerProfileDigest)
                && validityProfileDigest.equals(domain.validityProfileDigest)
                && authorizationProfile.equals(domain.authorizationProfile)
                && authorizationProfileDigest.equals(
                domain.authorizationProfileDigest)
                && Arrays.equals(nonce, domain.nonce)
                && expiry == domain.expiry;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                chainId, network, ledgerProfileDigest,
                validityProfileDigest, authorizationProfile,
                authorizationProfileDigest, expiry);
        return 31 * result + Arrays.hashCode(nonce);
    }

    private static String text(String value, String label, int maximum) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        value = text(value, label, 64);
        if (value.length() != 64
                || !value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256 hex");
        }
        try {
            if (HexFormat.of().parseHex(value).length != 32) {
                throw new IllegalArgumentException(label + " must contain 32 bytes");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256 hex", failure);
        }
        return value;
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static String readText(DataInputStream input, int maximum)
            throws IOException {
        return new String(readBytes(input, maximum), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) {
            throw new IllegalArgumentException("invalid length-delimited L2 field");
        }
        return input.readNBytes(length);
    }
}
