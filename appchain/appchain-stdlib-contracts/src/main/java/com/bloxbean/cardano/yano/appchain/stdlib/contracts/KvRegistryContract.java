package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.util.List;

/** Version 1 command and state contract for {@code kv-registry}. */
public final class KvRegistryContract {
    public static final String STATE_MACHINE_ID = "kv-registry";
    public static final String DEFAULT_TOPIC = "kv-registry.command.v1";
    public static final int OP_PUT = 0;
    public static final int OP_DELETE = 1;

    private KvRegistryContract() {
    }

    public static byte[] put(byte[] key, byte[] value) {
        return command(OP_PUT, requiredKey(key), boundedBytes(value, "value"));
    }

    public static byte[] delete(byte[] key) {
        return command(OP_DELETE, requiredKey(key), new byte[0]);
    }

    public static Command decodeCommand(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
        int op = StdlibContractCbor.uintInt(values.get(0));
        byte[] key = requiredKey(StdlibContractCbor.bytes(values.get(1)));
        byte[] value = StdlibContractCbor.bytes(values.get(2));
        if ((op == OP_DELETE && value.length != 0)
                || (op != OP_PUT && op != OP_DELETE)) throw StdlibContractCbor.malformed();
        return new Command(op, key, value);
    }

    public static Entry decodeEntry(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 2).getDataItems();
        return new Entry(StdlibContractCbor.bytes(values.get(0), 32),
                StdlibContractCbor.bytes(values.get(1)));
    }

    private static byte[] command(int op, byte[] key, byte[] value) {
        Array array = new Array();
        array.add(new UnsignedInteger(op));
        array.add(new ByteString(key));
        array.add(new ByteString(value));
        return StdlibContractCbor.encode(array);
    }

    private static byte[] requiredKey(byte[] value) {
        StdlibContractCbor.requireStateKey(value, "key");
        return value.clone();
    }

    private static byte[] boundedBytes(byte[] value, String name) {
        if (value == null || value.length > StdlibContractCbor.MAX_WIRE_BYTES) {
            throw new IllegalArgumentException(name + " must be present and bounded");
        }
        return value.clone();
    }

    public record Command(int operation, byte[] key, byte[] value) {
        public Command { key = key.clone(); value = value.clone(); }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
        public boolean put() { return operation == OP_PUT; }
    }

    public record Entry(byte[] owner, byte[] value) {
        public Entry { owner = owner.clone(); value = value.clone(); }
        @Override public byte[] owner() { return owner.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
}
