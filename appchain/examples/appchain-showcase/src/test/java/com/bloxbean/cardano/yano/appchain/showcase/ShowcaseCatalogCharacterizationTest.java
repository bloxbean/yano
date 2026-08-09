package com.bloxbean.cardano.yano.appchain.showcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-033 catalog/YAML parity and stable presentation order. */
class ShowcaseCatalogCharacterizationTest {
    private static final Path CONFIG = Path.of(
            "src/main/showcase/config/application-appchain.yml");
    private static final Pattern CHAIN = Pattern.compile(
            "(?ms)^    chains\\[(\\d+)]:\\n(.*?)(?=^    chains\\[|\\z)");
    private static final Pattern CHAIN_ID = Pattern.compile(
            "(?m)^      chain-id: \"?([^\"\\s]+)\"?$");
    private static final Pattern STATE_MACHINE = Pattern.compile(
            "(?m)^      state-machine: \"?([^\"\\s]+)\"?$");

    @Test
    void lightProfilePinsThirteenNamedApplicationsInPresentationOrder() throws Exception {
        Map<String, String> applications = applications(Files.readString(CONFIG));

        assertThat(applications.keySet()).containsExactly(
                "orders-chain", "registry-chain", "approvals-chain", "balances-chain",
                "documents-chain", "workflow-chain", "roles-chain", "payments-chain",
                "authenticated-map-chain", "authenticated-map-jmt-chain",
                "payment-chain-settlement", "document-review-chain", "cardano-history-chain");
        assertThat(applications).containsAllEntriesOf(Map.ofEntries(
                Map.entry("orders-chain", "ordered-log"),
                Map.entry("registry-chain", "kv-registry"),
                Map.entry("approvals-chain", "approvals"),
                Map.entry("balances-chain", "balances"),
                Map.entry("documents-chain", "doc-trail"),
                Map.entry("workflow-chain", "showcase-composite"),
                Map.entry("roles-chain", "role-approvals"),
                Map.entry("payments-chain", "eutxo-ledger"),
                Map.entry("authenticated-map-chain", "authenticated-map"),
                Map.entry("authenticated-map-jmt-chain", "authenticated-map"),
                Map.entry("payment-chain-settlement", "eutxo-ledger"),
                Map.entry("document-review-chain", "document-review"),
                Map.entry("cardano-history-chain", "cardano-history"))).hasSize(13);
    }

    @Test
    void currentReferenceApplicationsAndSettlementAreConfigurationNotAnotherRuntimeKind()
            throws Exception {
        String yaml = Files.readString(CONFIG);
        Map<String, String> applications = applications(yaml);

        assertThat(applications).containsAllEntriesOf(Map.of(
                "workflow-chain", "showcase-composite",
                "authenticated-map-chain", "authenticated-map",
                "payment-chain-settlement", "eutxo-ledger",
                "document-review-chain", "document-review"));
        assertThat(yaml).contains("preset: order-approval-outbox-v1")
                .contains("type: \"eutxo-vault-deposit-v1\"")
                .contains("type: \"eutxo-batch-withdrawal-confirmation-v1\"")
                .contains("state-machine: \"document-review\"")
                .contains("state-machine: \"cardano-history\"")
                .contains("preset: \"params-only-v1\"");
    }

    private static Map<String, String> applications(String yaml) {
        Matcher chains = CHAIN.matcher(yaml);
        Map<Integer, Map.Entry<String, String>> indexed = new LinkedHashMap<>();
        while (chains.find()) {
            Matcher chainId = CHAIN_ID.matcher(chains.group(2));
            Matcher stateMachine = STATE_MACHINE.matcher(chains.group(2));
            assertThat(chainId.find()).as("chain id at index %s", chains.group(1)).isTrue();
            assertThat(stateMachine.find()).as("state machine at index %s", chains.group(1)).isTrue();
            indexed.put(Integer.parseInt(chains.group(1)),
                    Map.entry(chainId.group(1), stateMachine.group(1)));
        }
        assertThat(indexed.keySet()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, indexed.size()).boxed().toList());
        Map<String, String> result = new LinkedHashMap<>();
        indexed.values().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
