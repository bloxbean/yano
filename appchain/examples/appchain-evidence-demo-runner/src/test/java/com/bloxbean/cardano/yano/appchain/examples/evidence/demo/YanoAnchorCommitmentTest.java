package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoAnchorCommitmentTest {
    private static final String CHAIN = "evidence-chain";
    private static final String TX = "ab".repeat(32);
    private static final String ADDRESS = "addr_test1wzscriptanchor";
    private static final String POLICY = "33".repeat(28);
    private static final long SLOT = 99;
    private static final Set<String> MEMBERS = Set.of(
            "01".repeat(32), "02".repeat(32), "03".repeat(32));
    private static final String TOKEN = AnchorDatumV1.threadTokenUnit(POLICY, CHAIN);
    private static final AnchorDatumV1 DATUM = datum(CHAIN, 12,
            filled(0x11), filled(0x22), MEMBERS, 2);
    private static final YanoAuditClient.AnchorExpectation EXPECTED =
            new YanoAuditClient.AnchorExpectation(CHAIN, SLOT, 12,
                    filled(0x11), filled(0x22), MEMBERS, 2, ADDRESS, POLICY);

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void verifiesExactTransactionSlotThreadOutputAndInlineDatum() throws Exception {
        Harness harness = harness(validTx(SLOT), validUtxos(DATUM, ADDRESS, TOKEN, "1", 0));

        YanoAuditClient.AnchorCommitment commitment = harness.client()
                .verifyAnchorCommitment(TX, EXPECTED);

        assertThat(commitment.transactionHash()).isEqualTo(TX);
        assertThat(commitment.l1Slot()).isEqualTo(SLOT);
        assertThat(commitment.outputIndex()).isZero();
        assertThat(commitment.threadTokenUnit()).isEqualTo(TOKEN);
    }

    @Test
    void rejectsExpectationForAnotherClientChainBeforeQueryingL1() throws Exception {
        Harness harness = harness(validTx(SLOT), validUtxos(DATUM, ADDRESS, TOKEN, "1", 0));
        YanoAuditClient.AnchorExpectation crossChain =
                new YanoAuditClient.AnchorExpectation("other-chain", SLOT, 12,
                        filled(0x11), filled(0x22), MEMBERS, 2, ADDRESS, POLICY);

        assertUnavailable(() -> harness.client().verifyAnchorCommitment(TX, crossChain));
    }

    @Test
    void rejectsWrongSlotAndMissingDuplicateOrWrongThreadOutputs() throws Exception {
        Harness harness = harness(validTx(SLOT + 1),
                validUtxos(DATUM, ADDRESS, TOKEN, "1", 0));
        assertUnavailable(() -> harness.client().verifyAnchorCommitment(TX, EXPECTED));
        harness.transaction().set(validTx(SLOT));

        String validOutput = output(DATUM, ADDRESS, TOKEN, "1", 0);
        for (String response : List.of(
                root("[]"),
                root("[" + validOutput + "," + output(
                        DATUM, ADDRESS, TOKEN, "1", 1) + "]"),
                validUtxos(DATUM, ADDRESS, TOKEN, "2", 0),
                validUtxos(DATUM, ADDRESS, "44".repeat(28), "1", 0),
                validUtxos(DATUM, ADDRESS + "x", TOKEN, "1", 0),
                root("[" + validOutput + "," + output(
                        DATUM, ADDRESS + "x", TOKEN, "1", 1) + "]"),
                root("[" + outputWithInline(null, ADDRESS, TOKEN, "1", 0) + "]"),
                root("[" + outputWithInline("00", ADDRESS, TOKEN, "1", 0) + "]"),
                root("[" + outputWithInline(
                        HexFormat.of().formatHex(DATUM.encode()) + "00",
                        ADDRESS, TOKEN, "1", 0) + "]"))) {
            harness.utxos().set(response);
            assertUnavailable(() -> harness.client().verifyAnchorCommitment(TX, EXPECTED));
        }
    }

    @Test
    void rejectsEveryDatumBindingMismatchAndMalformedEnvelope() throws Exception {
        Harness harness = harness(validTx(SLOT),
                validUtxos(DATUM, ADDRESS, TOKEN, "1", 0));
        List<AnchorDatumV1> wrongDatums = List.of(
                datum("other-chain", 12, filled(0x11), filled(0x22), MEMBERS, 2),
                datum(CHAIN, 13, filled(0x11), filled(0x22), MEMBERS, 2),
                datum(CHAIN, 12, filled(0x12), filled(0x22), MEMBERS, 2),
                datum(CHAIN, 12, filled(0x11), filled(0x23), MEMBERS, 2),
                datum(CHAIN, 12, filled(0x11), filled(0x22),
                        Set.of("01".repeat(32), "02".repeat(32), "04".repeat(32)), 2),
                datum(CHAIN, 12, filled(0x11), filled(0x22), MEMBERS, 3));
        for (AnchorDatumV1 wrong : wrongDatums) {
            harness.utxos().set(validUtxos(wrong, ADDRESS, TOKEN, "1", 0));
            assertUnavailable(() -> harness.client().verifyAnchorCommitment(TX, EXPECTED));
        }
        harness.utxos().set("{\"hash\":\"" + TX
                + "\",\"inputs\":[],\"outputs\":\"coerced\"}");
        assertUnavailable(() -> harness.client().verifyAnchorCommitment(TX, EXPECTED));
    }

    @Test
    void rejectsCrossNodeDisagreementEvenWhenEachCommitmentIsValid() throws Exception {
        List<YanoAuditClient> clients = List.of(
                harness(validTx(SLOT), validUtxos(DATUM, ADDRESS, TOKEN, "1", 0)).client(),
                harness(validTx(SLOT), validUtxos(DATUM, ADDRESS, TOKEN, "1", 0)).client(),
                harness(validTx(SLOT), validUtxos(DATUM, ADDRESS, TOKEN, "1", 1)).client());

        assertUnavailable(() -> EvidenceScenario.allAnchorCommitmentsOnce(
                clients, Map.of(TX, EXPECTED)));
    }

    private Harness harness(String transaction, String utxos) throws IOException {
        AtomicReference<String> transactionResponse = new AtomicReference<>(transaction);
        AtomicReference<String> utxoResponse = new AtomicReference<>(utxos);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/txs/", exchange -> {
            String response = exchange.getRequestURI().getPath().endsWith("/utxos")
                    ? utxoResponse.get() : transactionResponse.get();
            json(exchange, 200, response);
        });
        server.start();
        servers.add(server);
        YanoAuditClient client = new YanoAuditClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"),
                CHAIN, MEMBERS, 2, null);
        return new Harness(client, transactionResponse, utxoResponse);
    }

    private static String validTx(long slot) {
        return "{\"hash\":\"" + TX + "\",\"block_height\":7,\"slot\":"
                + slot + ",\"valid_contract\":true}";
    }

    private static String validUtxos(AnchorDatumV1 datum, String address,
                                     String unit, String quantity, int index) {
        return root("[" + output(datum, address, unit, quantity, index) + "]");
    }

    private static String root(String outputs) {
        return "{\"hash\":\"" + TX + "\",\"inputs\":[],\"outputs\":"
                + outputs + "}";
    }

    private static String output(AnchorDatumV1 datum, String address,
                                 String unit, String quantity, int index) {
        return outputWithInline(HexFormat.of().formatHex(datum.encode()),
                address, unit, quantity, index);
    }

    private static String outputWithInline(String inlineDatum, String address,
                                           String unit, String quantity, int index) {
        return "{\"tx_hash\":\"" + TX + "\",\"output_index\":" + index
                + ",\"address\":\"" + address + "\",\"amount\":["
                + "{\"unit\":\"lovelace\",\"quantity\":\"2000000\"},"
                + "{\"unit\":\"" + unit + "\",\"quantity\":\"" + quantity
                + "\"}],\"data_hash\":null,\"inline_datum\":"
                + (inlineDatum == null ? "null" : "\"" + inlineDatum + "\"")
                + ",\"reference_script_hash\":null}";
    }

    private static AnchorDatumV1 datum(String chain, long height,
                                       byte[] blockHash, byte[] stateRoot,
                                       Set<String> members, int threshold) {
        return new AnchorDatumV1(chain, height, blockHash, stateRoot,
                members.stream().map(HexFormat.of()::parseHex).toList(), threshold);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.ANCHOR_UNAVAILABLE);
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Harness(YanoAuditClient client,
                           AtomicReference<String> transaction,
                           AtomicReference<String> utxos) {
    }
}
