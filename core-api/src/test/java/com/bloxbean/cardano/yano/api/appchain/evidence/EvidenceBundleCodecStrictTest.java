package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceBundleCodecStrictTest {
    private static final String MESSAGE = "aa".repeat(32);
    private static final String MEMBER = "bb".repeat(32);

    @Test
    void roundTripsCanonicalEnvelopeAndRejectsCoercionUnknownsAndDuplicates() {
        String canonical = envelope(blockHex(), "[\"" + MEMBER + "\"]", "1", "");
        EvidenceBundle decoded = EvidenceBundleCodec.fromJson(canonical);

        assertThat(decoded.chainId()).isEqualTo("chain");
        assertThat(decoded.threshold()).isEqualTo(1);
        assertThat(EvidenceBundleCodec.fromJson(
                canonical.getBytes(StandardCharsets.UTF_8)).chainId()).isEqualTo("chain");
        for (String malformed : List.of(
                envelope(blockHex(), "[\"" + MEMBER + "\"]", "1.0", ""),
                envelope(blockHex(), "[\"" + MEMBER + "\"]", "\"1\"", ""),
                envelope(blockHex(), "[\"" + MEMBER + "\"]", "0", ""),
                envelope(blockHex(), "[\"" + MEMBER + "\"]", "2", ""),
                envelope(blockHex(), "[\"" + MEMBER + "\",\"" + MEMBER + "\"]", "1", ""),
                envelope(blockHex(), "[\"" + MEMBER.toUpperCase() + "\"]", "1", ""),
                envelope(blockHex().toUpperCase(), "[\"" + MEMBER + "\"]", "1", ""),
                envelope(blockHex(), "[\"" + MEMBER + "\"]", "1", ",\"extra\":1"),
                canonical.substring(0, canonical.length() - 1) + ",\"threshold\":1}",
                canonical + "{}")) {
            assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(malformed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDeepBlockCborBeforeRecursiveDecoderWithoutEscapingError() {
        String hostile = HexFormat.of().formatHex(nestedIndefiniteArrays(3_000));
        String envelope = envelope(hostile, "[\"" + MEMBER + "\"]", "1", "");

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(envelope))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTokenAndArrayFloodsBeforeDecodingElements() {
        String tokenFlood = envelopeWithBlocks(String.join(",",
                Collections.nCopies(12_000, "\"00\"")));
        String blockArrayFlood = envelopeWithBlocks(String.join(",",
                Collections.nCopies(4_097, "\"00\"")));

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(tokenFlood))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(
                blockArrayFlood.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCanonicalBlockWithOversizedSignerProfile() {
        AppBlock malformed = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                new byte[32], filled(1), List.of(), new byte[33], FinalityCert.empty());
        String encoded = HexFormat.of().formatHex(AppBlockCodec.serialize(malformed));

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(
                envelope(encoded, "[\"" + MEMBER + "\"]", "1", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMembershipBeyondTheFrameworkV1Limit() {
        String members = String.join(",", java.util.stream.IntStream.rangeClosed(
                        0, AppChainConfig.MAX_MEMBERS)
                .mapToObj(index -> "\"" + String.format("%064x", index) + "\"")
                .toList());

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(
                envelope(blockHex(), "[" + members + "]", "1", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsChainIdBeyondTheUtf8ByteLimit() {
        String oversized = "é".repeat(AppChainConfig.MAX_CHAIN_ID_BYTES);
        String json = envelope(blockHex(), "[\"" + MEMBER + "\"]", "1", "")
                .replace("\"chainId\":\"chain\"",
                        "\"chainId\":\"" + oversized + "\"");

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence bundle JSON");
    }

    @Test
    void acceptsTheExactV1MessageCountAndCborItemBoundary() {
        AppMessage message = profileMessage("chain", "topic");
        List<AppMessage> messages = Collections.nCopies(
                AppChainConfig.MAX_BLOCK_MESSAGES, message);
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                new byte[32], filled(1), messages, filled(2), FinalityCert.empty());
        EvidenceBundle bundle = new EvidenceBundle("chain", MESSAGE, List.of(block),
                List.of(MEMBER), 1, null);

        EvidenceBundle decoded = EvidenceBundleCodec.fromJson(
                EvidenceBundleCodec.toJson(bundle));

        assertThat(decoded.blocks().getFirst().messages())
                .hasSize(AppChainConfig.MAX_BLOCK_MESSAGES);
    }

    @Test
    void rejectsProgrammaticEnvelopeAboveTheCumulativeBlockByteBudget() {
        AppMessage message = AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(filled(3))
                .chainId("chain")
                .topic("topic")
                .sender(filled(4))
                .senderSeq(1)
                .expiresAt(1)
                .body(new byte[1024 * 1024])
                .authScheme(FinalityCert.SCHEME_ED25519)
                .authProof(new byte[0])
                .build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                AppBlockCodec.messagesRoot(List.of(message)), filled(1),
                List.of(message), filled(2), FinalityCert.empty());
        int encodedBlockBytes = AppBlockCodec.serialize(block).length;
        int count = Math.toIntExact(
                EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES / encodedBlockBytes + 1);
        EvidenceBundle oversized = new EvidenceBundle("chain", MESSAGE,
                Collections.nCopies(count, block), List.of(MEMBER), 1, null);

        assertThat(count).isLessThan(EvidenceBundle.MAX_BLOCKS);
        assertThatThrownBy(() -> EvidenceBundleCodec.toJson(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence bundle JSON");
        assertThat(EvidenceBundleCodec.exceedsTotalBlockBudget(
                EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES, 1)).isTrue();
        assertThat(EvidenceBundleCodec.exceedsTotalBlockBudget(
                EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES - 1, 1)).isFalse();
    }

    @Test
    void rejectsOversizedAggregateFromStringAndByteJsonInputs() {
        String block = aggregateProfileBlockHex();
        int encodedBlockBytes = block.length() / 2;
        int count = Math.toIntExact(
                EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES / encodedBlockBytes + 1);
        String json = envelopeWithRepeatedBlock(block, count);

        // Establish that the repeated element is independently decodable and
        // that neither the array-count nor JSON-document guard is responsible.
        assertThat(EvidenceBundleCodec.fromJson(
                envelope(block, "[\"" + MEMBER + "\"]", "1", ""))
                .blocks()).hasSize(1);
        assertThat(count).isLessThan(EvidenceBundle.MAX_BLOCKS);
        assertThat((long) (count - 1) * encodedBlockBytes)
                .isLessThanOrEqualTo(EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES);
        assertThat((long) count * encodedBlockBytes)
                .isGreaterThan(EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES);
        assertThat(json.length()).isLessThan(40 * 1024 * 1024);

        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence bundle JSON");
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(jsonBytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence bundle JSON");
    }

    @Test
    void appliesTopicLimitToUtf8BytesRatherThanJavaCharacters() {
        AppMessage exact = profileMessage("chain", "é".repeat(
                AppChainConfig.MAX_TOPIC_BYTES / 2));
        AppMessage oversized = profileMessage("chain", "é".repeat(
                AppChainConfig.MAX_TOPIC_BYTES / 2 + 1));

        assertThat(EvidenceBundleCodec.fromJson(EvidenceBundleCodec.toJson(
                bundleWith(exact))).blocks().getFirst().messages()).hasSize(1);
        assertThatThrownBy(() -> EvidenceBundleCodec.fromJson(
                EvidenceBundleCodec.toJson(bundleWith(oversized))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String envelope(String block, String members,
                                   String threshold, String extra) {
        return "{\"chainId\":\"chain\",\"messageId\":\"" + MESSAGE
                + "\",\"blocksCbor\":[\"" + block + "\"],\"members\":"
                + members + ",\"threshold\":" + threshold + extra + "}";
    }

    private static String envelopeWithBlocks(String blocks) {
        return "{\"chainId\":\"chain\",\"messageId\":\"" + MESSAGE
                + "\",\"blocksCbor\":[" + blocks + "],\"members\":[\""
                + MEMBER + "\"],\"threshold\":1}";
    }

    private static String envelopeWithRepeatedBlock(String block, int count) {
        String prefix = "{\"chainId\":\"chain\",\"messageId\":\"" + MESSAGE
                + "\",\"blocksCbor\":[";
        String suffix = "],\"members\":[\"" + MEMBER + "\"],\"threshold\":1}";
        int capacity = Math.toIntExact((long) prefix.length() + suffix.length()
                + (long) count * (block.length() + 3));
        StringBuilder json = new StringBuilder(capacity).append(prefix);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(block).append('"');
        }
        return json.append(suffix).toString();
    }

    private static String blockHex() {
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                new byte[32], filled(1), List.of(), filled(2), FinalityCert.empty());
        return HexFormat.of().formatHex(AppBlockCodec.serialize(block));
    }

    private static String aggregateProfileBlockHex() {
        AppMessage message = AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(filled(3))
                .chainId("chain")
                .topic("topic")
                .sender(filled(4))
                .senderSeq(1)
                .expiresAt(1)
                .body(new byte[1024 * 1024])
                .authScheme(FinalityCert.SCHEME_ED25519)
                .authProof(new byte[AppChainConfig.ED25519_SIGNATURE_BYTES])
                .build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                AppBlockCodec.messagesRoot(List.of(message)), filled(1),
                List.of(message), filled(2), FinalityCert.empty());
        return HexFormat.of().formatHex(AppBlockCodec.serialize(block));
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] nestedIndefiniteArrays(int depth) {
        byte[] bytes = new byte[depth * 2 + 1];
        Arrays.fill(bytes, 0, depth, (byte) 0x9f);
        bytes[depth] = 0;
        Arrays.fill(bytes, depth + 1, bytes.length, (byte) 0xff);
        return bytes;
    }

    private static AppMessage profileMessage(String chainId, String topic) {
        return AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(filled(3))
                .chainId(chainId)
                .topic(topic)
                .sender(filled(4))
                .senderSeq(1)
                .expiresAt(1)
                .body(new byte[0])
                .authScheme(FinalityCert.SCHEME_ED25519)
                .authProof(new byte[0])
                .build();
    }

    private static EvidenceBundle bundleWith(AppMessage message) {
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                new byte[32], filled(1), List.of(message), filled(2),
                FinalityCert.empty());
        return new EvidenceBundle("chain", MESSAGE, List.of(block),
                List.of(MEMBER), 1, null);
    }
}
