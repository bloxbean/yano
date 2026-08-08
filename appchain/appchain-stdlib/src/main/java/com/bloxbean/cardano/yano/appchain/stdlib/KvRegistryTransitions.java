package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.transition.StateMutation;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionCapability;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionDecision;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlan;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure owner-aware KV mutation capability shared by standalone and composed applications. */
public final class KvRegistryTransitions implements
        TransitionCapability<KvRegistryTransitions.Command, KvRegistryTransitions.Facts> {
    public static final int OP_PUT = 0;
    public static final int OP_DELETE = 1;

    public enum ValueFormat {
        RAW, CBOR, UTF8;

        public static ValueFormat parse(String value) {
            if (value == null || value.isBlank()) return RAW;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "machines.kv-registry.value-format must be raw, cbor or utf8: " + value);
            }
        }
    }

    private final ValueFormat valueFormat;

    public KvRegistryTransitions(ValueFormat valueFormat) {
        this.valueFormat = valueFormat != null ? valueFormat : ValueFormat.RAW;
    }

    public Command decodeCommand(byte[] body) {
        KvRegistryContract.Command decoded = KvRegistryContract.decodeCommand(body);
        Command command = new Command(decoded.operation(), decoded.key(), decoded.value());
        if (command.operation() == OP_PUT && command.value().length == 0) {
            throw new IllegalArgumentException("PUT requires a value");
        }
        if (command.operation() == OP_PUT && !conforms(command.value())) {
            throw new IllegalArgumentException(
                    "PUT value does not conform to value-format " + valueFormat);
        }
        return command;
    }

    @Override
    public TransitionDecision decide(Command command, TransitionContext context, Facts facts) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(facts, "facts");
        if (facts.currentEntry().isPresent()
                && !Arrays.equals(decodeOwner(facts.currentEntry().orElseThrow()), context.sender())) {
            return TransitionDecision.reject("KV_NOT_OWNER", "sender does not own the key");
        }
        if (command.operation() == OP_PUT) {
            if (!conforms(command.value())) {
                return TransitionDecision.reject("KV_VALUE_FORMAT", "value format rejected");
            }
            return TransitionDecision.approve(TransitionPlan.mutations(List.of(StateMutation.put(
                    command.key(), encodeEntry(context.sender(), command.value())))));
        }
        if (command.operation() == OP_DELETE && facts.currentEntry().isPresent()) {
            return TransitionDecision.approve(
                    TransitionPlan.mutations(List.of(StateMutation.delete(command.key()))));
        }
        return TransitionDecision.approve(TransitionPlan.empty());
    }

    public boolean conforms(byte[] value) {
        return switch (valueFormat) {
            case RAW -> true;
            case CBOR -> {
                try {
                    if (!StdlibCbor.acceptsNestedValue(value)) yield false;
                    CborSerializationUtil.deserializeOne(value);
                    yield true;
                } catch (Exception failure) {
                    yield false;
                }
            }
            case UTF8 -> {
                try {
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value));
                    yield true;
                } catch (CharacterCodingException failure) {
                    yield false;
                }
            }
        };
    }

    public static byte[] decodeOwner(byte[] entry) {
        return field(entry, 0);
    }

    public static byte[] decodeValue(byte[] entry) {
        return field(entry, 1);
    }

    private static byte[] field(byte[] entry, int index) {
        StdlibCbor.requirePersistedEntry(entry);
        Array array = (Array) CborSerializationUtil.deserializeOne(entry);
        if (array.getDataItems().size() != 2) {
            throw new IllegalArgumentException("invalid KV registry entry");
        }
        return ((ByteString) array.getDataItems().get(index)).getBytes();
    }

    private static byte[] encodeEntry(byte[] owner, byte[] value) {
        Array array = new Array();
        array.add(new ByteString(owner));
        array.add(new ByteString(value));
        return CborSerializationUtil.serialize(array);
    }

    public record Command(int operation, byte[] key, byte[] value) {
        public Command {
            if (operation != OP_PUT && operation != OP_DELETE) {
                throw new IllegalArgumentException("unsupported KV operation");
            }
            key = Objects.requireNonNull(key, "key").clone();
            value = Objects.requireNonNull(value, "value").clone();
        }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    public record Facts(Optional<byte[]> currentEntry) {
        public Facts {
            Objects.requireNonNull(currentEntry, "currentEntry");
            currentEntry = currentEntry.map(byte[]::clone);
        }
        @Override public Optional<byte[]> currentEntry() {
            return currentEntry.map(byte[]::clone);
        }
    }
}
