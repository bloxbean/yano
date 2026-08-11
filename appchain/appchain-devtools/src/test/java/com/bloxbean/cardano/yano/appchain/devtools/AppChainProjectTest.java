package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyProofV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainProjectTest {
    @TempDir
    Path temporary;

    @Test
    void embeddedDescriptorsResolveRecipesImplicationsArtifactsAndConsensusDefaults()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("evidence-ledger", "rotating", List.of()));

        assertThat(catalog.recipes()).extracting(AppChainProjectModel.Recipe::id)
                .containsExactly("audit-log", "owned-registry", "authenticated-map",
                        "approval-workflow",
                        "role-approval", "evidence-ledger", "eutxo-ledger",
                        "eutxo-cardano-bridge", "eutxo-zeroj-validity",
                        "eutxo-zeroj-preview", "custom-plugin");
        assertThat(catalog.recipes()).allSatisfy(recipe -> {
            assertThat(recipe.primaryOutcome()).isNotBlank();
            assertThat(recipe.firstCommand()).isNotBlank();
            assertThat(recipe.verificationQuery()).isNotBlank();
            assertThat(recipe.acceptanceScenario()).isNotBlank();
        });
        assertThat(resolution.selectedCapabilities()).contains(
                "state:role-evidence", "sequencer:rotating", "membership:static",
                "l1:slot-feed");
        assertThat(resolution.impliedCapabilities()).containsExactly("l1:slot-feed");
        assertThat(resolution.artifacts()).contains("yano-runtime", "appchain-stdlib",
                "appchain-evidence-profile", "appchain-role-workflow");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].effects.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].sequencer.mode", "rotating")
                .containsEntry("yano.app-chain.chains[0].block.max-bytes", "4194304")
                .containsEntry("yano.app-chain.chains[0].threshold", "2");
        assertThat(resolution.bootstrapRequired()).isTrue();
        assertThat(catalog.digests().values()).allMatch(value -> value.matches("[0-9a-f]{64}"));
        assertThat(catalog.digests()).containsEntry("blueprintSchema",
                golden("appchain-blueprint.schema.json"))
                .containsEntry("lockSchema", golden("appchain-lock.schema.json"))
                .containsEntry("capabilities", golden("appchain-capability-catalog.json"))
                .containsEntry("recipes", golden("appchain-recipe-catalog.json"))
                .containsEntry("firstPartyMetadata",
                        golden("appchain-first-party-metadata.json"))
                .containsEntry("releaseIndex",
                        packagedResourceDigest(
                                "appchain-release-capability-index.json"))
                .containsEntry("releaseAcceptanceIndex",
                        golden("appchain-release-acceptance-index.json"))
                .containsEntry("metadataTrustSchema",
                        golden("appchain-metadata-trust.schema.json"))
                .containsEntry("gitOpsLockSchema",
                        golden("appchain-gitops-lock.schema.json"))
                .containsEntry("componentCatalogSchema",
                        golden("appchain-component-catalog-schema.json"))
                .containsEntry("componentCatalogSnapshotSchema",
                        golden("appchain-component-catalog-snapshot-schema.json"));
        assertThat(catalog.releaseIndex().schemaStatus()).isEqualTo("alpha");
        assertThat(catalog.releaseIndex().stabilizationDecision())
                .isEqualTo("RETAIN_V1ALPHA1");
    }

    @Test
    void publicMemberKeysArePinnedAndCapabilityConflictsFailResolution() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        List<String> keys = List.of("a".repeat(64), "b".repeat(64), "c".repeat(64));

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("audit-log", "fixed", keys));

        assertThat(resolution.bootstrapRequired()).isFalse();
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].members", String.join(",", keys))
                .containsEntry("yano.app-chain.chains[0].sequencer.proposer", keys.getFirst());

        AppChainProjectModel.Blueprint conflicting = withCapabilities(
                blueprint("audit-log", "fixed", keys), List.of("state:kv-registry"));
        assertThatThrownBy(() -> resolver.resolve(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both provide exclusive contract state-machine");
    }

    @Test
    void authenticatedMapBlueprintCompilesSchemaAndPinsGenesis() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint blueprint = authenticatedMapBlueprint(
                "canonical-cbor", List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);

        String prefix = "yano.app-chain.chains[0].";
        assertThat(resolution.selectedCapabilities()).contains("state:authenticated-map");
        assertThat(resolution.consensusProperties())
                .containsEntry(prefix + "state-machine", "authenticated-map")
                .containsEntry(prefix + "state.commitment-profile", "mpf-blake2b256-v1")
                .containsKeys(prefix + "state.format-fingerprint",
                        prefix + "state.genesis-id",
                        prefix + "machines.authenticated-map.genesis-cbor-hex");
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapContract.decodeGenesis(
                Hex.decode(resolution.consensusProperties().get(
                        prefix + "machines.authenticated-map.genesis-cbor-hex")));
        assertThat(genesis.collections()).singleElement().satisfies(collection -> {
            assertThat(collection.id()).isEqualTo("products");
            assertThat(collection.valueEncoding())
                    .isEqualTo(AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR);
            assertThat(collection.validatorId()).isEqualTo("product-v1");
        });
        assertThat(genesis.validators()).singleElement()
                .satisfies(validator -> assertThat(validator.contractVersion())
                        .isEqualTo("yano-cbor-schema-ir-v1"));

        Path project = temporary.resolve("authenticated-map-project");
        new AppChainProjectRenderer(catalog, resolver).initialize(project, blueprint);
        assertThat(project.resolve("config/authenticated-map-genesis.hex")).isRegularFile();
        assertThat(project.resolve("docs/VALUE_VALIDATION.md")).isRegularFile();
        assertThat(new AppChainProjectLifecycle(properties).doctor(project, null).checks())
                .anySatisfy(check -> {
                    assertThat(check.id()).isEqualTo("authenticated-map-schema-encoding");
                    assertThat(check.status()).isEqualTo("PASS");
                });

        assertThatThrownBy(() -> resolver.resolve(authenticatedMapBlueprint(
                "canonical-cbor", List.of())))
                .hasMessageContaining("requires every topology.memberKeys");
    }

    @Test
    void governedAuthenticatedMapRendersClosedPublicGenesisAndReadiness() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(catalog, resolver);
        AppChainProjectModel.Blueprint blueprint = governedAuthenticatedMapBlueprint(true);

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);
        String encoded = resolution.consensusProperties().get(
                "yano.app-chain.chains[0].machines.authenticated-map.genesis-cbor-hex");
        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapContract.decodeGenesis(
                Hex.decode(encoded));

        assertThat(genesis.governedGenesis()).isNotNull();
        assertThat(genesis.governedGenesis().administratorAuthority()
                .administratorActorIds()).containsExactly("admin-a");
        assertThat(genesis.collections()).singleElement().satisfies(collection -> {
            assertThat(collection.authorization())
                    .isEqualTo(AuthenticatedMapContract.AUTH_GOVERNED_ROLE);
            assertThat(collection.authorizationPolicyId()).isEqualTo("issuer-write");
        });
        assertThat(resolution.artifacts()).contains("appchain-role-workflow");

        Path project = temporary.resolve("governed-authenticated-map");
        renderer.initialize(project, blueprint);
        assertThat(project.resolve("config/authenticated-map/governed-genesis-v1.hex"))
                .isRegularFile();
        assertThat(Files.readString(
                project.resolve("config/authenticated-map/identity.yaml")))
                .contains("domainApiVersion: \"authenticated-map-domain-v1\"");
        assertThat(project.resolve("config/authenticated-map/actors/admin-a-v1.hex"))
                .isRegularFile();
        assertThat(project.resolve("config/authenticated-map/direct-policies/"
                + "issuer-write-v1.hex")).isRegularFile();
        assertThat(project.resolve("docs/AUTHORIZATION.md")).isRegularFile();
        AppChainProjectModel.DoctorReport doctor =
                new AppChainProjectLifecycle(properties).doctor(project, null);
        assertThat(doctor.checks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("authenticated-map-governed-readiness");
            assertThat(check.status()).isEqualTo("PASS");
        });
    }

    @Test
    void doctorNamesGovernedCollectionWhosePolicyIsOnlyPlanned() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(catalog,
                new AppChainProjectResolver(properties, catalog));
        Path project = temporary.resolve("planned-policy");
        renderer.initialize(project, governedAuthenticatedMapBlueprint(false));

        AppChainProjectModel.DoctorReport doctor =
                new AppChainProjectLifecycle(properties).doctor(project, null);

        assertThat(doctor.status()).isEqualTo("DOCTOR_FAILED");
        assertThat(doctor.checks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("authenticated-map-governed-readiness");
            assertThat(check.status()).isEqualTo("FAIL");
            assertThat(check.detail()).contains("GOVERNED_COLLECTION_NOT_BOOTSTRAPPED",
                    "records->issuer-write", "onboarding planned");
        });
        assertThat(project.resolve("bootstrap/authenticated-map-onboarding.yaml"))
                .isRegularFile();
    }

    @Test
    void doctorNamesSchemaWithOpaqueEncodingInsteadOfOnlyReportingGenericFailure()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        Path project = Files.createDirectory(temporary.resolve("invalid-authenticated-map"));
        new ObjectMapper(new YAMLFactory()).writeValue(
                project.resolve("appchain.yaml").toFile(),
                authenticatedMapBlueprint(
                        "opaque", List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        AppChainProjectModel.DoctorReport report =
                new AppChainProjectLifecycle(properties).doctor(project, null);

        assertThat(report.status()).isEqualTo("DOCTOR_FAILED");
        assertThat(report.checks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("authenticated-map-schema-encoding");
            assertThat(check.status()).isEqualTo("FAIL");
            assertThat(check.detail()).contains("products", "canonical-cbor");
        });
    }

    @Test
    void genericOnApprovedEffectCapabilityUsesCustomRoutingTypeAndSharedValidation()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint selected = withAnswers(withCapabilities(
                        blueprint("approval-workflow", "fixed", List.of()),
                        List.of("effects:on-approved")),
                Map.of("effectType", "com.acme.erp.create-order"));

        AppChainProjectModel.Resolution resolution = resolver.resolve(selected);

        assertThat(resolution.selectedCapabilities()).contains(
                "state:approval-workflow", "effects:on-approved");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].effects.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "on-approved-effect.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "on-approved-effect.type", "com.acme.erp.create-order")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "activations.on-approved-effect", "1");

        assertThatThrownBy(() -> resolver.resolve(withCapabilities(
                blueprint("approval-workflow", "fixed", List.of()),
                List.of("effects:on-approved"))))
                .hasMessageContaining("require non-secret answers")
                .hasMessageContaining("effectType");
    }

    @Test
    void eutxoCapabilityPinsProfileAndRendersGenesisYaml() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint blueprint = withAnswers(withCapabilities(
                        blueprint("eutxo-ledger", "fixed",
                                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                        List.of()),
                Map.of(
                        "eutxoGenesisAddress", "addr_test1vr8nlm7example",
                        "eutxoGenesisLovelace", "100000000"));

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);

        assertThat(resolution.selectedCapabilities())
                .contains("state:eutxo-ledger", "profile:eutxo-plutus-v3",
                        "funding:eutxo-genesis");
        assertThat(resolution.artifacts()).contains("appchain-eutxo-ledger");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].state-machine", "eutxo-ledger")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.profile",
                        "yano-eutxo-v2-plutus-v3")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.expected-profile-digest",
                        "8cd4adb72def2c31dc8551a02f67429ea468bb2024dbe85a1dc7300590c9d1bf")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.genesis.address",
                        "addr_test1vr8nlm7example")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.genesis.lovelace",
                        "100000000");

        Path project = temporary.resolve("eutxo-project");
        new AppChainProjectRenderer(catalog, resolver).initialize(project, blueprint);
        assertThat(yamlValues(project.resolve("config/shared-consensus.yaml")))
                .containsAllEntriesOf(resolution.consensusProperties());
    }

    @Test
    void eutxoAnswersRejectLabeledAndMalformedPublicValuesBeforeRendering() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        Map<String, String> labeled = new java.util.LinkedHashMap<>(l2Answers());
        labeled.put("eutxoGenesisAddress", "addr_test1vr8nlm7example");
        labeled.put("eutxoGenesisLovelace", "100000000");
        labeled.put("eutxoL2Address",
                "L2_ADDRESS=addr_test1vr8nlm7example");

        assertThatThrownBy(() -> resolver.resolve(withAnswers(
                blueprint("eutxo-zeroj-validity", "fixed", List.of()), labeled)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eutxoL2Address")
                .hasMessageContaining("not NAME=value")
                .hasMessageNotContaining("L2_ADDRESS=");

        Map<String, String> malformedKey = new java.util.LinkedHashMap<>(labeled);
        malformedKey.put("eutxoL2Address", "addr_test1vr8nlm7example");
        malformedKey.put("eutxoL2PublicKey", "ABC123");
        assertThatThrownBy(() -> resolver.resolve(withAnswers(
                blueprint("eutxo-zeroj-validity", "fixed", List.of()), malformedKey)))
                .hasMessageContaining("eutxoL2PublicKey")
                .hasMessageContaining("64 lowercase hexadecimal");

        Map<String, String> malformedGenesis = Map.of(
                "eutxoGenesisAddress",
                "addr_test1vr8nlm7example",
                "eutxoGenesisLovelace",
                "not-a-number");
        assertThatThrownBy(() -> resolver.resolve(withAnswers(
                blueprint("eutxo-ledger", "fixed", List.of()), malformedGenesis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eutxoGenesisLovelace")
                .hasMessageContaining("bounded decimal integer");
    }

    @Test
    void zerojValidityPolicyAllowsTestnetsAndRejectsEveryMainnetSelectionPath()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        Map<String, String> genesis =
                new java.util.LinkedHashMap<>(l2Answers());
        genesis.putAll(Map.of(
                "eutxoGenesisAddress", "addr_test1vr8nlm7example",
                "eutxoGenesisLovelace", "100000000"));

        AppChainProjectModel.Blueprint preprod = withAnswers(
                blueprint("eutxo-zeroj-validity", "fixed", List.of()), genesis);
        assertThat(resolver.resolve(preprod).selectedCapabilities())
                .contains("state:eutxo-ledger", "profile:eutxo-key-payments",
                        "settlement:zeroj-validity");
        assertThat(resolver.resolve(preprod).consensusProperties())
                .containsEntry("yano.app-chain.chains[0].machines.eutxo.profile",
                        "yano-eutxo-v1")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.network",
                        "preprod")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.genesis."
                                + "l2-address",
                        "addr_test1vr8nlm7example")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.genesis."
                                + "l2-public-key",
                        "2".repeat(64))
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.genesis."
                                + "l2-key-epoch",
                        "1")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.expected-profile-digest",
                        "2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "transaction-format",
                        "yano-eutxo-l2-envelope-v1")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "expected-profile-digest",
                        "cfe1767761cbe05c7e2b82f951222fbb9df34afa5eb1f39fb8a5c1cc2af87d45")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity.circuit-id",
                        "eutxo-jubjub-batch-dev-b16-v4")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "batch-profile",
                        "cardano-payment-b16")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "batch-profile-digest",
                        "bd0835736116cb6338a82069e913f381"
                                + "5547008e4994508f110065c5dfc64747")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity.zeroj-version",
                        "0.1.0-pre10")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity.julc-version",
                        "0.1.0-pre14")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "authorization-profile",
                        "zeroj-jubjub-dev-v1")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "authorization-trusted-prover-required",
                        "true")
                .containsEntry(
                        "yano.app-chain.chains[0].machines.eutxo.validity."
                                + "funds-policy",
                        "disposable-test-funds-only");

        assertThat(catalog.recipe("eutxo-zeroj-validity")
                .effectiveSupportedNetworks())
                .containsExactly("devnet", "preview", "preprod");
        assertThat(catalog.capability("settlement:zeroj-validity")
                .effectiveSupportedNetworks())
                .containsExactly("devnet", "preview", "preprod");
        assertThat(catalog.recipe("audit-log").effectiveSupportedNetworks())
                .containsExactly("devnet", "preview", "preprod", "mainnet");

        assertThatThrownBy(() -> resolver.resolve(
                withNetwork(preprod, "mainnet")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eutxo-zeroj-validity")
                .hasMessageContaining("does not support network mainnet");
        Path rejectedOutput = temporary.resolve("rejected-mainnet-zeroj");
        assertThatThrownBy(() -> new AppChainProjectRenderer(catalog, resolver)
                .initialize(rejectedOutput, withNetwork(preprod, "mainnet")))
                .hasMessageContaining("does not support network mainnet");
        assertThat(rejectedOutput).doesNotExist();

        AppChainProjectModel.Blueprint explicitCapability = withNetwork(
                withCapabilities(blueprint("audit-log", "fixed", List.of()),
                        List.of("settlement:zeroj-validity")),
                "mainnet");
        assertThatThrownBy(() -> resolver.resolve(explicitCapability))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settlement:zeroj-validity")
                .hasMessageContaining("does not support network mainnet");

        AppChainProjectModel.Blueprint previewRecipe =
                withAnswers(withNetwork(blueprint(
                        "eutxo-zeroj-preview",
                        "fixed",
                        List.of()), "preview"), previewAnswers());
        assertThatThrownBy(() -> resolver.resolve(previewRecipe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        AppChainProjectResolver
                                .EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT);
        AppChainProjectModel.Blueprint acknowledged =
                withAcknowledgements(
                        previewRecipe,
                        List.of(AppChainProjectResolver
                                .EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT));
        assertThat(resolver.resolve(acknowledged)
                .selectedCapabilities())
                .contains("settlement:zeroj-validity",
                        "profile:eutxo-key-payments",
                        "bridge:cardano-federated",
                        "l1:slot-feed");
        Path previewOutput =
                temporary.resolve("acknowledged-zeroj-preview");
        AppChainProjectModel.Lock lock =
                new AppChainProjectRenderer(catalog, resolver)
                        .initialize(previewOutput, acknowledged);
        assertThat(lock.acknowledgements())
                .contains(
                        "EUTXO_ZEROJ_TRUSTED_PROVER_TEST_FUNDS_ONLY",
                        AppChainProjectResolver
                                .EUTXO_UNSAFE_TESTNET_ACKNOWLEDGEMENT);
    }

    @Test
    void eutxoBridgeRecipeUsesProjectChainIdAndExcludesVirtualGenesis() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint blueprint = withAnswers(
                blueprint("eutxo-cardano-bridge", "fixed",
                        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                bridgeAnswers());

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);

        assertThat(resolution.selectedCapabilities())
                .contains("state:eutxo-ledger", "profile:eutxo-plutus-v3",
                        "bridge:cardano-federated", "l1:slot-feed")
                .doesNotContain("funding:eutxo-genesis");
        assertThat(resolution.consensusProperties())
                .containsEntry(
                        "yano.app-chain.chains[0].l1.stability-depth",
                        "2")
                .containsEntry(
                        "yano.app-chain.chains[0].observers.bridge-deposits.chain-id",
                        "product-evidence")
                .doesNotContainKeys(
                        "yano.app-chain.chains[0].machines.eutxo.genesis.address",
                        "yano.app-chain.chains[0].machines.eutxo.genesis.lovelace");

        Map<String, String> unsafeAnswers =
                new java.util.LinkedHashMap<>(bridgeAnswers());
        unsafeAnswers.put("eutxoGenesisAddress", "addr_test1vr8nlm7example");
        unsafeAnswers.put("eutxoGenesisLovelace", "100000000");
        AppChainProjectModel.Blueprint unsafe = withAnswers(withCapabilities(
                        blueprint("eutxo-ledger", "fixed", List.of()),
                        List.of("bridge:cardano-federated")),
                unsafeAnswers);
        assertThatThrownBy(() -> resolver.resolve(unsafe))
                .hasMessageContaining("Conflicting capabilities")
                .hasMessageContaining("funding:eutxo-genesis");
    }

    private static Map<String, String> bridgeAnswers() {
        return Map.of(
                "bridgeVaultAddress", "addr_test1wzvault",
                "bridgeVaultScriptHash", "1".repeat(56),
                "bridgeMaxDepositLovelace", "100000000",
                "bridgeWithdrawalAddress", "addr_test1vwithdrawals",
                "bridgeEpoch", "1",
                "bridgeMaxWithdrawalLovelace", "50000000",
                "bridgeMaxPendingWithdrawals", "100");
    }

    private static Map<String, String> l2Answers() {
        return Map.of(
                "eutxoL2Address", "addr_test1vr8nlm7example",
                "eutxoL2PublicKey", "2".repeat(64));
    }

    private static Map<String, String> previewAnswers() {
        Map<String, String> answers =
                new java.util.LinkedHashMap<>(bridgeAnswers());
        answers.putAll(l2Answers());
        return Map.copyOf(answers);
    }

    @Test
    void renderingIsByteDeterministicSecretSafeAndRefusesManualGeneratedEdits()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        AppChainProjectModel.Blueprint blueprint = blueprint(
                "owned-registry", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        AppChainProjectModel.Lock firstLock = renderer.initialize(first, blueprint);
        AppChainProjectModel.Lock secondLock = renderer.initialize(second, blueprint);

        assertThat(fileDigests(first)).isEqualTo(fileDigests(second));
        assertThat(firstLock).isEqualTo(secondLock);
        assertThat(firstLock.generatedFiles()).containsKeys(
                "config/shared-consensus.yaml", "scripts/start", "secrets/.gitignore",
                "ci/verify", ".github/workflows/appchain-verify.yml",
                "ai/configure-yano-appchain/SKILL.md", "plans/prerequisites.yaml",
                "docs/PREREQUISITES.md");
        assertShellSyntax(first.resolve("ci/verify"));
        assertThat(Files.readString(first.resolve(".github/workflows/appchain-verify.yml")))
                .contains("YANO_DISTRIBUTION_SHA256", "sha256sum --check", "download=(curl")
                .doesNotContain("secrets.");
        new ObjectMapper(new YAMLFactory()).readTree(
                Files.readAllBytes(first.resolve(".github/workflows/appchain-verify.yml")));
        assertThat(Files.readString(first.resolve("ai/configure-yano-appchain/SKILL.md")))
                .contains("name: configure-yano-appchain", "Never invent configuration keys");
        assertThat(Files.readString(first.resolve("secrets/node0.env.example")))
                .contains("YANO_APPCHAIN_SIGNING_KEY=")
                .contains("YANO_APPCHAIN_API_KEYS=")
                .doesNotContain("a".repeat(64));
        Path nodeConfig = first.resolve("config/nodes/node0.yaml");
        assertThat(yamlValues(nodeConfig))
                .containsEntry("yano.app-chain.validation.strict", "true")
                .containsEntry("yano.app-chain.dx.resolved-config-digest",
                        firstLock.resolvedConfigDigest())
                .containsEntry("yano.app-chain.dx.release-catalog-digest",
                        firstLock.catalogDigests().get("releaseIndex"))
                .containsEntry("yano.app-chain.api.keys", "${YANO_APPCHAIN_API_KEYS:}");
        assertThat(Files.readString(nodeConfig))
                .contains("yano:", "app-chain:", "chains:")
                .doesNotContain("yano.app-chain.");
        assertThat(yamlValues(first.resolve("config/shared-consensus.yaml")))
                .isEqualTo(firstLock.consensusValues());
        assertThat(allText(first)).doesNotContain(temporary.toString());

        Files.writeString(first.resolve("config/shared-consensus.yaml"),
                "# manual edit\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> renderer.render(first))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("manual edits");
    }

    @Test
    void regenerationRefusesAUserFileCollidingWithANewRendererOutput() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path project = temporary.resolve("new-output-collision");
        renderer.initialize(project, blueprint("audit-log", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode lock = (ObjectNode) mapper.readTree(project.resolve("appchain.lock").toFile());
        ((ObjectNode) lock.path("generatedFiles")).remove("README.md");
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                project.resolve("appchain.lock").toFile(), lock);
        Files.writeString(project.resolve("README.md"), "user-owned notes\n");

        assertThatThrownBy(() -> renderer.render(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("user-owned path")
                .hasMessageContaining("README.md");
        assertThat(Files.readString(project.resolve("README.md")))
                .isEqualTo("user-owned notes\n");
    }

    @Test
    void studioBlueprintCanBeMaterializedWithoutASeedLock() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path source = temporary.resolve("studio-source");
        Path imported = Files.createDirectory(temporary.resolve("studio-import"));
        renderer.initialize(source, blueprint("audit-log", "fixed", List.of()));
        Files.copy(source.resolve("appchain.yaml"), imported.resolve("appchain.yaml"));

        AppChainProjectModel.Lock lock = renderer.render(imported);

        assertThat(lock.recipe()).isEqualTo("audit-log:1");
        assertThat(imported.resolve("appchain.lock")).isRegularFile();
        assertThat(imported.resolve("scripts/start")).isExecutable();
    }

    @Test
    void cliSupportsNonInteractiveAndGuidedInitializationAndSafeRegeneration()
            throws Exception {
        Path nonInteractive = temporary.resolve("registry-project");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        AppChainDevtoolsCli cli = new AppChainDevtoolsCli();

        int init = cli.run(new String[]{
                        "appchain", "init", "--non-interactive",
                        "--recipe", "owned-registry", "--network", "devnet",
                        "--members", "3", "--output", nonInteractive.toString(),
                        "--http-port-base", "18080", "--server-port-base", "23337",
                        "--format", "json"
                }, new PrintWriter(output), new PrintWriter(error));

        assertThat(init).isZero();
        assertThat(error.toString()).isEmpty();
        assertThat(output.toString()).contains("PROJECT_INITIALIZED", "owned-registry:1")
                .doesNotContain(temporary.toString());
        assertThat(nonInteractive.resolve("appchain.yaml")).isRegularFile();
        assertThat(nonInteractive.resolve("appchain.lock")).isRegularFile();
        assertThat(yamlValues(nonInteractive.resolve("config/nodes/node1.yaml")))
                .containsEntry("yano.block-producer.enabled", "false")
                .containsEntry("yano.remote.host", "127.0.0.1")
                .containsEntry("quarkus.http.port", "18081")
                .containsEntry("yano.server.port", "23338");

        output.getBuffer().setLength(0);
        int render = cli.run(new String[]{"render", nonInteractive.toString()},
                new PrintWriter(output), new PrintWriter(error));
        assertThat(render).isZero();
        assertThat(output.toString()).contains("PROJECT_RENDERED");

        Path guided = temporary.resolve("guided");
        StringWriter guidedOutput = new StringWriter();
        AppChainProjectCli projectCli = new AppChainProjectCli(
                new BufferedReader(new StringReader("audit-log\npreprod\n2\n")),
                new PrintWriter(guidedOutput), AppChainPropertyRegistry.framework(),
                new ObjectMapper());
        assertThat(projectCli.run(new String[]{"init", "--output", guided.toString()})).isZero();
        assertThat(guidedOutput.toString()).contains("Recipe [audit-log]", "PROJECT_INITIALIZED");
    }

    @Test
    void everyM1RecipeResolvesForAdvertisedRuntimeAndDeploymentTargets() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        int sequence = 0;
        for (String recipe : List.of("audit-log", "owned-registry", "authenticated-map",
                "approval-workflow", "role-approval", "evidence-ledger", "eutxo-ledger",
                "custom-plugin")) {
            List<String> runtimes = "custom-plugin".equals(recipe)
                    || "eutxo-ledger".equals(recipe)
                    ? List.of("jvm") : List.of("jvm", "native");
            for (String runtime : runtimes) {
                for (String deployment : List.of("host", "docker-compose")) {
                    AppChainProjectModel.Blueprint source = "authenticated-map".equals(recipe)
                            ? authenticatedMapBlueprint("canonical-cbor",
                            List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)))
                            : blueprint(recipe, "fixed", List.of());
                    AppChainProjectModel.Blueprint blueprint = withTarget(
                            source, runtime, deployment);
                    if ("custom-plugin".equals(recipe)) {
                        blueprint = withAnswers(blueprint,
                                Map.of("stateMachine", "com.example.custom-machine"));
                    } else if ("eutxo-ledger".equals(recipe)) {
                        blueprint = withAnswers(blueprint, Map.of(
                                "eutxoGenesisAddress", "addr_test1vr8nlm7example",
                                "eutxoGenesisLovelace", "100000000"));
                    }
                    Path project = temporary.resolve("matrix-" + sequence++);

                    AppChainProjectModel.Lock lock = renderer.initialize(project, blueprint);

                    assertThat(lock.runtime()).isEqualTo(runtime);
                    assertThat(lock.deployment()).isEqualTo(deployment);
                    assertThat(lock.artifacts()).isNotEmpty();
                    assertThat(project.resolve("scripts/start")).isExecutable();
                    assertThat(Files.readString(project.resolve("plans/prerequisites.yaml")))
                            .contains("primaryOutcome:", "firstCommand:",
                                    "verificationQuery:", "acceptanceScenario:");
                    if ("role-approval".equals(recipe) || "evidence-ledger".equals(recipe)) {
                        assertThat(project.resolve("bootstrap/role-approvals-plan.yaml"))
                                .isRegularFile();
                        assertThat(Files.readString(project.resolve("bootstrap/README.md")))
                                .contains("./yano.sh appchain role public-key",
                                        "query the exact record", "does not execute the payload")
                                .doesNotContain("privateKey", "seed:");
                        assertThat(Files.readString(
                                project.resolve("bootstrap/role-approvals-plan.yaml")))
                                .contains("QUERY_COMMITTED_RECORD_AND_VERIFY_PROOF",
                                        "SKIP_AND_RECORD_PROOF", "FAIL_CLOSED",
                                        "replacement: FORBIDDEN")
                                .doesNotContain("privateKey", "seed:");
                    }
                    assertShellSyntax(project.resolve("scripts/start"));
                    assertShellSyntax(project.resolve("scripts/stop"));
                    assertShellSyntax(project.resolve("scripts/status"));
                    if ("host".equals(deployment)) {
                        assertThat(Files.readString(project.resolve("scripts/stop")))
                                .contains("did not stop within 10 seconds")
                                .doesNotContain("records[@]");
                        assertScriptSucceeds(project.resolve("scripts/stop"));
                    }
                    if (Files.exists(project.resolve("scripts/start-node"))) {
                        assertShellSyntax(project.resolve("scripts/start-node"));
                    }
                    assertThat(project.resolve("compose.yaml").toFile().exists())
                            .isEqualTo("docker-compose".equals(deployment));
                    if ("docker-compose".equals(deployment)) {
                        assertThat(Files.readString(project.resolve("compose.yaml")))
                                .contains("YANO_PROFILE: preprod")
                                .doesNotContain("YANO_PROFILE: preprod,appchain")
                                .doesNotContain("entrypoint:");
                        var nodeValues = assertThat(yamlValues(
                                project.resolve("config/nodes/node0.yaml")))
                                .containsEntry("yano.storage.path", "/app/chainstate")
                                .containsEntry("yano.app-chain.storage.path",
                                        "/app/appchain-chainstate");
                        if ("eutxo-ledger".equals(recipe)) {
                            nodeValues.containsEntry(
                                    "yano.plugins.bundle.\"com.bloxbean.cardano.yano.appchain.eutxo.indexer\".storage-path",
                                    "/app/appchain-indexers");
                        }
                        var compose = assertThat(Files.readString(
                                project.resolve("compose.yaml")))
                                .contains("node0-data:/app/chainstate")
                                .contains("node0-appchain-data:/app/appchain-chainstate")
                                .doesNotContain("node0-data:/project");
                        if ("eutxo-ledger".equals(recipe)) {
                            compose.contains(
                                    "node0-appchain-indexers:/app/appchain-indexers");
                        }
                    }
                }
            }
        }
    }

    @Test
    void authenticatedMapRecipeInitializationEmitsEditableIntentAndCanonicalGenesis()
            throws Exception {
        Path project = temporary.resolve("authenticated-map-init");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(new String[]{
                "appchain", "init", "--non-interactive",
                "--recipe", "authenticated-map", "--network", "preprod",
                "--members", "3",
                "--member-key", "a".repeat(64),
                "--member-key", "b".repeat(64),
                "--member-key", "c".repeat(64),
                "--output", project.toString()
        }, new PrintWriter(output), new PrintWriter(error));

        assertThat(exit).isZero();
        assertThat(error.toString()).isEmpty();
        assertThat(Files.readString(project.resolve("appchain.yaml")))
                .contains("authenticatedMap:", "profile: \"mpf-blake2b256-v1\"",
                        "valueEncoding: \"opaque\"")
                .doesNotContain("validator: null", "httpPortBase: null",
                        "serverPortBase: null");
        assertThat(project.resolve("config/authenticated-map-genesis.hex")).isRegularFile();
    }

    private static void assertShellSyntax(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", "-n", script.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", script, diagnostics).isZero();
    }

    private static void assertScriptSucceeds(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", script, diagnostics).isZero();
    }

    @Test
    void hostTargetCanRenderPortablePerMachinePeerOverlays() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        AppChainProjectModel.Blueprint blueprint = withHosts(
                blueprint("audit-log", "fixed",
                        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                List.of("node-a.example", "node-b.example", "node-c.example"));
        Path project = temporary.resolve("multi-machine");

        renderer.initialize(project, blueprint);

        assertThat(yamlValues(project.resolve("config/nodes/node0.yaml")))
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node-b.example:13337,node-c.example:13337")
                .containsEntry("yano.server.port", "13337");
        assertThat(yamlValues(project.resolve("config/nodes/node2.yaml")))
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node-a.example:13337,node-b.example:13337")
                .containsEntry("quarkus.http.port", "8080");
        assertThat(Files.readString(project.resolve("scripts/start")))
                .contains("Usage: start NODE_INDEX")
                .doesNotContain("for node in");
    }

    @Test
    void m2ProjectLifecycleValidatesDoctorsDiffsAndMigrates() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path project = temporary.resolve("lifecycle");
        renderer.initialize(project, blueprint("approval-workflow", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        AppChainProjectModel.ProjectValidation validation = lifecycle.validate(project);
        assertThat(validation.lock().recipe()).isEqualTo("approval-workflow:1");
        assertThat(validation.generatedFileCount()).isGreaterThan(10);
        assertThat(Files.readString(project.resolve("docs/TRUST.md"))).contains("Trust model");
        assertThat(Files.readString(project.resolve("docs/BOOTSTRAP.md"))).contains("Bootstrap");
        assertThat(Files.readString(project.resolve("docs/VERIFY.md")))
                .contains("validate --mode project");

        Path oldLock = temporary.resolve("old.lock");
        Files.copy(project.resolve("appchain.lock"), oldLock);
        String blueprint = Files.readString(project.resolve("appchain.yaml"));
        Files.writeString(project.resolve("appchain.yaml"),
                blueprint.replace("two-thirds", "all"));
        renderer.render(project);

        AppChainProjectModel.LockDiff difference = lifecycle.diff(
                oldLock, project.resolve("appchain.lock"));
        assertThat(difference.status()).isEqualTo("CHANGESET");
        assertThat(difference.changes()).anySatisfy(change -> {
            assertThat(change.key()).endsWith(".threshold");
            assertThat(change.policy()).isEqualTo("GOVERNED_ACTIVATION");
        });
        assertThat(lifecycle.migrate(project, true)).isEqualTo(
                "NO_MIGRATION_REQUIRED_DRY_RUN");

        Path distribution = Files.createDirectory(temporary.resolve("release"));
        Files.write(distribution.resolve("yano.jar"), new byte[]{0});
        Path index = distribution.resolve(
                "tools/yano-appchain/metadata/appchain-dx/v1alpha1/"
                        + "appchain-release-capability-index.json");
        Files.createDirectories(index.getParent());
        Files.write(index, catalog.releaseIndexBytes());
        AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(project, distribution);
        assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
        assertThat(doctor.checks()).allMatch(check ->
                "PASS".equals(check.status()) || "NOT_REQUIRED".equals(check.status()));
        assertThat(doctor.checks()).extracting(AppChainProjectModel.DoctorCheck::id)
                .contains("CONFIG_VALID", "ARTIFACTS_READY", "IDENTITIES_READY",
                        "RUNTIME_STARTABLE", "APPLICATION_BOOTSTRAPPED", "EXECUTORS_READY",
                        "EXTERNAL_TARGETS_READY", "OUTCOME_READY");
    }

    @Test
    void customPluginRecipeRequiresAnAnswerAndRejectsNativeRuntime() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint custom = blueprint(
                "custom-plugin", "fixed", List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        assertThatThrownBy(() -> resolver.resolve(custom))
                .hasMessageContaining("require non-secret answers")
                .hasMessageContaining("stateMachine");
        AppChainProjectModel.Resolution resolved = resolver.resolve(withAnswers(
                custom, Map.of("stateMachine", "com.example.reviewed")));
        assertThat(resolved.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].state-machine",
                        "com.example.reviewed");
        assertThatThrownBy(() -> resolver.resolve(withTarget(withAnswers(custom,
                Map.of("stateMachine", "com.example.reviewed")), "native", "host")))
                .hasMessageContaining("does not support runtime native");
    }

    @Test
    void standaloneJvmToolingInspectsEveryAdvertisedNativeDistributionFlavor() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        assertThat(catalog.releaseIndex().distributions())
                .filteredOn(flavor -> "native".equals(flavor.runtimeType()))
                .allMatch(flavor -> "external-version-matched".equals(flavor.tooling()));

        for (String executable : List.of("yano-native-test/yano", "yano-native-test/yano.exe")) {
            Path archive = temporary.resolve(executable.endsWith(".exe")
                    ? "native-windows.zip" : "native-unix.zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry(executable));
                output.write(0);
                output.closeEntry();
                output.putNextEntry(new ZipEntry(
                        "yano-native-test/config/schema/"
                                + "appchain-release-capability-index.json"));
                output.write(catalog.releaseIndexBytes());
                output.closeEntry();
            }

            AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(null, archive);
            assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
            assertThat(doctor.checks()).allMatch(check -> "PASS".equals(check.status()));
        }
    }

    @Test
    void doctorInspectsRootlessJvmDistributionArchives() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path archive = temporary.resolve("rootless-jvm.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("yano.jar"));
            output.write(0);
            output.closeEntry();
            output.putNextEntry(new ZipEntry("config/schema/"
                    + "appchain-release-capability-index.json"));
            output.write(catalog.releaseIndexBytes());
            output.closeEntry();
        }

        AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(null, archive);

        assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
        assertThat(doctor.checks()).allMatch(check -> "PASS".equals(check.status()));
    }

    @Test
    void completeCatalogIsTruthfulAndDistributionCapabilitiesCannotBeSelected()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);

        assertThat(catalog.capabilities()).hasSize(43)
                .allSatisfy(capability -> {
                    assertThat(capability.availability()).isIn(
                            "BUNDLED", "FIRST_PARTY_OPTIONAL", "REFERENCE", "EXPERIMENTAL");
                    assertThat(capability.effectiveScope()).isIn(
                            "chain", "node", "distribution");
                    assertThat(capability.trustStatement()).isNotBlank();
                    assertThat(capability.documentation()).isNotBlank();
                    assertThat(capability.acceptanceScenario()).isNotBlank();
                });
        assertThat(catalog.capabilities())
                .filteredOn(capability -> "ui:console".equals(capability.id()))
                .singleElement()
                .satisfies(capability -> {
                    assertThat(capability.effectiveScope()).isEqualTo("node");
                    assertThat(capability.effectiveSelectable()).isFalse();
                });
        assertThatThrownBy(() -> resolver.resolve(withCapabilities(
                blueprint("audit-log", "fixed", List.of()), List.of("ui:console"))))
                .hasMessageContaining("not selectable")
                .hasMessageContaining("ui:console");
        assertThatThrownBy(() -> resolver.resolve(withCapabilities(
                blueprint("audit-log", "fixed", List.of()),
                        List.of("rollup:zeroj-cardano"))))
                .hasMessageContaining("not selectable")
                .hasMessageContaining("rollup:zeroj-cardano");
        assertThat(catalog.capabilities())
                .filteredOn(capability -> "observability:prometheus".equals(capability.id()))
                .singleElement()
                .satisfies(capability -> {
                    assertThat(capability.effectiveScope()).isEqualTo("distribution");
                    assertThat(capability.effectiveSelectable()).isFalse();
                });

        AppChainProjectModel.Resolution governed = resolver.resolve(withMembership(
                blueprint("audit-log", "fixed", List.of()), "governed"));
        assertThat(governed.selectedCapabilities()).contains("membership:governed");
        assertThat(governed.consensusProperties()).containsEntry(
                "yano.app-chain.chains[0].membership.mode", "governed");
    }

    @Test
    void nodeScopedCapabilitiesStayOutOfConsensusAndRenderOnlyNodeConfiguration()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint blueprint = withAnswers(withCapabilities(
                        blueprint("approval-workflow", "fixed",
                                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                        List.of("effects:on-approved", "executor:webhook")),
                Map.of("effectType", "webhook.post",
                        "webhookUrl", "https://hooks.example.test/yano"));

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);

        assertThat(resolution.consensusProperties().keySet())
                .noneMatch(key -> key.contains("effects.executors.webhook"));
        assertThat(resolution.nodePropertyTemplate())
                .containsEntry("yano.app-chain.chains[0].effects.executor.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].effects.executors.webhook.url",
                        "https://hooks.example.test/yano");
        Path project = temporary.resolve("webhook-node-scope");
        new AppChainProjectRenderer(catalog, resolver).initialize(project, blueprint);
        assertThat(yamlValues(project.resolve("config/shared-consensus.yaml")).keySet())
                .noneMatch(key -> key.contains("effects.executors.webhook"));
        assertThat(yamlValues(project.resolve("config/nodes/node1.yaml")))
                .containsEntry("yano.app-chain.chains[0].effects.executors.webhook.url",
                        "https://hooks.example.test/yano");
    }

    @Test
    void optionalConnectorSelectionGeneratesPlansAndStagedReadinessWithoutFalseFailure()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(catalog, resolver);
        Path project = temporary.resolve("optional-kafka");
        renderer.initialize(project, withCapabilities(
                blueprint("audit-log", "fixed",
                        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                List.of("executor:kafka")));

        assertThat(Files.readString(project.resolve("plans/prerequisites.yaml")))
                .contains("appchain-kafka", "FIRST_PARTY_OPTIONAL",
                        "provide-node-local-kafka-configuration")
                .doesNotContain("password", "private-key", "mnemonic");
        assertThat(Files.readString(project.resolve("docs/PREREQUISITES.md")))
                .contains("FIRST_PARTY_OPTIONAL", "executor:kafka");

        Path distribution = Files.createDirectory(temporary.resolve("stock-release"));
        Files.write(distribution.resolve("yano.jar"), new byte[]{0});
        Path index = distribution.resolve(
                "tools/yano-appchain/metadata/appchain-dx/v1alpha1/"
                        + "appchain-release-capability-index.json");
        Files.createDirectories(index.getParent());
        Files.write(index, catalog.releaseIndexBytes());
        AppChainProjectModel.DoctorReport doctor =
                new AppChainProjectLifecycle(properties).doctor(project, distribution);

        assertThat(doctor.status()).isEqualTo("DOCTOR_WARNINGS");
        assertThat(doctor.checks()).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("artifact:appchain-kafka");
            assertThat(check.status()).isEqualTo("PENDING");
        }).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("EXECUTORS_READY");
            assertThat(check.status()).isEqualTo("PENDING");
        }).anySatisfy(check -> {
            assertThat(check.id()).isEqualTo("OUTCOME_READY");
            assertThat(check.status()).isEqualTo("PENDING");
        });
    }

    @Test
    void gitOpsExportsAreDeterministicSecretFreeAndBoundToValidatedSource() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(catalog, resolver);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path project = temporary.resolve("gitops-project");
        renderer.initialize(project, blueprint("owned-registry", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        Path first = temporary.resolve("kustomize-one");
        Path second = temporary.resolve("kustomize-two");
        AppChainProjectModel.GitOpsResult exported = lifecycle.gitOps(
                project, AppChainGitOpsExporter.Target.KUSTOMIZE, first);
        lifecycle.gitOps(project, AppChainGitOpsExporter.Target.KUSTOMIZE, second);
        Path helm = temporary.resolve("helm");
        lifecycle.gitOps(project, AppChainGitOpsExporter.Target.HELM, helm);

        assertThat(exported.status()).isEqualTo("GITOPS_EXPORTED");
        assertThat(fileDigests(first)).isEqualTo(fileDigests(second));
        assertThat(yamlValues(first.resolve("files/node0.yaml")))
                .containsEntry("yano.storage.path", "/var/lib/yano/chainstate")
                .containsEntry("yano.app-chain.storage.path",
                        "/var/lib/yano/appchain-chainstate")
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node1:13337,node2:13337");
        assertThat(Files.readString(first.resolve("node0.yaml")))
                .contains("secretRef:", "yano-appchain-node0")
                .doesNotContain("YANO_APPCHAIN_SIGNING_KEY=");
        assertThat(Files.readString(first.resolve("gitops.lock")))
                .contains("sourceBlueprintDigest", "sourceResolvedConfigDigest",
                        "sourceReleaseCatalogDigest");
        assertThat(helm.resolve("Chart.yaml")).isRegularFile();
        assertThat(helm.resolve("templates/nodes.yaml")).isRegularFile();
        assertThat(allText(helm)).doesNotContain("YANO_APPCHAIN_SIGNING_KEY=");

        Path nonEmpty = Files.createDirectory(temporary.resolve("non-empty"));
        Files.writeString(nonEmpty.resolve("keep"), "user data");
        assertThatThrownBy(() -> lifecycle.gitOps(
                project, AppChainGitOpsExporter.Target.HELM, nonEmpty))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must be empty");

        Path devnet = temporary.resolve("devnet-project");
        renderer.initialize(devnet, withNetwork(
                blueprint("audit-log", "fixed", List.of()), "devnet"));
        assertThatThrownBy(() -> lifecycle.gitOps(devnet,
                AppChainGitOpsExporter.Target.KUSTOMIZE, temporary.resolve("devnet-export")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ephemeral genesis");
    }

    private static AppChainProjectModel.Blueprint blueprint(
            String recipe,
            String sequencing,
            List<String> memberKeys) {
        return new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("product-evidence"),
                new AppChainProjectModel.Spec(
                        "0.1.0-test",
                        "preprod",
                        new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"),
                        List.of(new AppChainProjectModel.ChainIntent(
                                "product-evidence", recipe, List.of(), Map.of(),
                                new AppChainProjectModel.Topology(
                                        3, memberKeys, List.of(),
                                        "two-thirds", sequencing, "static", null, null)))));
    }

    private static AppChainProjectModel.Blueprint authenticatedMapBlueprint(
            String valueEncoding,
            List<String> memberKeys
    ) {
        AppChainProjectModel.Blueprint base = blueprint(
                "authenticated-map", "fixed", memberKeys);
        AppChainProjectModel.ChainIntent chain = base.spec().chains().getFirst();
        AppChainProjectModel.AuthenticatedMapIntent authenticatedMap =
                new AppChainProjectModel.AuthenticatedMapIntent(
                        "mpf-blake2b256-v1",
                        "00".repeat(32),
                        16,
                        65_536,
                        List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                                "products", "owner", false, 64, 1024,
                                valueEncoding, "product-v1")),
                        List.of(new AppChainProjectModel.AuthenticatedMapSchemaIntent(
                                "product-v1", "product",
                                "product = { sku: tstr .size (1..32), qty: uint .le 1000 }")));
        return replaceChain(base, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                chain.topology(), authenticatedMap));
    }

    private static AppChainProjectModel.Blueprint governedAuthenticatedMapBlueprint(
            boolean includePolicy
    ) {
        List<String> memberKeys = List.of(
                "a".repeat(64), "b".repeat(64), "c".repeat(64));
        AppChainProjectModel.Blueprint base = blueprint(
                "authenticated-map", "fixed", memberKeys);
        AppChainProjectModel.ChainIntent chain = base.spec().chains().getFirst();
        byte[] seed = Hex.decode("01".repeat(32));
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "admin-a-key", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                chain.chainId(), "admin-a", 1, key, seed);
        var actorKey = new AppChainProjectModel.AuthenticatedMapActorKeyIntent(
                key.keyId(), "ed25519", Hex.encode(publicKey),
                Hex.encode(proof.signature()), 1L, 0L, "active");
        var records = new AppChainProjectModel.AuthenticatedMapGenesisRecordsIntent(
                List.of(new AppChainProjectModel.AuthenticatedMapOrganizationIntent(
                        "operator-a", 1L, "active", null)),
                List.of(new AppChainProjectModel.AuthenticatedMapActorIntent(
                        "admin-a", 1L, "operator-a", "active",
                        List.of("registry-admin", "issuer"), List.of(actorKey), null)),
                includePolicy ? List.of(
                        new AppChainProjectModel.AuthenticatedMapDirectPolicyIntent(
                                "issuer-write", 1L, "active", "issuer", 100L))
                        : List.of(),
                List.of());
        var authenticatedMap = new AppChainProjectModel.AuthenticatedMapIntent(
                "mpf-blake2b256-v1", "00".repeat(32), 16, 65_536,
                List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                        "records", "governed-role", "issuer-write", false,
                        64, 1024, "canonical-cbor", null)),
                List.of(),
                new AppChainProjectModel.AuthenticatedMapGovernanceIntent(
                        "registry-admins", 1L, List.of("admin-a"), 1, 1000L),
                records, null,
                includePolicy ? List.of() : List.of(
                new AppChainProjectModel.AuthenticatedMapOnboardingIntent(
                        "direct-policy", "issuer-write", "activate before writes")));
        return replaceChain(base, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                chain.topology(), authenticatedMap));
    }

    private static AppChainProjectModel.Blueprint withMembership(
            AppChainProjectModel.Blueprint blueprint,
            String membership) {
        AppChainProjectModel.ChainIntent chain = blueprint.spec().chains().getFirst();
        AppChainProjectModel.Topology topology = chain.topology();
        return replaceChain(blueprint, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                new AppChainProjectModel.Topology(
                        topology.members(), topology.memberKeys(), topology.nodeHosts(),
                        topology.finality(), topology.sequencing(), membership,
                        topology.httpPortBase(), topology.serverPortBase())));
    }

    private static AppChainProjectModel.Blueprint withCapabilities(
            AppChainProjectModel.Blueprint blueprint,
            List<String> capabilities) {
        AppChainProjectModel.ChainIntent chain = blueprint.spec().chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), capabilities, chain.answers(), chain.topology());
        return replaceChain(blueprint, changed);
    }

    private static AppChainProjectModel.Blueprint replaceChain(
            AppChainProjectModel.Blueprint blueprint,
            AppChainProjectModel.ChainIntent changed) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static AppChainProjectModel.Blueprint withTarget(
            AppChainProjectModel.Blueprint blueprint,
            String runtime,
            String deployment) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(
                        spec.yanoVersion(), spec.network(),
                        new AppChainProjectModel.RuntimeSelection(runtime),
                        new AppChainProjectModel.DeploymentSelection(deployment),
                        spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withNetwork(
            AppChainProjectModel.Blueprint blueprint,
            String network) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), network, spec.runtime(),
                        spec.deployment(), spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withAcknowledgements(
            AppChainProjectModel.Blueprint blueprint,
            List<String> acknowledgements) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(),
                blueprint.metadata(),
                new AppChainProjectModel.Spec(
                        spec.yanoVersion(), spec.network(),
                        spec.runtime(), spec.deployment(),
                        spec.chains(), spec.componentCatalogs(),
                        acknowledgements));
    }

    private static AppChainProjectModel.Blueprint withHosts(
            AppChainProjectModel.Blueprint blueprint,
            List<String> hosts) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.Topology topology = chain.topology();
        AppChainProjectModel.Topology changedTopology = new AppChainProjectModel.Topology(
                topology.members(), topology.memberKeys(), hosts, topology.finality(),
                topology.sequencing(), topology.membership(), topology.httpPortBase(),
                topology.serverPortBase());
        AppChainProjectModel.ChainIntent changedChain = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(), changedTopology);
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changedChain)));
    }

    private static AppChainProjectModel.Blueprint withAnswers(
            AppChainProjectModel.Blueprint blueprint,
            Map<String, String> answers) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), answers, chain.topology());
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static Map<String, String> yamlValues(Path path) throws IOException {
        Map<String, String> values = new TreeMap<>();
        new AppChainConfigFileLoader().load(path).forEach((key, value) ->
                values.put(key, String.valueOf(value)));
        return values;
    }

    private static Map<String, String> fileDigests(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(),
                        AppChainProjectCatalog.sha256(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private static String allText(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private static String golden(String name) throws IOException {
        java.util.Properties hashes = new java.util.Properties();
        try (var input = AppChainProjectTest.class.getClassLoader().getResourceAsStream(
                "appchain-dx/v1alpha1/metadata.sha256")) {
            if (input == null) throw new IOException("missing project metadata golden hashes");
            hashes.load(input);
        }
        return hashes.getProperty(name);
    }

    private static String packagedResourceDigest(String name) throws IOException {
        try (var input = AppChainProjectTest.class.getClassLoader().getResourceAsStream(
                "appchain-dx/v1alpha1/" + name)) {
            if (input == null) throw new IOException("missing packaged metadata " + name);
            return AppChainProjectCatalog.sha256(input.readAllBytes());
        }
    }
}
