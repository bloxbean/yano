package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.HistoryContractCbor;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical stake-history dataset and authenticated-state contract. */
public final class EpochStakeContract {
    public static final int VERSION = 1;
    public static final int SEMANTICS_END_OF_EPOCH = 0;
    public static final int DEFAULT_CHUNK_ENTRIES = 25_000;
    public static final int MAX_CHUNK_ENTRIES = 25_000;
    public static final String STATE_MACHINE_ID = "epoch-stake";
    public static final String OBSERVER_TYPE = "l1-epoch-stake-v1";
    public static final String DEFAULT_OBSERVER_ID = "epoch-stake";
    public static final String PROOF_SUBJECT = "cardano-history/epoch-stake-v1";
    public static final String QUERY_PATH = "epoch-stake/get";
    public static final String META_QUERY_PATH = "epoch-stake/meta";
    private static final byte[] ROOT_DOMAIN = "yano-epoch-stake-root-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private EpochStakeContract() {
    }

    public record Entry(int credType, byte[] credHash, BigInteger coin, byte[] poolHash) {
        public Entry {
            if (credType < 0 || credType > 1 || credHash == null || credHash.length != 28
                    || coin == null || coin.signum() < 0
                    || poolHash == null || poolHash.length != 28) {
                throw new IllegalArgumentException("invalid epoch-stake entry");
            }
            credHash = credHash.clone();
            poolHash = poolHash.clone();
        }
        @Override public byte[] credHash() { return credHash.clone(); }
        @Override public byte[] poolHash() { return poolHash.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Entry that && credType == that.credType
                    && coin.equals(that.coin) && Arrays.equals(credHash, that.credHash)
                    && Arrays.equals(poolHash, that.poolHash);
        }
        @Override public int hashCode() {
            int result = Objects.hash(credType, coin);
            result = 31 * result + Arrays.hashCode(credHash);
            return 31 * result + Arrays.hashCode(poolHash);
        }
    }

    public record Manifest(long epoch, long totalEntries, int chunkEntries,
                           int chunkCount, byte[] snapshotRoot) {
        public Manifest {
            if (epoch < 0 || totalEntries < 0 || chunkEntries <= 0
                    || chunkEntries > MAX_CHUNK_ENTRIES || chunkCount < 0
                    || chunkCount != chunks(totalEntries, chunkEntries)
                    || snapshotRoot == null || snapshotRoot.length != 32) {
                throw new IllegalArgumentException("invalid epoch-stake manifest");
            }
            snapshotRoot = snapshotRoot.clone();
        }
        @Override public byte[] snapshotRoot() { return snapshotRoot.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Manifest that && epoch == that.epoch
                    && totalEntries == that.totalEntries && chunkEntries == that.chunkEntries
                    && chunkCount == that.chunkCount && Arrays.equals(snapshotRoot, that.snapshotRoot);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(epoch, totalEntries, chunkEntries, chunkCount)
                    + Arrays.hashCode(snapshotRoot);
        }
    }

    public record Chunk(long epoch, byte[] snapshotRoot, int index, List<Entry> entries) {
        public Chunk {
            if (epoch < 0 || snapshotRoot == null || snapshotRoot.length != 32 || index < 0
                    || entries == null || entries.size() > MAX_CHUNK_ENTRIES) {
                throw new IllegalArgumentException("invalid epoch-stake chunk");
            }
            snapshotRoot = snapshotRoot.clone();
            entries = List.copyOf(entries);
            requireCanonical(entries);
        }
        @Override public byte[] snapshotRoot() { return snapshotRoot.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Chunk that && epoch == that.epoch && index == that.index
                    && entries.equals(that.entries) && Arrays.equals(snapshotRoot, that.snapshotRoot);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(epoch, index, entries) + Arrays.hashCode(snapshotRoot);
        }
    }

    /** Authenticated ingestion state; consumers accept claims only when {@code complete} is true. */
    public record Meta(Manifest manifest, int receivedChunks, boolean complete) {
        public Meta {
            Objects.requireNonNull(manifest, "manifest");
            if (receivedChunks < 0 || receivedChunks > manifest.chunkCount()
                    || complete != (receivedChunks == manifest.chunkCount())) {
                throw new IllegalArgumentException("invalid epoch-stake completeness metadata");
            }
        }
    }

    public record Query(long epoch, int credType, byte[] credHash) {
        public Query {
            if (epoch < 0 || credType < 0 || credType > 1
                    || credHash == null || credHash.length != 28) {
                throw new IllegalArgumentException("invalid epoch-stake query");
            }
            credHash = credHash.clone();
        }
        @Override public byte[] credHash() { return credHash.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Query that && epoch == that.epoch
                    && credType == that.credType && Arrays.equals(credHash, that.credHash);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(epoch, credType) + Arrays.hashCode(credHash);
        }
    }

    public record Value(BigInteger coin, byte[] poolHash) {
        public Value {
            if (coin == null || coin.signum() < 0 || poolHash == null || poolHash.length != 28) {
                throw new IllegalArgumentException("invalid epoch-stake value");
            }
            poolHash = poolHash.clone();
        }
        @Override public byte[] poolHash() { return poolHash.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Value that && coin.equals(that.coin)
                    && Arrays.equals(poolHash, that.poolHash);
        }
        @Override public int hashCode() {
            return 31 * coin.hashCode() + Arrays.hashCode(poolHash);
        }
    }

    public static byte[] encodeManifest(Manifest value) {
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(value.epoch()));
        array.add(new UnsignedInteger(SEMANTICS_END_OF_EPOCH));
        array.add(new UnsignedInteger(value.totalEntries()));
        array.add(new UnsignedInteger(value.chunkEntries()));
        array.add(new UnsignedInteger(value.chunkCount()));
        array.add(new ByteString(value.snapshotRoot()));
        return HistoryContractCbor.encode(array);
    }

    public static Manifest decodeManifest(byte[] bytes) {
        Array values = HistoryContractCbor.decodeArray(bytes, 7);
        if (HistoryContractCbor.uintInt(values.getDataItems().get(0)) != VERSION
                || HistoryContractCbor.uintInt(values.getDataItems().get(2))
                != SEMANTICS_END_OF_EPOCH) throw HistoryContractCbor.malformed();
        return new Manifest(HistoryContractCbor.uint(values.getDataItems().get(1)),
                HistoryContractCbor.uint(values.getDataItems().get(3)),
                HistoryContractCbor.uintInt(values.getDataItems().get(4)),
                HistoryContractCbor.uintInt(values.getDataItems().get(5)),
                HistoryContractCbor.bytes(values.getDataItems().get(6), 32));
    }

    public static byte[] encodeChunk(Chunk value) {
        Array entries = new Array();
        for (Entry entry : value.entries()) entries.add(entryItem(entry));
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(value.epoch()));
        array.add(new ByteString(value.snapshotRoot()));
        array.add(new UnsignedInteger(value.index()));
        array.add(entries);
        return HistoryContractCbor.encode(array);
    }

    public static Chunk decodeChunk(byte[] bytes) {
        Array values = HistoryContractCbor.decodeArray(bytes, 5);
        if (HistoryContractCbor.uintInt(values.getDataItems().get(0)) != VERSION) {
            throw HistoryContractCbor.malformed();
        }
        Array encodedEntries = HistoryContractCbor.array(values.getDataItems().get(4),
                MAX_CHUNK_ENTRIES);
        List<Entry> entries = new ArrayList<>(encodedEntries.getDataItems().size());
        for (var item : encodedEntries.getDataItems()) {
            Array entry = HistoryContractCbor.array(item, 4);
            if (entry.getDataItems().size() != 4) throw HistoryContractCbor.malformed();
            entries.add(new Entry(HistoryContractCbor.uintInt(entry.getDataItems().get(0)),
                    HistoryContractCbor.bytes(entry.getDataItems().get(1), 28),
                    unsigned(entry.getDataItems().get(2)),
                    HistoryContractCbor.bytes(entry.getDataItems().get(3), 28)));
        }
        return new Chunk(HistoryContractCbor.uint(values.getDataItems().get(1)),
                HistoryContractCbor.bytes(values.getDataItems().get(2), 32),
                HistoryContractCbor.uintInt(values.getDataItems().get(3)), entries);
    }

    public static byte[] encodeValue(Entry entry) {
        Array array = new Array();
        array.add(new UnsignedInteger(entry.coin()));
        array.add(new ByteString(entry.poolHash()));
        return HistoryContractCbor.encode(array);
    }

    public static Value decodeValue(byte[] bytes) {
        Array values = HistoryContractCbor.decodeArray(bytes, 2);
        return new Value(unsigned(values.getDataItems().get(0)),
                HistoryContractCbor.bytes(values.getDataItems().get(1), 28));
    }

    public static byte[] encodeQuery(Query query) {
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(query.epoch()));
        array.add(new UnsignedInteger(query.credType()));
        array.add(new ByteString(query.credHash()));
        return HistoryContractCbor.encode(array);
    }

    public static Query decodeQuery(byte[] bytes) {
        Array values = HistoryContractCbor.decodeArray(bytes, 4);
        if (HistoryContractCbor.uintInt(values.getDataItems().get(0)) != VERSION) {
            throw HistoryContractCbor.malformed();
        }
        return new Query(HistoryContractCbor.uint(values.getDataItems().get(1)),
                HistoryContractCbor.uintInt(values.getDataItems().get(2)),
                HistoryContractCbor.bytes(values.getDataItems().get(3), 28));
    }

    public static byte[] encodeMeta(Meta value) {
        Manifest manifest = value.manifest();
        Array array = new Array();
        array.add(new UnsignedInteger(VERSION));
        array.add(new UnsignedInteger(manifest.epoch()));
        array.add(new UnsignedInteger(SEMANTICS_END_OF_EPOCH));
        array.add(new UnsignedInteger(manifest.totalEntries()));
        array.add(new UnsignedInteger(manifest.chunkEntries()));
        array.add(new UnsignedInteger(manifest.chunkCount()));
        array.add(new ByteString(manifest.snapshotRoot()));
        array.add(new UnsignedInteger(value.receivedChunks()));
        array.add(new UnsignedInteger(value.complete() ? 1 : 0));
        return HistoryContractCbor.encode(array);
    }

    public static Meta decodeMeta(byte[] bytes) {
        Array values = HistoryContractCbor.decodeArray(bytes, 9);
        if (HistoryContractCbor.uintInt(values.getDataItems().get(0)) != VERSION
                || HistoryContractCbor.uintInt(values.getDataItems().get(2))
                != SEMANTICS_END_OF_EPOCH) throw HistoryContractCbor.malformed();
        Manifest manifest = new Manifest(
                HistoryContractCbor.uint(values.getDataItems().get(1)),
                HistoryContractCbor.uint(values.getDataItems().get(3)),
                HistoryContractCbor.uintInt(values.getDataItems().get(4)),
                HistoryContractCbor.uintInt(values.getDataItems().get(5)),
                HistoryContractCbor.bytes(values.getDataItems().get(6), 32));
        int received = HistoryContractCbor.uintInt(values.getDataItems().get(7));
        int complete = HistoryContractCbor.uintInt(values.getDataItems().get(8));
        if (complete > 1) throw HistoryContractCbor.malformed();
        return new Meta(manifest, received, complete == 1);
    }

    public static byte[] chunkHash(List<Entry> entries) {
        Array array = new Array();
        for (Entry entry : entries) array.add(entryItem(entry));
        return Blake2bUtil.blake2bHash256(HistoryContractCbor.encode(array));
    }

    public static byte[] snapshotRoot(List<byte[]> chunkHashes) {
        byte[] current = initialSnapshotRoot();
        for (byte[] chunkHash : chunkHashes) {
            current = appendSnapshotRoot(current, chunkHash);
        }
        return current;
    }

    public static byte[] initialSnapshotRoot() {
        return Blake2bUtil.blake2bHash256(ROOT_DOMAIN);
    }

    public static byte[] appendSnapshotRoot(byte[] current, byte[] chunkHash) {
        if (current == null || current.length != 32 || chunkHash == null || chunkHash.length != 32) {
            throw new IllegalArgumentException("snapshot and chunk hashes must contain 32 bytes");
        }
        return Blake2bUtil.blake2bHash256(
                ByteBuffer.allocate(64).put(current).put(chunkHash).array());
    }

    public static byte[] entryKey(long epoch, int credType, byte[] credHash) {
        if (epoch < 0 || credType < 0 || credType > 1 || credHash.length != 28) {
            throw new IllegalArgumentException("invalid stake entry key");
        }
        return ("stake/" + epoch + "/" + String.format("%02x", credType)
                + java.util.HexFormat.of().formatHex(credHash)).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] metaKey(long epoch) {
        return ("stake/" + epoch + "/meta").getBytes(StandardCharsets.US_ASCII);
    }
    public static byte[] chunkKey(long epoch, int index) {
        return ("stake/" + epoch + "/chunks/" + index).getBytes(StandardCharsets.US_ASCII);
    }
    public static byte[] cursorKey(long epoch) {
        return ("stake/" + epoch + "/cursor").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] credentialOrderKey(int credType, byte[] credHash) {
        if (credType < 0 || credType > 1 || credHash == null || credHash.length != 28) {
            throw new IllegalArgumentException("invalid stake credential order key");
        }
        return ByteBuffer.allocate(29).put((byte) credType).put(credHash).array();
    }
    public static int chunks(long total, int chunkEntries) {
        return total == 0 ? 0 : Math.toIntExact((total + chunkEntries - 1) / chunkEntries);
    }

    public static int compare(Entry left, Entry right) {
        int type = Integer.compare(left.credType(), right.credType());
        return type != 0 ? type : Arrays.compareUnsigned(left.credHash(), right.credHash());
    }

    private static void requireCanonical(List<Entry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            if (compare(entries.get(i - 1), entries.get(i)) >= 0) {
                throw new IllegalArgumentException("stake entries are not in canonical order");
            }
        }
    }

    private static Array entryItem(Entry entry) {
        Array item = new Array();
        item.add(new UnsignedInteger(entry.credType()));
        item.add(new ByteString(entry.credHash()));
        item.add(new UnsignedInteger(entry.coin()));
        item.add(new ByteString(entry.poolHash()));
        return item;
    }

    private static BigInteger unsigned(co.nstant.in.cbor.model.DataItem item) {
        if (!(item instanceof UnsignedInteger integer)) throw HistoryContractCbor.malformed();
        return integer.getValue();
    }
}
