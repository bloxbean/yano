package com.bloxbean.cardano.yano.api.appchain.sink;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canonical JSON rendering of a finalized app block for stream sinks
 * (webhook, Kafka, ...). Built with Jackson so user-controlled fields are
 * correctly escaped.
 */
public final class AppBlockJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppBlockJson() {
    }

    public static String toJson(AppBlock block) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("chainId", block.chainId());
        root.put("height", block.height());
        root.put("blockHash", HexUtil.encodeHexString(AppBlockCodec.blockHash(block)));
        root.put("stateRoot", HexUtil.encodeHexString(block.stateRoot()));
        root.put("timestamp", block.timestamp());
        ArrayNode messages = root.putArray("messages");
        for (AppMessage message : block.messages()) {
            ObjectNode m = messages.addObject();
            m.put("messageId", message.getMessageIdHex());
            m.put("topic", message.getTopic());
            m.put("sender", HexUtil.encodeHexString(message.getSender()));
            m.put("senderSeq", message.getSenderSeq());
            m.put("bodyHex", HexUtil.encodeHexString(message.getBody()));
        }
        return root.toString();
    }
}
