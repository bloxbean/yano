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

    static byte[] encodeDepositClaim(EutxoDepositClaim claim) {
        Array array = new Array();
        array.add(uint(claim.abiVersion()));
        array.add(text(claim.chainId()));
        outpoint(array, claim.acceptedOutpoint());
        array.add(uint(claim.l1Slot()));
        array.add(new ByteString(claim.l1BlockHash()));
        array.add(text(claim.vaultAddress()));
        array.add(text(claim.vaultScriptHash()));
        array.add(new ByteString(claim.acceptedOutputCbor()));
        array.add(text(claim.l2Address()));
        array.add(new ByteString(claim.mirroredOutputCbor()));
        array.add(new ByteString(claim.depositNonce()));
        outpoint(array, claim.stagingOutpoint());
        array.add(uint(claim.refundDeadline()));
        return encode(array);
    }

    static EutxoDepositClaim decodeDepositClaim(byte[] bytes) {
        List<DataItem> fields = array(item(bytes), 15, "deposit claim");
        return new EutxoDepositClaim(
                integer(fields.get(0), "ABI version"),
                string(fields.get(1), "chain id"),
                outpoint(fields.get(2), fields.get(3)),
                longInteger(fields.get(4), "L1 slot"),
                bytes(fields.get(5), "L1 block hash"),
                string(fields.get(6), "vault address"),
                string(fields.get(7), "vault script hash"),
                bytes(fields.get(8), "accepted output CBOR"),
                string(fields.get(9), "L2 address"),
                bytes(fields.get(10), "mirrored output CBOR"),
                bytes(fields.get(11), "deposit nonce"),
                outpoint(fields.get(12), fields.get(13)),
                longInteger(fields.get(14), "refund deadline"));
    }

    static byte[] encodeDepositRecord(EutxoDepositRecord record) {
        Array array = new Array();
        array.add(uint(VERSION));
        array.add(new ByteString(record.claim().encode()));
        outpoint(array, record.mirroredOutpoint());
        array.add(uint(record.creditedHeight()));
        return encode(array);
    }

    static EutxoDepositRecord decodeDepositRecord(byte[] bytes) {
        List<DataItem> fields = array(item(bytes), 5, "deposit record");
        version(fields.get(0));
        return new EutxoDepositRecord(
                EutxoDepositClaim.decode(bytes(fields.get(1), "deposit claim")),
                outpoint(fields.get(2), fields.get(3)),
                longInteger(fields.get(4), "credited height"));
    }

    static byte[] encodeOptionalDepositRecord(EutxoDepositRecord record) {
        return encode(record == null ? SimpleValue.NULL : item(record.encode()));
    }

    static EutxoDepositRecord decodeOptionalDepositRecord(byte[] bytes) {
        DataItem decoded = item(bytes);
        return decoded == SimpleValue.NULL
                ? null : decodeDepositRecord(encode(decoded));
    }

    static byte[] encodeReserve(EutxoReserve reserve) {
        Array array = new Array();
        array.add(uint(VERSION));
        array.add(text(reserve.assetId()));
        array.add(uint(reserve.stableVault()));
        array.add(uint(reserve.spendableMirrored()));
        array.add(uint(reserve.pendingWithdrawals()));
        array.add(uint(reserve.confirmedWithdrawals()));
        return encode(array);
    }

    static EutxoReserve decodeReserve(byte[] bytes) {
        List<DataItem> fields = array(item(bytes), 6, "reserve");
        version(fields.get(0));
        return new EutxoReserve(
                string(fields.get(1), "asset id"),
                bigInteger(fields.get(2), "stable vault"),
                bigInteger(fields.get(3), "spendable mirrored"),
                bigInteger(fields.get(4), "pending withdrawals"),
                bigInteger(fields.get(5), "confirmed withdrawals"));
    }

    static byte[] encodeOptionalReserve(EutxoReserve reserve) {
        return encode(reserve == null ? SimpleValue.NULL : item(reserve.encode()));
    }

    static EutxoReserve decodeOptionalReserve(byte[] bytes) {
        DataItem decoded = item(bytes);
        return decoded == SimpleValue.NULL ? null : decodeReserve(encode(decoded));
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

    private static void outpoint(Array array, EutxoOutpoint outpoint) {
        array.add(text(outpoint.transactionId()));
        array.add(uint(outpoint.index()));
    }

    private static EutxoOutpoint outpoint(DataItem transactionId, DataItem index) {
        return new EutxoOutpoint(
                string(transactionId, "transaction id"),
                integer(index, "output index"));
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

    private static UnsignedInteger uint(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
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
        BigInteger value = bigInteger(item, field);
        try {
            return value.longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " exceeds long range", failure);
        }
    }

    private static BigInteger bigInteger(DataItem item, String field) {
        if (item instanceof UnsignedInteger integer) {
            return integer.getValue();
        }
        if (item instanceof NegativeInteger integer) {
            return integer.getValue();
        }
        throw new IllegalArgumentException(field + " must be an integer");
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
