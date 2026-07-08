package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.charset.StandardCharsets;

/**
 * Built-in default app: an append-only ordered log of opaque messages.
 * For every finalized message it writes
 * {@code key = message-id} → {@code cbor([height, index, topic, sender])}
 * into the state trie — so any consumer can obtain an MPF inclusion proof
 * that a given message was finalized at a given position, verifiable against
 * an anchored state root without trusting the nodes (ADR app-layer/005 D10).
 */
public final class OrderedLogStateMachine implements AppStateMachine {

    public static final String ID = "ordered-log";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        int index = 0;
        for (AppMessage message : block.messages()) {
            Array position = new Array();
            position.add(new UnsignedInteger(block.height()));
            position.add(new UnsignedInteger(index));
            position.add(new UnicodeString(message.getTopic() != null ? message.getTopic() : ""));
            position.add(new ByteString(message.getSender()));
            writer.put(message.getMessageId(), CborSerializationUtil.serialize(position));
            index++;
        }
        // Chain tip marker: latest height under a fixed key for cheap lookups/proofs
        writer.put("~tip".getBytes(StandardCharsets.UTF_8),
                CborSerializationUtil.serialize(new UnsignedInteger(block.height())));
    }
}
