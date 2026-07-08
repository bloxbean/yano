package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Portable JSON serialization of an {@link EvidenceBundle} (ADR app-layer/006
 * E3.4). Blocks are carried as canonical CBOR hex so an auditor reconstructs
 * the exact {@link AppBlock} and re-verifies hashes/certs byte-for-byte.
 */
public final class EvidenceBundleCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceBundleCodec() {
    }

    public static String toJson(EvidenceBundle bundle) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("chainId", bundle.chainId());
        root.put("messageId", bundle.messageIdHex());

        ArrayNode blocks = root.putArray("blocksCbor");
        for (AppBlock block : bundle.blocks()) {
            blocks.add(HexUtil.encodeHexString(AppBlockCodec.serialize(block)));
        }
        ArrayNode members = root.putArray("members");
        bundle.memberKeysHex().forEach(members::add);

        if (bundle.anchor() != null) {
            ObjectNode anchor = root.putObject("anchor");
            anchor.put("anchoredHeight", bundle.anchor().anchoredHeight());
            anchor.put("anchoredBlockHash", bundle.anchor().anchoredBlockHashHex());
            anchor.put("txHash", bundle.anchor().txHash());
            anchor.put("l1Slot", bundle.anchor().l1Slot());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Evidence bundle JSON encode failed", e);
        }
    }

    public static EvidenceBundle fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<AppBlock> blocks = new ArrayList<>();
            for (JsonNode blockHex : root.path("blocksCbor")) {
                blocks.add(AppBlockCodec.deserialize(HexUtil.decodeHexString(blockHex.asText())));
            }
            List<String> members = new ArrayList<>();
            for (JsonNode member : root.path("members")) {
                members.add(member.asText());
            }
            EvidenceBundle.AnchorRef anchor = null;
            if (root.hasNonNull("anchor")) {
                JsonNode a = root.get("anchor");
                anchor = new EvidenceBundle.AnchorRef(
                        a.path("anchoredHeight").asLong(),
                        a.path("anchoredBlockHash").asText(),
                        a.path("txHash").asText(),
                        a.path("l1Slot").asLong());
            }
            return new EvidenceBundle(root.path("chainId").asText(),
                    root.path("messageId").asText(), blocks, members, anchor);
        } catch (IOException e) {
            throw new UncheckedIOException("Evidence bundle JSON decode failed", e);
        }
    }
}
