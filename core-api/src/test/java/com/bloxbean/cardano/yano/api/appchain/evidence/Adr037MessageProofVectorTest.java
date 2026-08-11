package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Adr037MessageProofVectorTest {
    @Test
    void verifiesSharedGoldenAndAdversarialVectors() throws Exception {
        Path vectors = Path.of("..", "appchain", "test-vectors", "adr037-message-proof-v1.json")
                .toAbsolutePath().normalize();
        JsonNode document = new ObjectMapper().readTree(Files.readString(vectors));
        byte[] root = HexFormat.of().parseHex(document.required("messagesRoot").asText());
        int leafCount = document.required("leafCount").asInt();
        for (JsonNode vector : document.required("vectors")) {
            byte[] messageId = HexFormat.of().parseHex(vector.required("messageId").asText());
            var siblings = new java.util.ArrayList<byte[]>();
            vector.required("siblings").forEach(value ->
                    siblings.add(HexFormat.of().parseHex(value.asText())));
            MessageInclusionProof proof = new MessageInclusionProof(
                    1, MessageInclusionProof.TREE_ID, "vectors", 1, new byte[32], root,
                    messageId, vector.required("index").asInt(), leafCount, siblings);
            assertThat(proof.verifiesRoot()).isTrue();

            byte[] wrongRoot = root.clone();
            wrongRoot[0] ^= 1;
            assertThat(new MessageInclusionProof(1, MessageInclusionProof.TREE_ID,
                    "vectors", 1, new byte[32], wrongRoot, messageId,
                    vector.required("index").asInt(), leafCount, siblings).verifiesRoot()).isFalse();
            byte[] wrongSibling = siblings.getFirst().clone();
            wrongSibling[0] ^= 1;
            siblings.set(0, wrongSibling);
            assertThat(new MessageInclusionProof(1, MessageInclusionProof.TREE_ID,
                    "vectors", 1, new byte[32], root, messageId,
                    vector.required("index").asInt(), leafCount, siblings).verifiesRoot()).isFalse();
        }
    }
}
