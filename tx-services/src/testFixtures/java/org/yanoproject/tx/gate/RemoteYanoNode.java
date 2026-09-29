package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A running Yano devnet node seen through its REST API, for the gate workloads that run against a packaged node
 * (the Phase 6b Haskell-follower workload, the Phase 7c JVM-vs-native parity workload): protocol parameters, the
 * genesis UTxO of an address, the tip, transaction status, AdaPot and account reads, and raw submission.
 */
public final class RemoteYanoNode {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final BFBackendService backend;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** @param baseUrl the node's HTTP root, for example {@code http://localhost:7171} */
    public RemoteYanoNode(String baseUrl) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.backend = new BFBackendService(base + "/api/v1/", "gate");
    }

    /** @return a Blockfrost-shaped backend over the node's {@code /api/v1} */
    public BFBackendService backend() {
        return backend;
    }

    public ProtocolParams protocolParams() {
        try {
            Result<ProtocolParams> result = backend.getEpochService().getProtocolParameters();
            if (!result.isSuccessful()) {
                throw new IllegalStateException("protocol parameters: " + result.getResponse());
            }
            return result.getValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The devnet genesis output of {@code address} (tx id {@code blake2b_256(address bytes)}, index 0). */
    public Utxo genesisUtxo(String address) throws Exception {
        String txHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(new Address(address).getBytes()));
        JsonNode utxo = get("/api/v1/utxos/" + txHash + "/0");
        for (JsonNode amount : utxo.path("amount")) {
            if ("lovelace".equals(amount.path("unit").asText())) {
                return new Utxo(txHash, 0, address, List.of(Amount.lovelace(
                        new BigInteger(amount.path("quantity").asText()))), null, null, null);
            }
        }
        throw new IllegalStateException("no genesis UTxO for " + address + ": " + utxo);
    }

    /** @return the tip's slot, or -1 when the node does not answer */
    public long tipSlot() {
        try {
            return get("/api/v1/node/tip").path("slot").asLong();
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return the tip's block number, or -1 when the node does not answer */
    public long tipBlock() {
        try {
            return get("/api/v1/node/tip").path("blockNumber").asLong();
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return {@code pending}, {@code in_block}, {@code unknown}, or {@code error} when the node does not answer */
    public String txStatus(String txHash) {
        try {
            return get("/api/v1/txs/" + txHash + "/status").path("status").asText();
        } catch (Exception e) {
            return "error";
        }
    }

    public boolean confirmed(String txHash) {
        return "in_block".equals(txStatus(txHash));
    }

    /** @return the slot of the block holding {@code txHash}, or -1 */
    public long txSlot(String txHash) throws Exception {
        return get("/api/v1/txs/" + txHash + "/status").path("slot").asLong(-1);
    }

    public BigInteger treasury(long epoch) throws Exception {
        return new BigInteger(get("/api/v1/epochs/" + epoch + "/adapot").path("treasury").asText());
    }

    /** @return the reward balance of {@code stakeAddress}, zero when the node has no such account */
    public BigInteger withdrawable(String stakeAddress) {
        try {
            return new BigInteger(get("/api/v1/accounts/" + stakeAddress).path("withdrawable_amount").asText("0"));
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /** Submits raw transaction CBOR ({@code POST /api/v1/tx/submit}) and returns the node's answer as is. */
    public HttpResponse<String> submit(byte[] cbor) throws IOException, InterruptedException {
        return postBytes("/api/v1/tx/submit", cbor);
    }

    /** {@code POST} of raw CBOR, for example to {@code /api/v1/utils/txs/evaluate}. */
    public HttpResponse<String> postBytes(String path, byte[] cbor) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/cbor")
                .POST(HttpRequest.BodyPublishers.ofByteArray(cbor)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code POST} of a JSON body; a non-200 answer throws. */
    public JsonNode post(String path, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        return body(path, response);
    }

    /** {@code GET}; a non-200 answer throws. */
    public JsonNode get(String path) throws Exception {
        return body(path, http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private static JsonNode body(String path, HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path + " -> " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body());
    }

    /** Polls {@code condition} every 200 ms; @return false when {@code timeoutMillis} passed first */
    public static boolean poll(long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }
}
