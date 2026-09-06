package com.bloxbean.cardano.yano.app.api.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(WalletIndexDevnetProfile.class)
@Tag("integration")
class WalletIndexLiveIT {
    @TestHTTPResource URL url;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void genesisAssetsOutgoingOnlySnapshotRestoreAndReplay() throws Exception {
        String base = url.toString().replaceAll("/$", "") + "/api/v1/";
        BackendService backend = new BFBackendService(base, "local-test");
        DefaultUtxoSupplier utxos = new DefaultUtxoSupplier(backend.getUtxoService());
        Account sender = WalletIndexDevnetProfile.account(0);
        Account receiver = WalletIndexDevnetProfile.account(1);
        String address = receiver.baseAddress();
        assertThat(get("addresses/" + sender.baseAddress() + "/first-seen").path("firstSeenSlot").asLong(-1)).isZero();
        assertThat(get("addresses/" + address + "/first-seen").path("firstSeenSlot").isNull()).isTrue();
        List<JsonNode> empty = scan(receiver, origin(), List.of());
        JsonNode initial = empty.getLast().path("point");
        assertThat(transactions(empty)).isEmpty();
        String snapshotName = "wallet119-" + UUID.randomUUID();
        var snapshot = post("devnet/snapshot", mapper.createObjectNode().put("name", snapshotName));
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);

        var policy = ScriptPubkey.createWithNewKey();
        Tx receive = new Tx().payToAddress(address, Amount.ada(10))
                .mintAssets(policy._1, new Asset("Wallet119", BigInteger.valueOf(100)), address)
                .from(sender.baseAddress());
        var received = new QuickTxBuilder(backend).compose(receive)
                .withSigner(SignerProviders.signerFrom(sender)).withSigner(SignerProviders.signerFrom(policy._2.getSkey())).complete();
        assertThat(received.isSuccessful()).as(received.getResponse()).isTrue();
        awaitUtxo(utxos, address, received.getValue());
        List<JsonNode> first = scan(receiver, initial, List.of());
        assertThat(transactions(first)).extracting(r -> r.path("txHash").asText()).containsExactly(received.getValue());
        List<JsonNode> known = new ArrayList<>();
        for (JsonNode output : transactions(first).getFirst().path("outputs")) {
            if (output.path("address").asText().equals(address)) known.add(output);
        }
        assertThat(known.stream().flatMap(o -> {
            List<JsonNode> values = new ArrayList<>(); o.path("assets").forEach(values::add); return values.stream();
        }).anyMatch(a -> a.path("quantity").bigIntegerValue().equals(BigInteger.valueOf(100)))).isTrue();
        JsonNode firstSeen = get("addresses/" + address + "/first-seen");
        long seenSlot = firstSeen.path("firstSeenSlot").longValue();
        assertThat(seenSlot).isPositive();

        Tx spend = new Tx().payToAddress(sender.baseAddress(), List.of(Amount.ada(8),
                        Amount.asset(policy._1.getPolicyId() + HexFormat.of().formatHex("Wallet119".getBytes(StandardCharsets.UTF_8)), 100)))
                .from(address).withChangeAddress(sender.baseAddress());
        var spent = new QuickTxBuilder(backend).compose(spend).feePayer(sender.baseAddress())
                .withSigner(SignerProviders.signerFrom(receiver)).withSigner(SignerProviders.signerFrom(sender)).complete();
        assertThat(spent.isSuccessful()).as(spent.getResponse()).isTrue();
        awaitUtxo(utxos, sender.baseAddress(), spent.getValue());
        assertThat(utxos.getAll(address)).isEmpty();
        List<JsonNode> resumed = scan(receiver, first.getLast().path("point"), known);
        assertThat(transactions(resumed)).extracting(r -> r.path("txHash").asText()).containsExactly(spent.getValue());
        assertThat(get("addresses/" + address + "/first-seen").path("firstSeenSlot").longValue()).isEqualTo(seenSlot);
        assertThat(transactions(scan(receiver, origin(), List.of()))).extracting(r -> r.path("txHash").asText())
                .containsExactly(received.getValue(), spent.getValue());

        JsonNode orphaned = resumed.getLast().path("point");
        assertThat(post("devnet/restore/" + snapshotName, mapper.createObjectNode()).statusCode()).isEqualTo(200);
        assertThat(get("addresses/" + address + "/first-seen").path("firstSeenSlot").isNull()).isTrue();
        assertThat(transactions(scan(receiver, origin(), List.of()))).isEmpty();
        HttpResponse<String> stale = post("scan", request(receiver, orphaned, List.of()));
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);

        Tx replacement = new Tx().payToAddress(address, Amount.ada(11))
                .mintAssets(policy._1, new Asset("Wallet119", BigInteger.valueOf(100)), address)
                .from(sender.baseAddress());
        var replayed = new QuickTxBuilder(backend).compose(replacement)
                .withSigner(SignerProviders.signerFrom(sender))
                .withSigner(SignerProviders.signerFrom(policy._2.getSkey())).complete();
        assertThat(replayed.isSuccessful()).as(replayed.getResponse()).isTrue();
        awaitUtxo(utxos, address, replayed.getValue());
        assertThat(transactions(scan(receiver, origin(), List.of())))
                .extracting(r -> r.path("txHash").asText()).containsExactly(replayed.getValue());
        assertThat(get("addresses/" + address + "/first-seen").path("firstSeenSlot").asLong()).isPositive();
    }

    private void awaitUtxo(DefaultUtxoSupplier supplier, String address, String txHash) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (supplier.getAll(address).stream().anyMatch(u -> u.getTxHash().equals(txHash))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Transaction did not produce expected UTxO: " + txHash);
    }

    private List<JsonNode> scan(Account account, JsonNode after, List<JsonNode> known) throws Exception {
        HttpResponse<String> response = post("scan", request(account, after, known));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<JsonNode> records = new ArrayList<>();
        for (String line : response.body().split("\\n")) if (!line.isBlank()) records.add(mapper.readTree(line));
        assertThat(records.getFirst().path("type").asText()).isEqualTo("ready");
        assertThat(records.getLast().path("type").asText()).isEqualTo("done");
        assertThat(records.getLast().path("point")).isEqualTo(records.getFirst().path("point"));
        return records;
    }

    private ObjectNode request(Account account, JsonNode after, List<JsonNode> known) {
        ObjectNode request = mapper.createObjectNode().put("version", 1);
        byte[] address = account.getBaseAddress().getBytes();
        request.putArray("credentials").addObject().put("role", "stake").put("type", "key")
                .put("hash", HexFormat.of().formatHex(address, 29, 57));
        request.set("after", after);
        var outputs = request.putArray("knownOutputs"); known.forEach(outputs::add);
        return request;
    }

    private JsonNode origin() {
        return mapper.createObjectNode().put("blockNumber", -1).put("slot", 0).put("blockHash", "00".repeat(32));
    }

    private List<JsonNode> transactions(List<JsonNode> records) {
        return records.stream().filter(r -> r.path("type").asText().equals("transaction")).toList();
    }

    private URI uri(String path) { return URI.create(url.toString().replaceAll("/$", "") + "/api/v1/" + path); }
    private JsonNode get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
    private HttpResponse<String> post(String path, JsonNode body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
