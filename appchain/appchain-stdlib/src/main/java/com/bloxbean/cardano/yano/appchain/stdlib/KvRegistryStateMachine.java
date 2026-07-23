package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;

/**
 * Standard-library state machine {@code kv-registry} (ADR app-layer/006 E2.1):
 * a replicated registry with per-key ownership — the first member to write a
 * key becomes its owner; only the owner may update or delete it. Every entry
 * is individually provable (key → MPF inclusion proof of [owner, value]).
 * <p>
 * Command body (CBOR): {@code [op(uint), key(bstr), value(bstr)]}
 * — op 0 = PUT, op 1 = DELETE (value empty).
 * <p>
 * State entry (CBOR): {@code key → [owner(bstr .size 32), value(bstr)]}.
 * <p>
 * Optional value-format check (ADR app-layer/008.1 I1.4, config
 * {@code machines.kv-registry.value-format = raw | cbor | utf8}): a PUT whose
 * value does not conform is rejected at admission and is a deterministic
 * no-op in {@link #apply} (consensus-enforced, same posture as ownership).
 * <p>
 * Use cases: token/metadata registries, DID documents, allow/deny lists,
 * shared configuration.
 */
public final class KvRegistryStateMachine implements AppStateMachine {

    public static final String ID = "kv-registry";
    public static final int OP_PUT = 0;
    public static final int OP_DELETE = 1;

    private static final Logger log = LoggerFactory.getLogger(KvRegistryStateMachine.class);

    /** Optional structural constraint on PUT values. */
    public enum ValueFormat {
        /** No constraint (default). */
        RAW,
        /** Value must be a single well-formed CBOR item. */
        CBOR,
        /** Value must be valid UTF-8 text. */
        UTF8;

        public static ValueFormat parse(String value) {
            if (value == null || value.isBlank()) {
                return RAW;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "machines.kv-registry.value-format must be raw, cbor or utf8: " + value);
            }
        }
    }

    private final ValueFormat valueFormat;

    public KvRegistryStateMachine() {
        this(ValueFormat.RAW);
    }

    public KvRegistryStateMachine(ValueFormat valueFormat) {
        this.valueFormat = valueFormat != null ? valueFormat : ValueFormat.RAW;
    }

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
            if (command.op() == OP_PUT && !conforms(command.value())) {
                return AdmissionResult.reject("PUT value does not conform to value-format " + valueFormat);
            }
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed kv-registry command: expected cbor [op, key, value]");
        }
    }

    private boolean conforms(byte[] value) {
        return switch (valueFormat) {
            case RAW -> true;
            case CBOR -> {
                try {
                    if (!StdlibCbor.acceptsNestedValue(value)) {
                        yield false;
                    }
                    CborSerializationUtil.deserializeOne(value);
                    yield true;
                } catch (Exception e) {
                    yield false;
                }
            }
            case UTF8 -> {
                try {
                    java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(value));
                    yield true;
                } catch (java.nio.charset.CharacterCodingException e) {
                    yield false;
                }
            }
        };
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
                if (!conforms(command.value())) {
                    log.debug("kv-registry: non-conforming value rejected (block {}, format {})",
                            block.height(), valueFormat);
                    continue; // deterministic no-op — consensus-enforced format check
                }
                writer.put(command.key(), encodeEntry(sender, command.value()));
            } else if (command.op() == OP_DELETE && existing.isPresent()) {
                writer.delete(command.key());
            }
        }
    }

    /** Client/helper: encode a PUT command body. */
    public static byte[] put(byte[] key, byte[] value) {
        return KvRegistryContract.put(key, value);
    }

    /** Client/helper: encode a DELETE command body. */
    public static byte[] delete(byte[] key) {
        return KvRegistryContract.delete(key);
    }

    /** Decode a state entry into [owner, value]. */
    public static byte[] decodeOwner(byte[] entry) {
        StdlibCbor.requirePersistedEntry(entry);
        Array arr = (Array) CborSerializationUtil.deserializeOne(entry);
        return ((ByteString) arr.getDataItems().get(0)).getBytes();
    }

    public static byte[] decodeValue(byte[] entry) {
        StdlibCbor.requirePersistedEntry(entry);
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
            KvRegistryContract.Command decoded = KvRegistryContract.decodeCommand(body);
            return new Command(decoded.operation(), decoded.key(), decoded.value());
        }
    }
}
