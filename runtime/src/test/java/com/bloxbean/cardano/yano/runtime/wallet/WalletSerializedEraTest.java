package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WalletSerializedEraTest {
    @Test void decodedHistoricalOutputsAndEventsMatchIndependentRawCborReference() throws Exception {
        JsonNode fixtures = new ObjectMapper().readTree(resource("expected.json"));
        assertThat(fixtures.size()).isEqualTo(10);
        for (JsonNode fixture : fixtures) {
            String name = fixture.path("file").asText();
            byte[] cbor = HexFormat.of().parseHex(new String(resource(name), StandardCharsets.US_ASCII).trim());
            assertThat(digest(cbor)).as(name).isEqualTo(fixture.path("wireSha256").asText());
            var block = BlockSerializer.INSTANCE.deserialize(cbor);
            assertThat(block.getTransactionBodies()).as(name).hasSize(fixture.path("transactions").size());
            Set<WalletCredential> all = new HashSet<>();
            for (int i = 0; i < block.getTransactionBodies().size(); i++) {
                var tx = block.getTransactionBodies().get(i);
                boolean invalid = block.getInvalidTransactions() != null && block.getInvalidTransactions().contains(i);
                Set<WalletCredential> outputs = new HashSet<>();
                if (invalid) {
                    if (tx.getCollateralReturn() != null) WalletCredentials.address(tx.getCollateralReturn().getAddress(), outputs);
                } else if (tx.getOutputs() != null) {
                    tx.getOutputs().forEach(output -> WalletCredentials.address(output.getAddress(), outputs));
                }
                Set<WalletCredential> events = new HashSet<>();
                if (!invalid) WalletCredentials.events(tx, events);
                JsonNode expected = fixture.path("transactions").get(i);
                assertSet(name + " tx " + i + " outputs", outputs, expected.path("outputs"));
                assertSet(name + " tx " + i + " events", events, expected.path("events"));
                all.addAll(outputs);
                all.addAll(events);
            }
            byte[] filter = CredentialFilter.encode(new byte[16], all.stream().map(WalletCredential::filterElement).toList());
            for (WalletCredential credential : all) {
                assertThat(CredentialFilter.matches(filter, List.of(credential.filterElement()))).as(name + " " + credential).isTrue();
            }
        }
    }

    private static void assertSet(String label, Set<WalletCredential> actual, JsonNode expected) throws Exception {
        assertThat(actual).as(label).hasSize(expected.path("count").asInt());
        String canonical = String.join("\n", actual.stream()
                .map(c -> c.role() + ":" + c.type() + ":" + c.hash()).sorted().toList());
        assertThat(digest(canonical.getBytes(StandardCharsets.UTF_8))).as(label).isEqualTo(expected.path("sha256").asText());
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] resource(String name) throws Exception {
        try (var stream = WalletSerializedEraTest.class.getResourceAsStream("/wallet/eras/" + name)) {
            if (stream == null) throw new IllegalArgumentException("Missing fixture " + name);
            return stream.readAllBytes();
        }
    }
}
