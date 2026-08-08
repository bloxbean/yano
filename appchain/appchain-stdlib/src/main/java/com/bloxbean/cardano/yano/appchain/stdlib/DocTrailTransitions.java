package com.bloxbean.cardano.yano.appchain.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.transition.StateMutation;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionCapability;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionDecision;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlan;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure document-trail append capability shared by standalone and composed applications. */
public final class DocTrailTransitions implements
        TransitionCapability<DocTrailTransitions.Append, DocTrailTransitions.Facts> {
    private static final byte[] GENESIS_HEAD = new byte[32];

    public static Append decodeCommand(byte[] body) {
        DocTrailContract.Append command = DocTrailContract.decodeCommand(body);
        return new Append(command.entityId(), command.entryHash(), command.reference());
    }

    public static Facts facts(AppStateReader state, Append command) {
        return new Facts(state.get(DocTrailContract.entityKey(command.entityId()))
                .map(DocTrailTransitions::decodeHead));
    }

    public TransitionDecision decide(
            byte[] encodedCommand,
            TransitionContext context,
            AppStateReader state
    ) {
        Append command = decodeCommand(encodedCommand);
        return decide(command, context, facts(state, command));
    }

    @Override
    public TransitionDecision decide(Append command, TransitionContext context, Facts facts) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(facts, "facts");
        TrailHead current = facts.current().orElse(new TrailHead(0, GENESIS_HEAD));
        byte[] newHead = Blake2bUtil.blake2bHash256(
                concat(current.headHash(), command.entryHash(), context.sender()));
        return TransitionDecision.approve(TransitionPlan.mutations(List.of(StateMutation.put(
                DocTrailContract.entityKey(command.entityId()),
                encodeHead(new TrailHead(Math.addExact(current.count(), 1), newHead))))));
    }

    public static TrailHead decodeHead(byte[] bytes) {
        StdlibCbor.requirePersistedEntry(bytes);
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(bytes)).getDataItems();
        if (items.size() != 2) {
            throw new IllegalArgumentException("invalid document-trail head");
        }
        return new TrailHead(
                ((UnsignedInteger) items.get(0)).getValue().longValueExact(),
                ((ByteString) items.get(1)).getBytes());
    }

    public static byte[] encodeHead(TrailHead head) {
        Array value = new Array();
        value.add(new UnsignedInteger(head.count()));
        value.add(new ByteString(head.headHash()));
        return CborSerializationUtil.serialize(value);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length = Math.addExact(length, part.length);
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    public record Append(String entityId, byte[] entryHash, String reference) {
        public Append {
            entityId = Objects.requireNonNull(entityId, "entityId");
            entryHash = Objects.requireNonNull(entryHash, "entryHash").clone();
            reference = reference != null ? reference : "";
        }
        @Override public byte[] entryHash() { return entryHash.clone(); }
    }

    public record Facts(Optional<TrailHead> current) {
        public Facts { current = Objects.requireNonNull(current, "current"); }
    }

    public record TrailHead(long count, byte[] headHash) {
        public TrailHead {
            if (count < 0) throw new IllegalArgumentException("count must be non-negative");
            headHash = Objects.requireNonNull(headHash, "headHash").clone();
            if (headHash.length != 32) throw new IllegalArgumentException("headHash must be 32 bytes");
        }
        @Override public byte[] headHash() { return headHash.clone(); }
    }
}
