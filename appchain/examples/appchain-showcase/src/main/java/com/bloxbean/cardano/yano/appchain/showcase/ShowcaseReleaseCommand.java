package com.bloxbean.cardano.yano.appchain.showcase;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Canonical demo release command: {@code [1, releaseId, orderKey, approvalId]}. */
public record ShowcaseReleaseCommand(String releaseId, byte[] orderKey, String approvalId) {
    private static final int VERSION = 1;
    private static final int MAX_ID_BYTES = 96;
    private static final int MAX_KEY_BYTES = 256;

    public ShowcaseReleaseCommand {
        requireText(releaseId, "releaseId");
        requireText(approvalId, "approvalId");
        orderKey = orderKey != null ? orderKey.clone() : new byte[0];
        if (orderKey.length == 0 || orderKey.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("orderKey must contain 1..256 bytes");
        }
    }

    @Override
    public byte[] orderKey() {
        return orderKey.clone();
    }

    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(VERSION));
        value.add(new UnicodeString(releaseId));
        value.add(new ByteString(orderKey));
        value.add(new UnicodeString(approvalId));
        return CborSerializationUtil.serialize(value);
    }

    public byte[] commandHash() {
        return Blake2bUtil.blake2bHash256(encode());
    }

    public static ShowcaseReleaseCommand decode(byte[] encoded) {
        try {
            Array value = (Array) CborSerializationUtil.deserializeOne(encoded);
            List<DataItem> items = value.getDataItems();
            if (items.size() != 4
                    || ((UnsignedInteger) items.get(0)).getValue().intValueExact() != VERSION) {
                throw invalid();
            }
            ShowcaseReleaseCommand decoded = new ShowcaseReleaseCommand(
                    ((UnicodeString) items.get(1)).getString(),
                    ((ByteString) items.get(2)).getBytes(),
                    ((UnicodeString) items.get(3)).getString());
            if (!Arrays.equals(encoded, decoded.encode())) {
                throw invalid();
            }
            return decoded;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new IllegalArgumentException(name + " must be trimmed UTF-8 text of 1..96 bytes");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid canonical showcase release command");
    }
}
