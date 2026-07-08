package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Standard-library state machine {@code doc-trail} (ADR app-layer/006 E2.4):
 * append-only per-entity event trails keyed by an external id ({@code productId},
 * {@code caseId}, ...). Each entity accumulates an ordered, tamper-evident list
 * of entry hashes; every entity's trail head is a provable state key.
 * <p>
 * Command (CBOR body): {@code [entityId(tstr), entryHash(bstr), ref(tstr)]}
 * — {@code entryHash} is the app-level hash of the (off-chain) document/event,
 * {@code ref} an optional locator (URL, IPFS CID, doc id). Bodies stay small:
 * documents live off-chain, the trail records their hashes.
 * <p>
 * State per entity ({@code "e/" + entityId}):
 * {@code count(uint), head-hash(bstr32)} where head-hash chains the entries:
 * {@code head_n = blake2b(head_{n-1} ‖ entryHash_n ‖ author)} (genesis head = 32 zero bytes).
 * The full entry list is recoverable from the block history; the head proves
 * the entity's entire ordered trail against the (anchorable) state root.
 * <p>
 * Use cases: Digital Product Passport, supply-chain trails, case/evidence
 * management.
 */
public final class DocTrailStateMachine implements AppStateMachine {

    public static final String ID = "doc-trail";
    private static final byte[] GENESIS_HEAD = new byte[32];

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            Command.decode(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed doc-trail command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            Command command;
            try {
                command = Command.decode(message.getBody());
            } catch (Exception e) {
                continue;
            }
            byte[] key = entityKey(command.entityId());
            Entry current = writer.get(key).map(Entry::decode)
                    .orElse(new Entry(0, GENESIS_HEAD));

            byte[] combined = concat(current.headHash(), command.entryHash(), message.getSender());
            byte[] newHead = Blake2bUtil.blake2bHash256(combined);
            writer.put(key, new Entry(current.count() + 1, newHead).encode());
        }
    }

    // ------------------------------------------------------------------
    // Client/helper encoding + queries
    // ------------------------------------------------------------------

    public static byte[] append(String entityId, byte[] entryHash, String ref) {
        Array arr = new Array();
        arr.add(new UnicodeString(entityId));
        arr.add(new ByteString(entryHash));
        arr.add(new UnicodeString(ref != null ? ref : ""));
        return CborSerializationUtil.serialize(arr);
    }

    public static byte[] entityKey(String entityId) {
        return ("e/" + entityId).getBytes(StandardCharsets.UTF_8);
    }

    public static Entry decodeEntry(byte[] stateValue) {
        return Entry.decode(stateValue);
    }

    /**
     * Recompute an entity's expected head from its ordered (entryHash, author)
     * sequence — lets a verifier confirm a claimed trail against the proven head.
     */
    public static byte[] computeHead(List<byte[]> entryHashes, List<byte[]> authors) {
        byte[] head = GENESIS_HEAD;
        for (int i = 0; i < entryHashes.size(); i++) {
            head = Blake2bUtil.blake2bHash256(concat(head, entryHashes.get(i), authors.get(i)));
        }
        return head;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] part : parts) {
            len += part.length;
        }
        byte[] out = new byte[len];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    record Command(String entityId, byte[] entryHash, String ref) {
        static Command decode(byte[] body) {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
            String entityId = ((UnicodeString) items.get(0)).getString();
            if (entityId.isBlank()) {
                throw new IllegalArgumentException("Empty entityId");
            }
            byte[] entryHash = ((ByteString) items.get(1)).getBytes();
            if (entryHash.length == 0) {
                throw new IllegalArgumentException("Empty entryHash");
            }
            String ref = ((UnicodeString) items.get(2)).getString();
            return new Command(entityId, entryHash, ref);
        }
    }

    /** Per-entity trail head: number of entries and the running chained hash. */
    public record Entry(long count, byte[] headHash) {
        byte[] encode() {
            Array arr = new Array();
            arr.add(new UnsignedInteger(count));
            arr.add(new ByteString(headHash));
            return CborSerializationUtil.serialize(arr);
        }

        static Entry decode(byte[] bytes) {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes)).getDataItems();
            return new Entry(
                    ((UnsignedInteger) items.get(0)).getValue().longValue(),
                    ((ByteString) items.get(1)).getBytes());
        }
    }
}
