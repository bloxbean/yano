package com.bloxbean.cardano.yano.appchain.eutxo.zk.lifecycle;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.client.EutxoL2SessionKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoValidityLifecycleTest {
    @TempDir
    Path temporary;

    @Test
    void createsEncryptedL2SessionKeyWithoutExposingPrivateMaterial()
            throws Exception {
        Path output = temporary.resolve("operator/l2-session-key.enc");
        char[] password = "development-password".toCharArray();

        Map<String, String> result =
                EutxoValidityLifecycleCli.generateKey(output, password);

        assertThat(result)
                .containsEntry("status", "L2_SESSION_KEY_CREATED")
                .containsEntry("encryptedKey",
                        output.toAbsolutePath().toString());
        assertThat(result.get("publicKey"))
                .matches("[0-9a-f]{64}");
        assertThat(password)
                .containsOnly('\0');
        byte[] envelope = Files.readAllBytes(output);
        assertThat(new String(
                envelope, StandardCharsets.ISO_8859_1))
                .doesNotContain(result.get("publicKey"));
        char[] decryptPassword =
                "development-password".toCharArray();
        try (EutxoL2SessionKey opened =
                     EutxoL2SessionKey.decrypt(
                             envelope, decryptPassword)) {
            assertThat(HexFormat.of().formatHex(opened.publicKey()))
                    .isEqualTo(result.get("publicKey"));
        } finally {
            Arrays.fill(decryptPassword, '\0');
        }
        assertThatThrownBy(() ->
                EutxoValidityLifecycleCli.generateKey(
                        output,
                        "development-password".toCharArray()))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void bootstrapIsIdempotentAndFailClosedByNetwork()
            throws Exception {
        Path devnet = project(
                "devnet", List.of(
                        EutxoValidityLifecycle.TRUST_WARNING));
        EutxoValidityLifecycle lifecycle =
                new EutxoValidityLifecycle(devnet);

        assertThat(lifecycle.bootstrap(false, false).status())
                .isEqualTo("CONTRACTS_PLANNED_CEREMONY_REQUIRED");
        assertThat(lifecycle.bootstrap(false, false).status())
                .isEqualTo("CONTRACTS_PLANNED_CEREMONY_REQUIRED");
        assertThat(lifecycle.status().authorizationProfile())
                .isEqualTo(EutxoZkAuthorizationProfile
                        .JUBJUB_DEVELOPMENT_V1.id());
        assertThat(lifecycle.status().batchProfileDigest())
                .isEqualTo(EutxoZkBatchProfile
                        .CARDANO_PAYMENT_B16.digest());

        Path request = temporary.resolve("deposit.json");
        Files.writeString(request, "{\"lovelace\":1000000}");
        var prepared = lifecycle.prepareOperation(
                "deposit", "deposit-1", request, null);
        assertThat(prepared.status())
                .isEqualTo("OPERATION_PREPARED");
        assertThat(lifecycle.prepareOperation(
                "deposit", "deposit-1", request, null).status())
                .isEqualTo("OPERATION_PREPARED");
        assertThat(lifecycle.reconcile().details().get("prepared"))
                .isEqualTo(1);
        assertThat(lifecycle.doctor().status())
                .isEqualTo("VALIDITY_DOCTOR_FAILED");

        Path preview = project("preview", List.of(
                EutxoValidityLifecycle.TRUST_WARNING));
        assertThatThrownBy(() ->
                new EutxoValidityLifecycle(preview)
                        .bootstrap(false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        EutxoValidityLifecycle
                                .UNSAFE_TESTNET_ACKNOWLEDGEMENT);

        Path mainnet = project("mainnet", List.of(
                EutxoValidityLifecycle.TRUST_WARNING));
        assertThatThrownBy(() ->
                new EutxoValidityLifecycle(mainnet)
                        .bootstrap(false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mainnet");

        Path invalidIdentity = project("devnet", List.of(
                EutxoValidityLifecycle.TRUST_WARNING));
        ObjectMapper mapper = new ObjectMapper();
        var invalidLock = mapper.readTree(
                invalidIdentity.resolve("appchain.lock").toFile());
        invalidLock.withObject("/consensusValues").put(
                "yano.app-chain.chains[0].machines.eutxo.genesis."
                        + "l2-public-key",
                "not-a-public-key");
        mapper.writeValue(
                invalidIdentity.resolve("appchain.lock").toFile(),
                invalidLock);
        assertThatThrownBy(() ->
                new EutxoValidityLifecycle(invalidIdentity)
                        .bootstrap(false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L2 address");
    }

    @Test
    void publicTestnetRequiresDurableAcknowledgement()
            throws Exception {
        Path preview = project("preview", List.of(
                EutxoValidityLifecycle.TRUST_WARNING,
                EutxoValidityLifecycle
                        .UNSAFE_TESTNET_ACKNOWLEDGEMENT));
        var result = new EutxoValidityLifecycle(preview)
                .bootstrap(false, false);
        assertThat(result.network()).isEqualTo("preview");
        assertThat(result.trustedProverRequired()).isTrue();
        assertThat(result.fundsPolicy())
                .isEqualTo("disposable-test-funds-only");
    }

    @Test
    void signedCborSubmissionAcceptsYanoResponseWithoutPersistingSecret()
            throws Exception {
        Path project = project("devnet", List.of(
                EutxoValidityLifecycle.TRUST_WARNING));
        EutxoValidityLifecycle lifecycle =
                new EutxoValidityLifecycle(project);
        lifecycle.bootstrap(false, false);
        lifecycle.prepareOperation(
                "deposit", "deposit-1", null, null);
        assertThatThrownBy(() -> lifecycle.prepareOperation(
                "settlement", "settlement-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--proof");
        Path transaction = project.resolve("signed.cbor");
        Files.write(transaction, new byte[] {(byte) 0x84, 1, 2, 3});

        String transactionId = "ab".repeat(32);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tx/submit", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders()
                    .getFirst("Content-Type"))
                    .isEqualTo("application/cbor");
            assertThat(exchange.getRequestHeaders()
                    .getFirst("X-API-Key"))
                    .isEqualTo("not-persisted");
            assertThat(exchange.getRequestBody().readAllBytes())
                    .containsExactly((byte) 0x84, 1, 2, 3);
            byte[] response = ("{\"txHash\":\""
                    + transactionId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI node = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort());
            var submitted = lifecycle.submitOperation(
                    "deposit", "deposit-1",
                    transaction, node, "not-persisted");
            assertThat(submitted.status())
                    .isEqualTo("OPERATION_SUBMITTED");
            assertThat(submitted.details().get("transactionId"))
                    .isEqualTo(transactionId);
        } finally {
            server.stop(0);
        }

        String retained = Files.readString(project.resolve(
                "runtime/validity/operations/"
                        + "deposit-deposit-1.json"));
        assertThat(retained).contains(transactionId)
                .doesNotContain("not-persisted");
        assertThat(lifecycle.markStable(
                "deposit", "deposit-1", transactionId).status())
                .isEqualTo("OPERATION_STABLE");
    }

    private Path project(
            String network,
            List<String> acknowledgements
    ) throws Exception {
        Path project = Files.createTempDirectory(
                temporary, network + "-");
        Map<String, String> consensus = new LinkedHashMap<>();
        String prefix = "yano.app-chain.chains[0].";
        consensus.put(prefix + "chain-id", "payments-zk");
        consensus.put(prefix
                        + "machines.eutxo.validity.authorization-profile",
                EutxoZkAuthorizationProfile
                        .JUBJUB_DEVELOPMENT_V1.id());
        consensus.put(prefix
                        + "machines.eutxo.validity."
                        + "authorization-trusted-prover-required",
                "true");
        consensus.put(prefix
                        + "machines.eutxo.validity.funds-policy",
                "disposable-test-funds-only");
        consensus.put(prefix
                        + "machines.eutxo.bridge.vault-address",
                "addr_test1wzvault");
        consensus.put(prefix
                        + "machines.eutxo.bridge.vault-script-hash",
                "1".repeat(56));
        consensus.put(prefix
                        + "machines.eutxo.bridge.withdrawal-address",
                "addr_test1vwithdrawals");
        consensus.put(prefix
                        + "machines.eutxo.bridge.epoch",
                "1");
        consensus.put(prefix
                        + "machines.eutxo.genesis.l2-address",
                "addr_test1vr8nlm7example");
        consensus.put(prefix
                        + "machines.eutxo.genesis.l2-public-key",
                "2".repeat(64));
        consensus.put(prefix
                        + "machines.eutxo.genesis.l2-key-epoch",
                "1");
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("blueprintDigest", "ab".repeat(32));
        lock.put("network", network);
        lock.put("recipe", "eutxo-zeroj-preview:1");
        lock.put("selectedCapabilities",
                List.of(
                        "settlement:zeroj-validity",
                        "bridge:cardano-federated"));
        lock.put("consensusValues", consensus);
        lock.put("acknowledgements",
                new ArrayList<>(acknowledgements));
        Files.write(project.resolve("appchain.lock"),
                new ObjectMapper().writeValueAsBytes(lock));
        return project;
    }
}
