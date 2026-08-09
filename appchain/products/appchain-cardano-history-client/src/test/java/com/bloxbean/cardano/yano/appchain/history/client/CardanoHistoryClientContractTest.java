package com.bloxbean.cardano.yano.appchain.history.client;

import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryClientContractTest {
    private HttpServer server;

    @AfterEach void close() {
        if (server != null) server.stop(0);
    }

    @Test void requiresExplicitBaseUrlAndChain() {
        assertThatThrownBy(() -> CardanoHistoryClient.builder("", "history"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardanoHistoryClient.builder("http://localhost:17070/api/v1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CardanoHistoryClient.builder(
                "http://localhost:17070/api/v1", "history").build()).isNotNull();
    }

    @Test void malformedBundleAndWrongProfileFailClosed() {
        assertThatThrownBy(() -> new CardanoHistoryPortableStakeProof(1, 170, 0,
                "ab", CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history", "unknown", "00",
                        1, "00", ProofVerifier.TrustedRootSource.CALLER_PINNED, "")))
                .isInstanceOf(IllegalArgumentException.class);
        var malformed = new CardanoHistoryPortableStakeProof(1, 170, 0,
                "11".repeat(28), CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history",
                        ProofVerifier.MPF_BLAKE2B256_V1, "22".repeat(32), 1,
                        "33".repeat(32), ProofVerifier.TrustedRootSource.CALLER_PINNED, ""));
        assertThat(CardanoHistoryPortableProofVerifier.verify(malformed))
                .isEqualTo(CardanoHistoryPortableProofVerifier.Verification.INVALID);
    }

    @Test void embeddedSourceCanNeverSelfAssertCardanoAnchorTrust() {
        var selfAsserted = new CardanoHistoryPortableStakeProof(1, 170, 0,
                "11".repeat(28), CardanoHistoryProofBundle.StakeMode.MINIMUM, "1", "", "00",
                new CardanoHistoryTrustedRoot("history",
                        ProofVerifier.MPF_BLAKE2B256_V1, "22".repeat(32), 1,
                        "33".repeat(32), ProofVerifier.TrustedRootSource.CARDANO_ANCHOR,
                        "44".repeat(32)));
        assertThat(CardanoHistoryPortableProofVerifier.verify(selfAsserted))
                .isEqualTo(CardanoHistoryPortableProofVerifier.Verification.INVALID);
    }

    @Test void readsTheExactL1ConfirmedRootForProofGeneration() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/history/anchor/commitment", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            byte[] body = ("{\"chainId\":\"history\",\"mode\":\"script\","
                    + "\"anchoredHeight\":42,\"stateRoot\":\"" + "11".repeat(32)
                    + "\",\"blockHash\":\"" + "22".repeat(32)
                    + "\",\"transactionHash\":\"tx-42\",\"l1Slot\":100}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var client = CardanoHistoryClient.builder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "history").build();

        var anchor = client.confirmedAnchor();

        assertThat(anchor.root().committedHeight()).isEqualTo(42);
        assertThat(anchor.root().stateRootHex()).isEqualTo("11".repeat(32));
        assertThat(anchor.blockHashHex()).isEqualTo("22".repeat(32));
        assertThat(anchor.transactionHash()).isEqualTo("tx-42");
    }
}
