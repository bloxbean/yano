package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Standard-library state machine {@code kv-registry} (ADR app-layer/006 E2.1):
 * a replicated registry with per-key ownership — the first member to write a
 * key becomes its owner; only the owner may update or delete it. Every entry
 * is individually provable (key → MPF inclusion proof of [owner, value]).
 * <p>
 * Command body (CBOR): {@code [op(uint), key(bstr), value(bstr)]}
 * — op 0 = PUT (value required), op 1 = DELETE (value ignored/empty).
 * <p>
 * State entry (CBOR): {@code key → [owner(bstr .size 32), value(bstr)]}.
 * <p>
 * Use cases: token/metadata registries, DID documents, allow/deny lists,
 * shared configuration.
 */
public final class KvRegistryStateMachine implements AppStateMachine {

    public static final String ID = "kv-registry";
    public static final int OP_PUT = 0;
    public static final int OP_DELETE = 1;

    private static final Logger log = LoggerFactory.getLogger(KvRegistryStateMachine.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            Command command = Command.decode(message.getBody());
            if (command.op() == OP_PUT && (command.value() == null || command.value().length == 0)) {
                return AdmissionResult.reject("PUT requires a value");
            }
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed kv-registry command: expected cbor [op, key, value]");
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        for (AppMessage message : block.messages()) {
            Command command;
            try {
                command = Command.decode(message.getBody());
            } catch (Exception e) {
                // Should have been filtered at admission; skip deterministically
                continue;
            }
            byte[] sender = message.getSender();
            Optional<byte[]> existing = writer.get(command.key());
            if (existing.isPresent()) {
                byte[] owner = decodeOwner(existing.get());
                if (!Arrays.equals(owner, sender)) {
                    log.debug("kv-registry: non-owner update of key rejected (block {}, sender {})",
                            block.height(), message.getMessageIdHex());
                    continue; // deterministic no-op — not the owner
                }
            }
            if (command.op() == OP_PUT) {
                writer.put(command.key(), encodeEntry(sender, command.value()));
            } else if (command.op() == OP_DELETE && existing.isPresent()) {
                writer.delete(command.key());
            }
        }
    }

    /** Client/helper: encode a PUT command body. */
    public static byte[] put(byte[] key, byte[] value) {
        return Command.encode(OP_PUT, key, value);
    }

    /** Client/helper: encode a DELETE command body. */
    public static byte[] delete(byte[] key) {
        return Command.encode(OP_DELETE, key, new byte[0]);
    }

    /** Decode a state entry into [owner, value]. */
    public static byte[] decodeOwner(byte[] entry) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(entry);
        return ((ByteString) arr.getDataItems().get(0)).getBytes();
    }

    public static byte[] decodeValue(byte[] entry) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(entry);
        return ((ByteString) arr.getDataItems().get(1)).getBytes();
    }

    private static byte[] encodeEntry(byte[] owner, byte[] value) {
        Array arr = new Array();
        arr.add(new ByteString(owner));
        arr.add(new ByteString(value));
        return CborSerializationUtil.serialize(arr);
    }

    record Command(int op, byte[] key, byte[] value) {
        static Command decode(byte[] body) {
            List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
            int op = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (op != OP_PUT && op != OP_DELETE) {
                throw new IllegalArgumentException("Unknown op: " + op);
            }
            byte[] key = ((ByteString) items.get(1)).getBytes();
            if (key.length == 0) {
                throw new IllegalArgumentException("Empty key");
            }
            byte[] value = ((ByteString) items.get(2)).getBytes();
            return new Command(op, key, value);
        }

        static byte[] encode(int op, byte[] key, byte[] value) {
            Array arr = new Array();
            arr.add(new UnsignedInteger(op));
            arr.add(new ByteString(key));
            arr.add(new ByteString(value != null ? value : new byte[0]));
            return CborSerializationUtil.serialize(arr);
        }
    }
}
