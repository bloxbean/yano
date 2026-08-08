package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the Phase-1 fossil: since the ADR-025.2 Phase B composite
 * assembly, authenticated-map leaves live only under the composite component
 * key. The proof helper must target that key — proving the map-local
 * canonical key instead yields a genuine (and misleading) absence proof.
 */
@Timeout(30)
class StdlibAuthenticatedMapProofCompositeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void proofHelperTargetsTheCompositeComponentKeyAndVerifiesThePresentEntry()
            throws Exception {
        byte[] applicationKey = "sku-1".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "verified".getBytes(StandardCharsets.US_ASCII);
        byte[] entry = AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.Entry.active(1, new byte[0], value, 1, 1));
        byte[] compositeKey = CompositeCommitmentV1.componentKey(
                AuthenticatedMapContract.STATE_MACHINE_ID,
                AuthenticatedMapContract.canonicalKey("records", applicationKey));

        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        byte[] root = tree.put(1, Map.of(compositeKey, entry)).rootHash();
        String rootHex = Hex.encode(root);
        String proofWire = Hex.encode(tree.getProofWire(compositeKey, 1).orElseThrow());
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.JMT_BLAKE2B256_V1).orElseThrow();

        AtomicReference<String> requestedPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/c1", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            String blockHash = "22".repeat(32);
            byte[] body = ("""
                    {"key":"%s","chainId":"c1","stateRoot":"%s","proofWireHex":"%s",
                     "valueHex":"%s","committedHeight":1,"proofSchemaVersion":1,
                     "schemaVersion":1,"profile":"%s","backend":"%s",
                     "commitmentFormatId":"%s","formatFingerprint":"%s",
                     "genesisId":"%s","legacy":false,"proofEncodingId":"%s",
                     "nativeVersioning":true,"physicalDelete":false,"version":1,
                     "oldestProvableHeight":1,"presence":"PRESENT","blockHash":"%s",
                     "block":{"version":1,"height":1,"prevHash":"%s","l1Slot":0,
                       "l1BlockHash":"","timestamp":1,"messagesRoot":"%s",
                       "stateRoot":"%s","blockHash":"%s"},
                     "finalityCertificate":{"scheme":0,"signatures":[
                       {"signer":"%s","signature":"%s"}]}}
                    """.formatted(Hex.encode(compositeKey), rootHex, proofWire,
                    Hex.encode(entry), profile.id(), profile.backend(),
                    profile.commitmentFormatId(), profile.formatFingerprintHex(),
                    "11".repeat(32), profile.proofEncodingId(), blockHash,
                    "00".repeat(32), "33".repeat(32), rootHex, blockHash,
                    "44".repeat(32), "55".repeat(64)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AppChainClient client = AppChainClient.builder(
                        "http://localhost:" + server.getAddress().getPort() + "/api/v1")
                .chainId("c1").build();
        StdlibAppChainClient stdlib = new StdlibAppChainClient(client, proof ->
                new ProofVerifier.TrustedStateRoot("c1", proof.profile(),
                        proof.genesisIdHex(), proof.committedHeight(),
                        proof.stateRootHex(),
                        ProofVerifier.TrustedRootSource.CALLER_PINNED));

        var verified = stdlib.authenticatedMapProof("records", applicationKey)
                .orElseThrow();

        assertThat(requestedPath.get()).isEqualTo(
                "/api/v1/app-chain/chains/c1/proof/" + Hex.encode(compositeKey));
        assertThat(requestedPath.get()).contains(
                Hex.encode("yano-composite-state-v1".getBytes(StandardCharsets.US_ASCII)));
        assertThat(verified.value().revision()).isEqualTo(1);
        assertThat(verified.value().value()).isEqualTo(value);
        assertThat(verified.proof().stateRootHex()).isEqualTo(rootHex);
    }
}
