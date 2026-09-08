package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Strict fixed-array deterministic-CBOR helpers shared by observation v1 records. */
final class ObservationCbor {
    static final int VERSION = 1;
    static final int HASH_BYTES = 32;
    static final int SIGNATURE_BYTES = 64;

    private ObservationCbor() {
    }

    static Array array() {
        return new Array();
    }

    static void uint(Array array, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("observation integer must be nonnegative");
        }
        array.add(new UnsignedInteger(value));
    }

    static void text(Array array, String value) {
        array.add(new UnicodeString(Objects.requireNonNull(value, "value")));
    }

    static void bytes(Array array, byte[] value) {
        array.add(new ByteString(Objects.requireNonNull(value, "value")));
    }

    static byte[] encode(Array array) {
        return CborSerializationUtil.serialize(array);
    }

    static List<DataItem> decode(byte[] bytes, int maximumBytes, int maximumItems,
                                 int maximumContainerItems, int maximumStringBytes,
                                 int fields, String kind) {
        CborStructurePreflight.Limits limits = new CborStructurePreflight.Limits(
                maximumBytes, 6, maximumItems, maximumContainerItems, maximumStringBytes);
        if (!CborStructurePreflight.accepts(bytes, limits)) {
            throw invalid(kind);
        }
        try {
            DataItem item = CborSerializationUtil.deserializeOne(bytes);
            if (!(item instanceof Array array) || array.getDataItems().size() != fields) {
                throw invalid(kind);
            }
            return array.getDataItems();
        } catch (RuntimeException malformed) {
            throw invalid(kind);
        }
    }

    static int intValue(DataItem item) {
        return ((UnsignedInteger) item).getValue().intValueExact();
    }

    static long longValue(DataItem item) {
        return ((UnsignedInteger) item).getValue().longValueExact();
    }

    static String textValue(DataItem item) {
        return ((UnicodeString) item).getString();
    }

    static byte[] bytesValue(DataItem item) {
        return ((ByteString) item).getBytes();
    }

    static byte[] fixed(byte[] value, int length, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " bytes");
        }
        return value.clone();
    }

    static byte[] bounded(byte[] value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.length > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " bytes");
        }
        return value.clone();
    }

    static String boundedText(String value, int maximumBytes, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " is blank or exceeds " + maximumBytes + " bytes");
        }
        return value;
    }

    static void canonical(byte[] original, byte[] encoded, String kind) {
        if (!Arrays.equals(original, encoded)) {
            throw invalid(kind);
        }
    }

    static IllegalArgumentException invalid(String kind) {
        return new IllegalArgumentException("invalid bounded canonical observation " + kind);
    }
}
