package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Public, consensus-bound bridge configuration returned by the EUTxO machine. */
public record EutxoBridgeInfo(
        boolean enabled,
        String vaultAddress,
        String vaultScriptHash,
        String withdrawalAddress,
        long bridgeEpoch,
        BigInteger maximumWithdrawalLovelace,
        boolean withdrawalsPaused
) {
    private static final int VERSION = 1;

    public EutxoBridgeInfo {
        vaultAddress = text(vaultAddress, 256, "vaultAddress");
        vaultScriptHash = text(vaultScriptHash, 128, "vaultScriptHash");
        withdrawalAddress = text(withdrawalAddress, 256, "withdrawalAddress");
        maximumWithdrawalLovelace = Objects.requireNonNull(
                maximumWithdrawalLovelace, "maximumWithdrawalLovelace");
        if (bridgeEpoch < 0 || maximumWithdrawalLovelace.signum() <= 0) {
            throw new IllegalArgumentException("invalid bridge public information");
        }
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeBoolean(enabled);
                write(output, vaultAddress);
                write(output, vaultScriptHash);
                write(output, withdrawalAddress);
                output.writeLong(bridgeEpoch);
                write(output, maximumWithdrawalLovelace.toString());
                output.writeBoolean(withdrawalsPaused);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static EutxoBridgeInfo decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > 1_024) {
            throw new IllegalArgumentException("bridge information is too large");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported bridge information");
            }
            EutxoBridgeInfo value = new EutxoBridgeInfo(
                    input.readBoolean(),
                    read(input, 256), read(input, 128), read(input, 256),
                    input.readLong(), new BigInteger(read(input, 64)),
                    input.readBoolean());
            if (input.available() != 0) {
                throw new IllegalArgumentException("trailing bridge information bytes");
            }
            return value;
        } catch (IOException | NumberFormatException failure) {
            throw new IllegalArgumentException("invalid bridge information", failure);
        }
    }

    private static void write(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new IllegalArgumentException("invalid bridge information text");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("truncated bridge information");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String text(String value, int maximum, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > maximum
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
