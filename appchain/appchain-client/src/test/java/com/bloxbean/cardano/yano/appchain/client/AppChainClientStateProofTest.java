package com.bloxbean.cardano.yano.appchain.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class AppChainClientStateProofTest {
    private static final String ROOT = "ab".repeat(32);
    private static final String BLOCK_HASH = "22".repeat(32);
    private static final String INCLUSION = proofEnvelope("\"valueHex\":\"ff\","
            + "\"finalizedAtHeight\":3,", "PRESENT");
    private static final String EXCLUSION = proofEnvelope("", "ABSENT");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void proofParsesCertifiedAtomicSnapshotAndMessageHeight() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        start(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, INCLUSION);
        });

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(path.get()).isEqualTo("/api/v1/app-chain/chains/c1/state/proof/01");
        assertThat(proof.keyHex()).isEqualTo("01");
        assertThat(proof.chainId()).isEqualTo("c1");
        assertThat(proof.stateRootHex()).isEqualTo(ROOT);
        assertThat(proof.valueHex()).isEqualTo("ff");
        assertThat(proof.finalizedAtHeight()).isEqualTo(3);
        assertThat(proof.committedHeight()).isEqualTo(42);
    }

    @Test
    void proofPreservesAtomicExclusionEnvelope() throws Exception {
        start(exchange -> respond(exchange, 200, EXCLUSION));

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(proof.valueHex()).isNull();
        assertThat(proof.finalizedAtHeight()).isNull();
        assertThat(proof.committedHeight()).isEqualTo(42);
    }

    @Test
    void stateEntryUsesDedicatedCurrentOrHistoricalEndpointWithoutProofBytes() throws Exception {
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        AtomicReference<String> request = new AtomicReference<>();
        start(exchange -> {
            request.set(exchange.getRequestURI().toString());
            respond(exchange, 200, """
                    {"key":"01","chainId":"c1","stateRoot":"%s","valueHex":"ff",
                     "committedHeight":42,"proofSchemaVersion":1,"schemaVersion":1,
                     "profile":"%s","backend":"%s","commitmentFormatId":"%s",
                     "formatFingerprint":"%s","genesisId":"%s",
                     "proofEncodingId":"%s","nativeVersioning":%s,
                     "physicalDelete":%s,"version":42,"oldestProvableHeight":2,
                     "presence":"PRESENT","blockHash":"%s"}
                    """.formatted(ROOT, profile.id(), profile.backend(),
                    profile.commitmentFormatId(), profile.formatFingerprintHex(),
                    "11".repeat(32), profile.proofEncodingId(), profile.nativeVersioning(),
                    profile.physicalDelete(), BLOCK_HASH));
        });

        JsonNode entry = client().stateEntry(new byte[]{1}, 42).orElseThrow();

        assertThat(request.get()).isEqualTo(
                "/api/v1/app-chain/chains/c1/state/entry/01?height=42");
        assertThat(entry.path("valueHex").asText()).isEqualTo("ff");
        assertThat(entry.has("proofWireHex")).isFalse();
    }

    @Test
    void stateEntryAcceptsTheReleaseMatchedProfileTaggedServerContract() throws Exception {
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.JMT_BLAKE2B256_V1).orElseThrow();
        start(exchange -> respond(exchange, 200, """
                {"key":"01","chainId":"c1","stateRoot":"%s","valueHex":"ff",
                 "committedHeight":42,"proofSchemaVersion":1,"schemaVersion":1,
                 "profile":"%s","backend":"%s","commitmentFormatId":"%s",
                 "formatFingerprint":"%s","genesisId":"%s",
                 "proofEncodingId":"%s","nativeVersioning":true,
                 "physicalDelete":false,"version":42,"oldestProvableHeight":2,
                 "presence":"PRESENT","blockHash":"%s"}
                """.formatted(ROOT, profile.id(), profile.backend(),
                profile.commitmentFormatId(), profile.formatFingerprintHex(),
                "11".repeat(32), profile.proofEncodingId(), "22".repeat(32))));

        JsonNode entry = client().stateEntry(new byte[]{1}).orElseThrow();

        assertThat(entry.path("profile").asText())
                .isEqualTo(ProofVerifier.JMT_BLAKE2B256_V1);
        assertThat(entry.path("formatFingerprint").asText())
                .isEqualTo(profile.formatFingerprintHex());
    }

    @Test
    void profileTaggedProofRequiresReleaseMatchedCommitmentMetadata() throws Exception {
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.JMT_BLAKE2B256_V1).orElseThrow();
        String blockHash = "22".repeat(32);
        String tagged = """
                {"key":"01","chainId":"c1","stateRoot":"%s","proofWireHex":"80",
                 "valueHex":"ff","committedHeight":42,"proofSchemaVersion":1,
                 "schemaVersion":1,"profile":"%s","backend":"%s",
                 "commitmentFormatId":"%s","formatFingerprint":"%s",
                 "genesisId":"%s","proofEncodingId":"%s",
                 "nativeVersioning":true,"physicalDelete":false,"version":42,
                 "oldestProvableHeight":2,"presence":"PRESENT","blockHash":"%s",
                 "block":{"version":1,"height":42,"prevHash":"%s","l1Slot":0,
                   "l1BlockHash":"","timestamp":1,"messagesRoot":"%s",
                   "stateRoot":"%s","blockHash":"%s"},
                 "finalityCertificate":{"scheme":0,"signatures":[
                   {"signer":"%s","signature":"%s"}]}}
                """.formatted(ROOT, profile.id(), profile.backend(),
                profile.commitmentFormatId(), profile.formatFingerprintHex(),
                "11".repeat(32), profile.proofEncodingId(), blockHash,
                "00".repeat(32), "33".repeat(32), ROOT, blockHash,
                "44".repeat(32), "55".repeat(64));
        AtomicReference<String> response = new AtomicReference<>(tagged);
        start(exchange -> respond(exchange, 200, response.get()));

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(proof.profile()).isEqualTo(ProofVerifier.JMT_BLAKE2B256_V1);
        assertThat(proof.backend()).isEqualTo("jmt");
        assertThat(proof.oldestProvableHeight()).isEqualTo(2);
        assertThat(proof.block().height()).isEqualTo(42);
        response.set(tagged.replace("\"backend\":\"jmt\"", "\"backend\":\"mpf\""));
        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessageContaining("differs from this client release");
    }

    @Test
    void proofAcceptsBoundedNonConsensusImplementationMetadata() throws Exception {
        String metadata = """
                ,"implementation":{"compatibility":"Cardano Client Lib MPF compatible",
                  "testedImplementations":["cardano-client-lib"],
                  "verifierAvailable":true,"verificationTarget":"off-chain-and-on-chain"}
                """.strip();
        String envelope = INCLUSION.strip();
        String response = envelope.substring(0, envelope.length() - 1) + metadata + "}";
        start(exchange -> respond(exchange, 200, response));

        AppChainClient.Proof proof = client().proof(new byte[]{1}).orElseThrow();

        assertThat(proof.profile()).isEqualTo(ProofVerifier.MPF_BLAKE2B256_V1);
    }

    @Test
    void proofRejectsMalformedImplementationMetadata() throws Exception {
        String metadata = """
                ,"implementation":{"compatibility":"Cardano Client Lib MPF compatible",
                  "testedImplementations":["cardano-client-lib"],
                  "verifierAvailable":true,"verificationTarget":"on-chain-only"}
                """.strip();
        String envelope = INCLUSION.strip();
        String response = envelope.substring(0, envelope.length() - 1) + metadata + "}";
        start(exchange -> respond(exchange, 200, response));

        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("Invalid app-chain state implementation metadata");
    }

    @Test
    void offlineProofDecoderRejectsNonObjectInputsWithoutLeakingParserFailures() {
        for (String invalid : new String[]{"null", "[]", "true"}) {
            assertThatThrownBy(() -> AppChainClient.decodeProofEnvelope(invalid))
                    .isInstanceOf(AppChainClient.AppChainClientException.class)
                    .hasMessage("Malformed app-chain state proof response")
                    .hasNoCause();
        }
    }

    @Test
    void proofRejectsEnvelopeWithoutCommittedSnapshotIdentity() throws Exception {
        start(exchange -> respond(exchange, 200,
                INCLUSION.replace(",\"committedHeight\":42", "")));

        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("Invalid app-chain state proof identity");
    }

    @Test
    void proofRejectsMismatchedUnknownDuplicateAndNonCanonicalFields() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(INCLUSION);
        start(exchange -> respond(exchange, 200, response.get()));
        String fields = INCLUSION.strip();
        fields = fields.substring(0, fields.length() - 1);
        String duplicate = fields + ",\"key\":\"01\"}";
        String unknown = fields + ",\"extra\":true}";

        for (String invalid : new String[]{
                INCLUSION.replace("\"key\":\"01\"", "\"key\":\"02\""),
                INCLUSION.replace("\"chainId\":\"c1\"", "\"chainId\":\"c2\""),
                INCLUSION.replace(ROOT, ROOT.toUpperCase()),
                INCLUSION.replace("\"proofWireHex\":\"80\"", "\"proofWireHex\":\"\""),
                INCLUSION.replace("\"committedHeight\":42", "\"committedHeight\":-1"),
                INCLUSION.replace("\"finalizedAtHeight\":3", "\"finalizedAtHeight\":43"),
                duplicate,
                unknown,
                INCLUSION + "{}"
        }) {
            response.set(invalid);
            assertThatThrownBy(() -> client().proof(new byte[]{1}))
                    .isInstanceOf(AppChainClient.AppChainClientException.class)
                    .hasMessageNotContaining("proofWireHex");
        }
    }

    @Test
    void proofDistinguishesMissingAndBoundsKeyBeforeIo() throws Exception {
        start(exchange -> respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}"));

        Optional<AppChainClient.Proof> missing = client().proof(new byte[]{1});

        assertThat(missing).isEmpty();
        assertThatThrownBy(() -> client().proof(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-256 bytes");
        assertThatThrownBy(() -> client().proof(new byte[257]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-256 bytes");
    }

    @Test
    void proofFailureNeverReflectsResponseOrApiKey() throws Exception {
        String secret = "proof-api-key-canary";
        start(exchange -> respond(exchange, 500,
                "{\"code\":\"FAILED\",\"error\":\"" + secret + " from provider\"}"));
        AppChainClient client = AppChainClient.builder(baseUrl())
                .chainId("c1")
                .apiKey(secret)
                .build();

        assertThatThrownBy(() -> client.proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain state proof failed with HTTP 500 (FAILED)")
                .hasMessageNotContaining(secret)
                .hasNoCause();
    }

    @Test
    void proofBoundsRawEnvelopeAndIndividualProofComponents() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(INCLUSION);
        start(exchange -> {
            String body = response.get();
            if (body == null) {
                // Exact client envelope limit plus one byte. A declared length
                // must be rejected without allocating or reading that body.
                exchange.sendResponseHeaders(200, 4_260_353L);
                exchange.getResponseBody().write(0);
                exchange.getResponseBody().flush();
                exchange.close();
                return;
            }
            respond(exchange, 200, body);
        });

        response.set(null);
        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("App-chain state proof response exceeds the client size limit");

        response.set(INCLUSION.replace("\"proofWireHex\":\"80\"",
                "\"proofWireHex\":\"" + "00".repeat(1024 * 1024 + 1) + "\""));
        assertThatThrownBy(() -> client().proof(new byte[]{1}))
                .isInstanceOf(AppChainClient.AppChainClientException.class)
                .hasMessage("Invalid app-chain state proof encoding");
    }

    private AppChainClient client() {
        return AppChainClient.builder(baseUrl()).chainId("c1").build();
    }

    private static String proofEnvelope(String valueAndFinalityFields, String presence) {
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        return """
                {"key":"01","chainId":"c1","stateRoot":"%s","proofWireHex":"80",
                 %s"committedHeight":42,"proofSchemaVersion":1,"schemaVersion":1,
                 "profile":"%s","backend":"%s","commitmentFormatId":"%s",
                 "formatFingerprint":"%s","genesisId":"%s",
                 "proofEncodingId":"%s","nativeVersioning":%s,"physicalDelete":%s,
                 "version":42,"oldestProvableHeight":2,"presence":"%s","blockHash":"%s",
                 "block":{"version":1,"height":42,"prevHash":"%s","l1Slot":0,
                   "l1BlockHash":"","timestamp":1,"messagesRoot":"%s",
                   "stateRoot":"%s","blockHash":"%s"},
                 "finalityCertificate":{"scheme":0,"signatures":[
                   {"signer":"%s","signature":"%s"}]}}
                """.formatted(ROOT, valueAndFinalityFields, profile.id(), profile.backend(),
                profile.commitmentFormatId(), profile.formatFingerprintHex(), "11".repeat(32),
                profile.proofEncodingId(), profile.nativeVersioning(), profile.physicalDelete(),
                presence, BLOCK_HASH, "00".repeat(32), "33".repeat(32), ROOT, BLOCK_HASH,
                "44".repeat(32), "55".repeat(64));
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/api/v1";
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
                throw failure instanceof IOException ioFailure
                        ? ioFailure : new IOException("proof test handler failed", failure);
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
