package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Version 1 append, head, and verification contract for {@code doc-trail}. */
public final class DocTrailContract {
    public static final String STATE_MACHINE_ID = "doc-trail";
    public static final String DEFAULT_TOPIC = "doc-trail.command.v1";
    private static final byte[] GENESIS_HEAD = new byte[32];

    private DocTrailContract() {
    }

    public static byte[] append(String entityId, byte[] entryHash, String reference) {
        validate(entityId, entryHash);
        Array array = new Array();
        array.add(new UnicodeString(entityId));
        array.add(new ByteString(entryHash));
        array.add(new UnicodeString(reference != null ? reference : ""));
        return StdlibContractCbor.encode(array);
    }

    public static Append decodeCommand(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
        Append command = new Append(StdlibContractCbor.text(values.get(0)),
                StdlibContractCbor.bytes(values.get(1)), StdlibContractCbor.text(values.get(2)));
        validate(command.entityId(), command.entryHash());
        return command;
    }

    public static byte[] entityKey(String entityId) {
        StdlibContractCbor.requireText(entityId, "entityId");
        byte[] key = ("e/" + entityId).getBytes(StandardCharsets.UTF_8);
        StdlibContractCbor.requireStateKey(key, "document trail state key");
        return key;
    }

    public static Head decodeHead(byte[] bytes) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(bytes, 2).getDataItems();
        return new Head(StdlibContractCbor.uint(values.get(0)),
                StdlibContractCbor.bytes(values.get(1), 32));
    }

    public static byte[] computeHead(List<byte[]> entryHashes, List<byte[]> authors) {
        if (entryHashes == null || authors == null || entryHashes.size() != authors.size()) {
            throw new IllegalArgumentException("entryHashes and authors must have equal size");
        }
        byte[] head = GENESIS_HEAD;
        for (int index = 0; index < entryHashes.size(); index++) {
            byte[] hash = entryHashes.get(index);
            byte[] author = authors.get(index);
            if (hash == null || hash.length == 0 || author == null || author.length != 32) {
                throw new IllegalArgumentException("trail hashes must be non-empty and authors 32 bytes");
            }
            head = Blake2bUtil.blake2bHash256(concat(head, hash, author));
        }
        return head;
    }

    private static void validate(String entityId, byte[] entryHash) {
        entityKey(entityId);
        if (entryHash == null || entryHash.length == 0
                || entryHash.length > StdlibContractCbor.MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("entryHash must be non-empty and bounded");
        }
    }

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length += value.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    public record Append(String entityId, byte[] entryHash, String reference) {
        public Append { entryHash = entryHash.clone(); }
        @Override public byte[] entryHash() { return entryHash.clone(); }
    }

    public record Head(long count, byte[] headHash) {
        public Head { headHash = headHash.clone(); }
        @Override public byte[] headHash() { return headHash.clone(); }
    }
}
