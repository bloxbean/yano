package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Portable JSON serialization of an {@link EvidenceBundle} (ADR app-layer/006
 * E3.4). Blocks are carried as canonical CBOR hex so an auditor reconstructs
 * the exact {@link AppBlock} and re-verifies its signed header commitments,
 * message-id root, and finality signatures from canonical bytes.
 * Decoding is strict: duplicate/trailing JSON, unknown fields, coerced numeric
 * types, non-canonical CBOR/hex, duplicate members, and invalid thresholds are
 * rejected before trust-context verification.
 */
public final class EvidenceBundleCodec {

    /** Maximum accepted UTF-8 evidence document size and parser input-unit budget. */
    public static final int MAX_JSON_BYTES = 40 * 1024 * 1024;
    private static final int MAX_BLOCKS = EvidenceBundle.MAX_BLOCKS;
    private static final int MAX_MEMBERS = AppChainConfig.MAX_MEMBERS;
    private static final int MAX_BLOCK_MESSAGES = AppChainConfig.MAX_BLOCK_MESSAGES;
    private static final int MAX_JSON_TOKENS = 10_000;
    private static final int MAX_BLOCK_CBOR_BYTES = (int) AppChainConfig.MAX_BLOCK_BYTES;
    private static final long MAX_TOTAL_BLOCK_CBOR_BYTES =
            EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_JSON_BYTES)
                    .maxTokenCount(MAX_JSON_TOKENS)
                    .maxNestingDepth(4)
                    .maxStringLength(MAX_BLOCK_CBOR_BYTES * 2)
                    .maxNameLength(32)
                    .maxNumberLength(20)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = JsonMapper.builder(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> FIELDS = Set.of(
            "chainId", "messageId", "blocksCbor", "members", "threshold", "anchor");
    private static final Set<String> ANCHOR_FIELDS = Set.of(
            "anchoredHeight", "anchoredBlockHash", "txHash", "l1Slot");
    private EvidenceBundleCodec() {
    }

    public static String toJson(EvidenceBundle bundle) {
        if (bundle == null || bundle.blocks() == null || bundle.blocks().isEmpty()
                || bundle.blocks().size() > MAX_BLOCKS) {
            throw invalid();
        }
        List<byte[]> encodedBlockBytes = new ArrayList<>(bundle.blocks().size());
        long totalBlockBytes = 0;
        for (AppBlock block : bundle.blocks()) {
            final byte[] encoded;
            try {
                encoded = AppBlockCodec.serialize(block);
            } catch (RuntimeException malformed) {
                throw invalid();
            }
            if (encoded.length > MAX_BLOCK_CBOR_BYTES
                    || exceedsTotalBlockBudget(totalBlockBytes, encoded.length)) {
                throw invalid();
            }
            totalBlockBytes += encoded.length;
            encodedBlockBytes.add(encoded);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("chainId", bundle.chainId());
        root.put("messageId", bundle.messageIdHex());

        ArrayNode blocks = root.putArray("blocksCbor");
        for (byte[] encoded : encodedBlockBytes) {
            blocks.add(HexUtil.encodeHexString(encoded));
        }
        ArrayNode members = root.putArray("members");
        bundle.memberKeysHex().forEach(members::add);
        root.put("threshold", bundle.threshold());

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
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_BYTES) {
            throw invalid();
        }
        try {
            return fromTree(MAPPER.readTree(json));
        } catch (IOException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Strict byte-oriented decode that avoids a second large UTF-16 copy. */
    public static EvidenceBundle fromJson(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_JSON_BYTES) {
            throw invalid();
        }
        try {
            return fromTree(MAPPER.readTree(json));
        } catch (IOException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static EvidenceBundle fromTree(JsonNode root) {
        JsonNode encodedBlocks = root == null ? null : root.get("blocksCbor");
        JsonNode encodedMembers = root == null ? null : root.get("members");
        if (root == null || !root.isObject() || !exactFields(root, FIELDS, false)
                || !boundedChainId(root.get("chainId"))
                || !hex32(root.get("messageId"))
                || encodedBlocks == null || !encodedBlocks.isArray()
                || encodedBlocks.isEmpty() || encodedBlocks.size() > MAX_BLOCKS
                || encodedMembers == null || !encodedMembers.isArray()
                || encodedMembers.isEmpty() || encodedMembers.size() > MAX_MEMBERS
                || !positiveInt(root.get("threshold"))) {
            throw invalid();
        }
        List<AppBlock> blocks = new ArrayList<>(encodedBlocks.size());
        long totalBlockBytes = 0;
        for (JsonNode blockHex : encodedBlocks) {
            if (!blockHex.isTextual() || !lowerHex(blockHex.textValue())
                    || (blockHex.textValue().length() & 1) != 0
                    || blockHex.textValue().length() > MAX_BLOCK_CBOR_BYTES * 2) {
                throw invalid();
            }
            int encodedLength = blockHex.textValue().length() / 2;
            if (exceedsTotalBlockBudget(totalBlockBytes, encodedLength)) {
                throw invalid();
            }
            totalBlockBytes += encodedLength;
            byte[] encodedBlock = HexUtil.decodeHexString(blockHex.textValue());
            AppBlock block = AppBlockCodec.deserializeCanonical(
                    encodedBlock, MAX_BLOCK_CBOR_BYTES);
            if (!validDecodedBlockProfile(block)) {
                throw invalid();
            }
            blocks.add(block);
        }
        List<String> members = new ArrayList<>(encodedMembers.size());
        Set<String> uniqueMembers = new HashSet<>();
        for (JsonNode member : encodedMembers) {
            if (!hex32(member) || !uniqueMembers.add(member.textValue())) {
                throw invalid();
            }
            members.add(member.textValue());
        }
        int threshold = root.get("threshold").intValue();
        if (threshold > members.size()) {
            throw invalid();
        }
        EvidenceBundle.AnchorRef anchor = null;
        if (root.hasNonNull("anchor")) {
            JsonNode a = root.get("anchor");
            if (!a.isObject() || !exactFields(a, ANCHOR_FIELDS, true)
                    || !nonNegativeLong(a.get("anchoredHeight"))
                    || !hex32(a.get("anchoredBlockHash"))
                    || !hex32(a.get("txHash"))
                    || !nonNegativeLong(a.get("l1Slot"))) {
                throw invalid();
            }
            anchor = new EvidenceBundle.AnchorRef(
                    a.get("anchoredHeight").longValue(),
                    a.get("anchoredBlockHash").textValue(),
                    a.get("txHash").textValue(),
                    a.get("l1Slot").longValue());
        } else if (root.has("anchor") && !root.get("anchor").isNull()) {
            throw invalid();
        }
        return new EvidenceBundle(root.get("chainId").textValue(),
                root.get("messageId").textValue(), blocks, members, threshold, anchor);
    }

    static boolean exceedsTotalBlockBudget(long accumulated, long next) {
        return accumulated < 0 || next < 0
                || next > MAX_TOTAL_BLOCK_CBOR_BYTES
                || accumulated > MAX_TOTAL_BLOCK_CBOR_BYTES - next;
    }

    private static boolean exactFields(JsonNode object, Set<String> allowed,
                                       boolean requireEveryField) {
        Set<String> present = new HashSet<>();
        object.fieldNames().forEachRemaining(present::add);
        return allowed.containsAll(present)
                && (!requireEveryField || present.equals(allowed))
                && present.containsAll(Set.of("chainId", "messageId", "blocksCbor",
                "members", "threshold").stream().filter(allowed::contains).toList());
    }

    private static boolean boundedChainId(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return false;
        }
        String value = node.textValue();
        return !value.isBlank() && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length
                <= AppChainConfig.MAX_CHAIN_ID_BYTES;
    }

    private static boolean hex32(JsonNode node) {
        return node != null && node.isTextual()
                && node.textValue().matches("[0-9a-f]{64}");
    }

    private static boolean positiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                && node.intValue() > 0;
    }

    private static boolean nonNegativeLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong()
                && node.longValue() >= 0;
    }

    private static boolean lowerHex(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char next = value.charAt(index);
            if (!((next >= '0' && next <= '9') || (next >= 'a' && next <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validDecodedBlockProfile(AppBlock block) {
        if (block == null || block.version() != AppBlock.BLOCK_VERSION
                || block.chainId() == null || block.chainId().isBlank()
                || block.chainId().getBytes(StandardCharsets.UTF_8).length
                > AppChainConfig.MAX_CHAIN_ID_BYTES
                || block.height() < 1 || block.l1Slot() < 0 || block.timestamp() < 0
                || !bytes(block.prevHash(), 32) || !bytes(block.messagesRoot(), 32)
                || !bytes(block.stateRoot(), 32) || !bytes(block.proposer(), 32)
                || block.l1BlockHash() == null
                || block.l1Slot() == 0 && block.l1BlockHash().length != 0
                || block.l1Slot() > 0 && block.l1BlockHash().length != 32
                || block.messages() == null || block.messages().size() > MAX_BLOCK_MESSAGES
                || block.cert() == null || block.cert().scheme() != FinalityCert.SCHEME_ED25519
                || block.cert().signatures().size() > MAX_MEMBERS) {
            return false;
        }
        for (AppMessage message : block.messages()) {
            if (message == null || message.getVersion() != AppMessage.ENVELOPE_VERSION
                    || !bytes(message.getMessageId(), 32)
                    || !block.chainId().equals(message.getChainId())
                    || !boundedUtf8(message.getTopic(), AppChainConfig.MAX_TOPIC_BYTES)
                    || !bytes(message.getSender(), 32)
                    || message.getSenderSeq() < 0 || message.getExpiresAt() < 0
                    || message.getBody() == null
                    || message.getBody().length > AppChainConfig.MAX_MESSAGE_BYTES
                    || message.getAuthScheme() != FinalityCert.SCHEME_ED25519
                    || message.getAuthProof() == null
                    || !(message.getAuthProof().length
                    == AppChainConfig.ED25519_SIGNATURE_BYTES
                    || message.getAuthProof().length == 0
                    && message.getBody().length == 0)) {
                return false;
            }
        }
        for (FinalityCert.Signature signature : block.cert().signatures()) {
            if (signature == null || !bytes(signature.signer(), 32)
                    || !bytes(signature.signature(), 64)) {
                return false;
            }
        }
        return true;
    }

    private static boolean bytes(byte[] value, int expectedLength) {
        return value != null && value.length == expectedLength;
    }

    private static boolean boundedUtf8(String value, int maximumBytes) {
        return value != null && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid evidence bundle JSON");
    }
}
