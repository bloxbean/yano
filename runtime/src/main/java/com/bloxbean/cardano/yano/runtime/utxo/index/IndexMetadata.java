package com.bloxbean.cardano.yano.runtime.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxo;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.runtime.db.RocksDbContext;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.List;
import java.util.Arrays;

/** Host-owned safety gate and its undo; contributor callbacks cannot access these keys. */
final class IndexMetadata {
    private final Supplier<RocksDbContext> storage;
    private final String id;
    private final int schema;
    private final String network;

    record State(ChainPoint point, String reason) { }

    IndexMetadata(Supplier<RocksDbContext> storage, String id, int schema, String network) {
        this.storage = storage;
        this.id = id;
        this.schema = schema;
        this.network = network;
    }

    State read() {
        byte[] bytes = get(key((byte) 0, 0));
        return bytes == null ? null : decode(bytes);
    }

    boolean available(ChainPoint applied) {
        return unavailableReason(applied) == null;
    }

    String unavailableReason(ChainPoint applied) {
        State state;
        try { state = read(); }
        catch (IllegalStateException invalid) { return invalid.getMessage(); }
        if (state == null) return "Index history missing; fresh sync required";
        if (state.reason != null) return state.reason;
        if (!state.point.equals(applied)) return "Index is not at the applied canonical point";
        return null;
    }

    void genesis(WriteBatch batch, List<GenesisUtxo> outputs) {
        if (read() != null) throw new IllegalStateException("Contributor already initialized: " + id);
        put(batch, key((byte) 0, 0), encode(new State(ChainPoint.ORIGIN, null)));
        put(batch, key((byte) 2, 0), shelleyDigest(outputs));
    }

    void validateGenesisMaterialization(List<GenesisUtxo> outputs) {
        if (!Arrays.equals(get(key((byte) 2, 0)), shelleyDigest(outputs))) {
            throw new IllegalStateException("Genesis funds differ from contributor initialization: " + id);
        }
    }

    private static byte[] shelleyDigest(List<GenesisUtxo> outputs) {
        return GenesisUtxos.digest(outputs.stream().filter(output -> !output.isByron()).toList())
                .getBytes(StandardCharsets.UTF_8);
    }

    void applied(WriteBatch batch, ChainPoint previous, ChainPoint point, String failure) {
        byte[] old = get(key((byte) 0, 0));
        State state = old == null ? null : decode(old);
        String reason = failure;
        if (reason == null && state != null) reason = state.reason;
        boolean nextNumber = previous.equals(ChainPoint.ORIGIN)
                ? point.blockNumber() == 0 || point.blockNumber() == 1
                : point.blockNumber() == previous.blockNumber() + 1;
        if (reason == null && (state == null || !state.point.equals(previous) || !nextNumber)) {
            reason = "Index history gap; fresh sync required";
        }
        put(batch, key((byte) 1, point.blockNumber()), old == null ? new byte[0] : old);
        put(batch, key((byte) 0, 0), encode(new State(point, reason)));
    }

    void rollback(WriteBatch batch, ChainPoint target, String failure) {
        State state = read();
        while (state != null && state.point.blockNumber() > target.blockNumber()) {
            byte[] undoKey = key((byte) 1, state.point.blockNumber());
            byte[] old = get(undoKey);
            if (old == null) {
                failure = "Contributor undo unavailable; fresh sync required";
                break;
            }
            delete(batch, undoKey);
            State restored;
            try { restored = old.length == 0 ? null : decode(old); }
            catch (IllegalStateException invalidUndo) {
                failure = "Contributor undo invalid; fresh sync required";
                break;
            }
            if (restored != null && restored.point.blockNumber() >= state.point.blockNumber()) {
                failure = "Non-decreasing contributor undo; fresh sync required";
                break;
            }
            state = restored;
        }
        if (failure == null && (state == null || !state.point.equals(target))) {
            failure = "Contributor rollback point mismatch; fresh sync required";
        }
        put(batch, key((byte) 0, 0), encode(new State(target, failure != null ? failure : state.reason)));
    }

    void prune(WriteBatch batch, ChainPoint point) { delete(batch, key((byte) 1, point.blockNumber())); }

    private byte[] key(byte kind, long block) {
        byte[] name = id.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + name.length).put((byte) 1).putShort((short) name.length)
                .put(name).put(kind).putLong(block).array();
    }

    private byte[] get(byte[] key) {
        try { return storage.get().db().get(storage.get().handle(IndexStorage.META), key); }
        catch (RocksDBException failure) { throw new IndexStorage.StorageFailure(failure); }
    }

    private void put(WriteBatch batch, byte[] key, byte[] value) {
        try { batch.put(storage.get().handle(IndexStorage.META), key, value); }
        catch (RocksDBException failure) { throw new IndexStorage.StorageFailure(failure); }
    }

    private void delete(WriteBatch batch, byte[] key) {
        try { batch.delete(storage.get().handle(IndexStorage.META), key); }
        catch (RocksDBException failure) { throw new IndexStorage.StorageFailure(failure); }
    }

    private byte[] encode(State state) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeInt(schema);
            out.writeUTF(network);
            out.writeLong(state.point.blockNumber());
            out.writeLong(state.point.slot());
            out.writeUTF(state.point.blockHash());
            out.writeUTF(state.reason == null ? "" : state.reason.substring(0, Math.min(1024, state.reason.length())));
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private State decode(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1 || in.readInt() != schema || !network.equals(in.readUTF())) {
                throw new IllegalStateException("Contributor schema/network mismatch: " + id + "; fresh sync required");
            }
            var point = new ChainPoint(in.readLong(), in.readLong(), in.readUTF());
            String reason = in.readUTF();
            if (in.available() != 0) throw new IOException("Trailing metadata");
            return new State(point, reason.isEmpty() ? null : reason);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid contributor metadata: " + id + "; fresh sync required", failure);
        }
    }
}
