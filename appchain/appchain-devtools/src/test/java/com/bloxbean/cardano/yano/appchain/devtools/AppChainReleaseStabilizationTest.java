package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainReleaseStabilizationTest {
    @Test
    void acceptanceIndexCoversTheCatalogAndEveryEvidenceReferenceResolves()
            throws Exception {
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework());
        AppChainProjectModel.ReleaseAcceptanceIndex index =
                catalog.releaseAcceptanceIndex();

        assertThat(index.schemaVersion()).isEqualTo("v1alpha1");
        assertThat(index.schemaStatus()).isEqualTo("alpha");
        assertThat(index.stabilizationDecision()).isEqualTo("RETAIN_V1ALPHA1");
        assertThat(index.externalThirdPartyUsage()).isFalse();
        assertThat(index.cleanRoomExercises()).isNotEmpty();
        assertThat(index.knownLimitations()).isNotEmpty();
        assertThat(index.capabilityEvidence().keySet())
                .containsExactlyInAnyOrderElementsOf(catalog.capabilities().stream()
                        .map(AppChainProjectModel.Capability::id)
                        .toList());
        assertThat(index.recipes()).extracting(AppChainProjectModel.RecipeAcceptance::id)
                .containsExactlyInAnyOrderElementsOf(catalog.recipes().stream()
                        .map(AppChainProjectModel.Recipe::id)
                        .toList());

        assertThat(index.recipes().stream()
                .filter(recipe -> "stable".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("PACKAGED_RUNTIME"));
        assertThat(index.recipes().stream()
                .filter(recipe -> "preview".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("MODULE_OUTCOME_AND_PACKAGED_PROVIDER"));
        assertThat(index.recipes().stream()
                .filter(recipe -> "experimental".equals(recipe.maturity())))
                .allSatisfy(recipe -> assertThat(recipe.outcomeLevel())
                        .isEqualTo("OPERATOR_OWNED_REFERENCE"));

        Set<String> references = new LinkedHashSet<>();
        index.capabilityEvidence().values().forEach(references::addAll);
        index.recipes().stream().map(AppChainProjectModel.RecipeAcceptance::evidence)
                .forEach(references::addAll);
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        for (String reference : references) {
            List<String> parts = List.of(reference.split("#", -1));
            assertThat(parts).as("evidence syntax: %s", reference).hasSize(2);
            Path evidenceFile = repository.resolve(parts.get(0)).normalize();
            assertThat(evidenceFile).as("evidence file: %s", reference)
                    .startsWith(repository.normalize())
                    .isRegularFile();
            assertThat(Files.readString(evidenceFile))
                    .as("evidence fragment: %s", reference)
                    .contains(parts.get(1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void eutxoNetworkEvidenceCannotOverstateDevelopmentProfile()
            throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path evidenceFile = repository.resolve(
                "appchain/extensions/eutxo-zk/acceptance/"
                        + "network-acceptance-v1.json");
        Path schemaFile = repository.resolve(
                "appchain/extensions/eutxo-zk/acceptance/"
                        + "eutxo-zk-network-acceptance.schema.json");
        assertThat(evidenceFile).isRegularFile();
        assertThat(schemaFile).isRegularFile();

        Map<String, Object> evidence = new ObjectMapper()
                .readValue(evidenceFile.toFile(), Map.class);
        assertThat(evidence)
                .containsEntry("schemaVersion",
                        "eutxo-zk-network-acceptance/v1")
                .containsEntry("authorizationProfile",
                        "zeroj-jubjub-dev-v1")
                .containsEntry("batchProfile",
                        "cardano-payment-b16")
                .containsEntry("trustedProverRequired", true)
                .containsEntry("fundsPolicy",
                        "disposable-test-funds-only");

        Map<String, Map<String, Object>> networks =
                (Map<String, Map<String, Object>>)
                        evidence.get("networks");
        assertThat(networks.keySet())
                .containsExactlyInAnyOrder(
                        "devnet", "preview", "preprod", "mainnet");
        assertThat(networks.get("devnet").get("policy"))
                .isEqualTo("ALLOWED");
        for (String network : List.of("preview", "preprod")) {
            assertThat(networks.get(network).get("policy"))
                    .isEqualTo("ACKNOWLEDGEMENT_REQUIRED");
        }
        assertThat(networks.get("mainnet").get("policy"))
                .isEqualTo("REJECTED");

        for (String network : List.of(
                "devnet", "preview", "preprod")) {
            Map<String, Object> result = networks.get(network);
            assertThat(status(result, "packagedLifecycle"))
                    .isEqualTo("PASSED");
            assertThat(status(result, "liveDepositToWithdrawal"))
                    .isEqualTo("NOT_EXERCISED");
            assertThat(status(result, "rollbackAndRecovery"))
                    .isEqualTo("NOT_EXERCISED");
            assertThat(status(result, "independentReconstruction"))
                    .isEqualTo("NOT_EXERCISED");
        }

        Map<String, Map<String, Object>> security =
                (Map<String, Map<String, Object>>)
                        evidence.get("securityGates");
        assertThat(security.get("maliciousProverSecurity")
                .get("status")).isEqualTo("BLOCKED_BY_PROFILE");
        assertThat(security.values())
                .extracting(gate -> gate.get("status"))
                .doesNotContain("PASSED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eutxoPreviewReleaseContractPinsCatalogAndRemainsExperimental()
            throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path acceptance = repository.resolve(
                "appchain/extensions/eutxo-zk/acceptance");
        Path contractFile = acceptance.resolve(
                "preview-release-contract-v1.json");
        assertThat(contractFile).isRegularFile();
        assertThat(acceptance.resolve(
                "eutxo-zk-preview-release-contract.schema.json"))
                .isRegularFile();

        Map<String, Object> contract = new ObjectMapper()
                .readValue(contractFile.toFile(), Map.class);
        assertThat(contract)
                .containsEntry("releaseDecision",
                        "EXPERIMENTAL_TESTNET_ONLY")
                .containsEntry("recipe",
                        "eutxo-zeroj-preview:1")
                .containsEntry("runtimeTypes", List.of("jvm"))
                .containsEntry("supportedNetworks",
                        List.of("devnet", "preview", "preprod"))
                .containsEntry("mainnet", "REJECTED")
                .containsEntry("trustedProverRequired", true)
                .containsEntry("fundsPolicy",
                        "disposable-test-funds-only");

        AppChainProjectCatalog catalog = new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework());
        AppChainProjectModel.Recipe recipe =
                catalog.recipe("eutxo-zeroj-preview");
        assertThat(recipe.maturity()).isEqualTo("experimental");
        assertThat(recipe.availability()).isEqualTo("EXPERIMENTAL");
        assertThat(recipe.runtimeTypes()).containsExactly("jvm");
        assertThat(recipe.supportedNetworks())
                .containsExactly("devnet", "preview", "preprod");
        assertThat(catalog.capability("rollup:zeroj-cardano")
                .effectiveSelectable()).isFalse();

        Map<String, String> identities =
                (Map<String, String>) contract.get("identities");
        Map<String, String> properties =
                catalog.capability("settlement:zeroj-validity")
                        .properties();
        assertThat(identities)
                .containsEntry("yanoVersion",
                        catalog.releaseIndex().yanoVersion())
                .containsEntry("transactionFormat",
                        properties.get(
                                "machines.eutxo.validity."
                                        + "transaction-format"))
                .containsEntry("authorizationProfile",
                        properties.get(
                                "machines.eutxo.validity."
                                        + "authorization-profile"))
                .containsEntry("authorizationProfileDigest",
                        properties.get(
                                "machines.eutxo.validity."
                                        + "authorization-profile-digest"))
                .containsEntry("batchProfile",
                        properties.get(
                                "machines.eutxo.validity.batch-profile"))
                .containsEntry("batchProfileDigest",
                        properties.get(
                                "machines.eutxo.validity."
                                        + "batch-profile-digest"))
                .containsEntry("circuitId",
                        properties.get(
                                "machines.eutxo.validity.circuit-id"))
                .containsEntry("zerojVersion",
                        properties.get(
                                "machines.eutxo.validity.zeroj-version"))
                .containsEntry("julcVersion",
                        properties.get(
                                "machines.eutxo.validity.julc-version"));

        Map<String, String> sourceDigests =
                (Map<String, String>) contract.get("sourceDigests");
        assertThat(sourceDigests)
                .containsEntry("capabilityCatalogSha256",
                        catalog.digests().get("capabilities"))
                .containsEntry("networkAcceptanceSha256",
                        sha256(acceptance.resolve(
                                "network-acceptance-v1.json")));
        assertThat((List<String>) contract.get("openSecurityGates"))
                .isNotEmpty()
                .contains("jubjub-adversarial-circuit-hardening",
                        "live-devnet-preview-preprod-round-trip",
                        "accountable-production-funds-approval");
    }

    @SuppressWarnings("unchecked")
    private static String status(
            Map<String, Object> network,
            String gate
    ) {
        return ((Map<String, Object>) network.get(gate))
                .get("status").toString();
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path)));
    }
}
