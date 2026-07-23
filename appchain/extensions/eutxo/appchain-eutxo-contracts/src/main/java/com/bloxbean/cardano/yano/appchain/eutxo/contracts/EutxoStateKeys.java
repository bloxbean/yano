package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned deterministic keys committed by the Yano app-chain MPF. */
public final class EutxoStateKeys {
    private static final String PREFIX = "eutxo/v1/";

    private EutxoStateKeys() {
    }

    public static byte[] profile() {
        return bytes(PREFIX + "profile");
    }

    public static byte[] genesis() {
        return bytes(PREFIX + "genesis");
    }

    public static byte[] utxo(EutxoOutpoint outpoint) {
        return bytes(PREFIX + "u/" + Objects.requireNonNull(outpoint, "outpoint"));
    }

    public static byte[] transaction(String transactionId) {
        return bytes(PREFIX + "t/" + transactionId(transactionId));
    }

    public static byte[] attempt(byte[] appMessageId) {
        Objects.requireNonNull(appMessageId, "appMessageId");
        if (appMessageId.length != 32) {
            throw new IllegalArgumentException("app message id must contain 32 bytes");
        }
        return bytes(PREFIX + "a/" + HexFormat.of().formatHex(appMessageId));
    }

    public static byte[] addressIndex(String address) {
        Objects.requireNonNull(address, "address");
        if (address.isBlank() || address.length() > 256) {
            throw new IllegalArgumentException("address must contain 1-256 characters");
        }
        byte[] digest = Blake2bUtil.blake2bHash256(address.getBytes(StandardCharsets.UTF_8));
        return bytes(PREFIX + "x/address/" + HexFormat.of().formatHex(digest));
    }

    private static String transactionId(String value) {
        return new EutxoOutpoint(value, 0).transactionId();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
