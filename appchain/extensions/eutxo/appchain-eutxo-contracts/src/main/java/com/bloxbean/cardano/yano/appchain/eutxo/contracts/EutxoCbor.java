package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EutxoCbor {
    private static final int VERSION = 1;

    private EutxoCbor() {
    }

    static byte[] encodeRecord(EutxoRecord record) {
        return encode(recordItem(record));
    }

    static EutxoRecord decodeRecord(byte[] bytes) {
        return record(item(bytes));
    }

    static byte[] encodeReceipt(EutxoReceipt receipt) {
        Array array = new Array();
        array.add(uint(VERSION));
        array.add(uint(receipt.status().ordinal()));
        array.add(text(receipt.transactionId()));
        array.add(new ByteString(receipt.appMessageId()));
        array.add(uint(receipt.appHeight()));
        array.add(uint(receipt.ordinal()));
        array.add(uint(receipt.l1Slot()));
        array.add(text(receipt.code()));
        array.add(text(receipt.detail()));
        return encode(array);
    }

    static EutxoReceipt decodeReceipt(byte[] bytes) {
        return receipt(item(bytes));
    }

    static byte[] encodeOptionalRecord(EutxoRecord record) {
        return encode(record == null ? SimpleValue.NULL : recordItem(record));
    }

    static EutxoRecord decodeOptionalRecord(byte[] bytes) {
        DataItem item = item(bytes);
        return item == SimpleValue.NULL ? null : record(item);
    }

    static byte[] encodeOptionalReceipt(EutxoReceipt receipt) {
        return encode(receipt == null ? SimpleValue.NULL : item(receipt.encode()));
    }

    static EutxoReceipt decodeOptionalReceipt(byte[] bytes) {
        DataItem item = item(bytes);
        return item == SimpleValue.NULL ? null : receipt(item);
    }

    static byte[] encodeRecords(List<EutxoRecord> records) {
        Objects.requireNonNull(records, "records");
        Array array = new Array();
        array.add(uint(VERSION));
        Array values = new Array();
        records.forEach(record -> values.add(recordItem(record)));
        array.add(values);
        return encode(array);
    }

    static List<EutxoRecord> decodeRecords(byte[] bytes) {
        List<DataItem> envelope = array(item(bytes), 2, "record list");
        version(envelope.get(0));
        List<EutxoRecord> records = new ArrayList<>();
        for (DataItem value : array(envelope.get(1), -1, "records")) {
            records.add(record(value));
        }
        return List.copyOf(records);
    }

    private static Array recordItem(EutxoRecord record) {
        Array array = new Array();
        array.add(uint(VERSION));
        array.add(text(record.outpoint().transactionId()));
        array.add(uint(record.outpoint().index()));
        array.add(text(record.address()));
        array.add(new ByteString(record.outputCbor()));
        array.add(uint(record.origin().ordinal()));
        return array;
    }

    private static EutxoRecord record(DataItem item) {
        List<DataItem> fields = array(item, 6, "EUTxO record");
        version(fields.get(0));
        int origin = integer(fields.get(5), "origin");
        if (origin < 0 || origin >= EutxoRecord.Origin.values().length) {
            throw new IllegalArgumentException("invalid EUTxO origin");
        }
        return new EutxoRecord(
                new EutxoOutpoint(string(fields.get(1), "transaction id"),
                        integer(fields.get(2), "output index")),
                string(fields.get(3), "address"),
                bytes(fields.get(4), "output CBOR"),
                EutxoRecord.Origin.values()[origin]);
    }

    private static EutxoReceipt receipt(DataItem item) {
        List<DataItem> fields = array(item, 9, "EUTxO receipt");
        version(fields.get(0));
        int status = integer(fields.get(1), "status");
        if (status < 0 || status >= EutxoReceipt.Status.values().length) {
            throw new IllegalArgumentException("invalid EUTxO receipt status");
        }
        return new EutxoReceipt(
                EutxoReceipt.Status.values()[status],
                string(fields.get(2), "transaction id"),
                bytes(fields.get(3), "app message id"),
                longInteger(fields.get(4), "app height"),
                integer(fields.get(5), "ordinal"),
                longInteger(fields.get(6), "L1 slot"),
                string(fields.get(7), "code"),
                string(fields.get(8), "detail"));
    }

    private static void version(DataItem item) {
        if (integer(item, "version") != VERSION) {
            throw new IllegalArgumentException("unsupported EUTxO contract version");
        }
    }

    private static DataItem item(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            List<DataItem> items = new CborDecoder(new ByteArrayInputStream(bytes)).decode();
            if (items.size() != 1) {
                throw new IllegalArgumentException("expected one canonical CBOR item");
            }
            return items.get(0);
        } catch (CborException failure) {
            throw new IllegalArgumentException("invalid EUTxO CBOR", failure);
        }
    }

    private static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new CborEncoder(output).encode(item);
            return output.toByteArray();
        } catch (CborException failure) {
            throw new IllegalStateException("failed to encode EUTxO CBOR", failure);
        }
    }

    private static List<DataItem> array(DataItem item, int size, String field) {
        if (!(item instanceof Array array)) {
            throw new IllegalArgumentException(field + " must be a CBOR array");
        }
        List<DataItem> values = array.getDataItems();
        if (size >= 0 && values.size() != size) {
            throw new IllegalArgumentException(field + " must contain " + size + " fields");
        }
        return values;
    }

    private static UnsignedInteger uint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("CBOR unsigned integer cannot be negative");
        }
        return new UnsignedInteger(value);
    }

    private static UnicodeString text(String value) {
        return new UnicodeString(Objects.requireNonNull(value, "value"));
    }

    private static int integer(DataItem item, String field) {
        long value = longInteger(item, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " exceeds integer range");
        }
        return (int) value;
    }

    private static long longInteger(DataItem item, String field) {
        BigInteger value;
        if (item instanceof UnsignedInteger integer) {
            value = integer.getValue();
        } else if (item instanceof NegativeInteger integer) {
            value = integer.getValue();
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " exceeds long range", failure);
        }
    }

    private static String string(DataItem item, String field) {
        if (!(item instanceof UnicodeString string)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return string.getString();
    }

    private static byte[] bytes(DataItem item, String field) {
        if (!(item instanceof ByteString string)) {
            throw new IllegalArgumentException(field + " must be bytes");
        }
        return string.getBytes();
    }
}
