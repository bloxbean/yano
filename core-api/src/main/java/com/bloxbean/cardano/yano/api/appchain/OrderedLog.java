package com.bloxbean.cardano.yano.api.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.nio.charset.StandardCharsets;

/**
 * The canonical append-only-log record format shared by state machines that
 * make finalized messages provable (the built-in {@code ordered-log} and the ZK
 * gate). Records {@code message-id → cbor([height, index, topic, sender])} plus
 * a {@code ~tip} height marker — so any consumer can obtain an MPF inclusion
 * proof that a message was finalized at a given position (ADR app-layer/005 D10).
 * Kept in one place so inclusion proofs never diverge between machines.
 */
public final class OrderedLog {

    /** Fixed key holding the latest finalized height. */
    public static final byte[] TIP_KEY = "~tip".getBytes(StandardCharsets.UTF_8);

    private OrderedLog() {
    }

    /** Record one finalized message at {@code (height, index)}. */
    public static void recordMessage(AppStateWriter writer, long height, int index, AppMessage message) {
        Array position = new Array();
        position.add(new UnsignedInteger(height));
        position.add(new UnsignedInteger(index));
        position.add(new UnicodeString(message.getTopic() != null ? message.getTopic() : ""));
        position.add(new ByteString(message.getSender()));
        writer.put(message.getMessageId(), CborSerializationUtil.serialize(position));
    }

    /** Write the tip marker for a finalized block height. */
    public static void recordTip(AppStateWriter writer, long height) {
        writer.put(TIP_KEY, CborSerializationUtil.serialize(new UnsignedInteger(height)));
    }
}
