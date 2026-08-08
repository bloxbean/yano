package com.bloxbean.cardano.yano.appchain.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainClientMessageProofTest {
    @Test
    void decodesAndVerifiesCompactMessageProof() {
        String id = "11".repeat(32);
        String json = """
                {"schemaVersion":1,"treeId":"binary-merkle-blake2b256-v1",
                 "chainId":"proof-chain","blockHeight":7,"blockHash":"%s",
                 "messagesRoot":"%s","messageId":"%s","messageIndex":0,
                 "leafCount":1,"siblings":[]}
                """.formatted("22".repeat(32), id, id);

        var proof = AppChainClient.decodeMessageProof(json);

        assertThat(proof.verifiesRoot()).isTrue();
        assertThat(proof.blockHeight()).isEqualTo(7);
        assertThat(proof.messageIndex()).isZero();
    }

    @Test
    void rejectsUnknownFieldsAndInvalidPaths() {
        String id = "11".repeat(32);
        String valid = """
                {"schemaVersion":1,"treeId":"binary-merkle-blake2b256-v1",
                 "chainId":"proof-chain","blockHeight":7,"blockHash":"%s",
                 "messagesRoot":"%s","messageId":"%s","messageIndex":0,
                 "leafCount":1,"siblings":[]}
                """.formatted("22".repeat(32), id, id);
        assertThatThrownBy(() -> AppChainClient.decodeMessageProof(
                valid.replace("\"siblings\":[]", "\"siblings\":[],\"available\":true")))
                .isInstanceOf(AppChainClient.AppChainClientException.class);
        assertThatThrownBy(() -> AppChainClient.decodeMessageProof(
                valid.replace("\"messagesRoot\":\"" + id,
                        "\"messagesRoot\":\"" + "33".repeat(32))))
                .isInstanceOf(AppChainClient.AppChainClientException.class);
    }
}
