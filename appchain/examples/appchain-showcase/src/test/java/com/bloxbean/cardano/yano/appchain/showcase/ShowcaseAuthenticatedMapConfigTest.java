package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.appchain.stdlib.StdlibStateMachineProviders;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcaseAuthenticatedMapConfigTest {

    @Test
    void buildsReleasePinnedMultiCollectionGenesis() {
        byte[] closure = HexFormat.of().parseHex("42".repeat(32));
        Map<String, String> settings = ShowcaseAuthenticatedMapConfig.settings(
                ShowcaseAuthenticatedMapConfig.CHAIN_ID,
                List.of("11".repeat(32), "22".repeat(32), "33".repeat(32)),
                2,
                closure);

        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapContract.decodeGenesis(
                HexFormat.of().parseHex(settings.get(
                        StdlibStateMachineProviders.AUTHENTICATED_MAP_GENESIS_SETTING)));

        assertThat(genesis.chainId()).isEqualTo("authenticated-map-chain");
        assertThat(genesis.commitmentProfileId()).isEqualTo("mpf-blake2b256-v1");
        assertThat(genesis.maxBatchItems()).isEqualTo(32);
        assertThat(genesis.maxBatchBytes()).isEqualTo(65_536);
        assertThat(genesis.collections())
                .extracting(AuthenticatedMapContract.CollectionDescriptor::id)
                .containsExactly("attachments", "canonical-events", "governed-catalog",
                        "gtins", "products", "released-products");
        assertThat(genesis.collections())
                .filteredOn(collection -> collection.id().equals("attachments"))
                .singleElement()
                .satisfies(collection -> {
                    assertThat(collection.valueEncoding())
                            .isEqualTo(AuthenticatedMapContract.VALUE_ENCODING_OPAQUE);
                    assertThat(collection.validatorId()).isEmpty();
                });
        assertThat(genesis.collections())
                .filteredOn(collection -> collection.id().equals("canonical-events"))
                .singleElement()
                .satisfies(collection -> {
                    assertThat(collection.authorization())
                            .isEqualTo(AuthenticatedMapContract.AUTH_MEMBER);
                    assertThat(collection.valueEncoding())
                            .isEqualTo(AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR);
                    assertThat(collection.validatorId()).isEmpty();
                });
        assertThat(genesis.collections())
                .filteredOn(collection -> collection.id().equals("products"))
                .singleElement()
                .satisfies(collection -> assertThat(collection.validatorId())
                        .isEqualTo("product-v1"));
        assertThat(genesis.collections())
                .filteredOn(collection -> collection.id().equals("governed-catalog"))
                .singleElement()
                .satisfies(collection -> {
                    assertThat(collection.authorization())
                            .isEqualTo(AuthenticatedMapContract.AUTH_GOVERNED_ROLE);
                    assertThat(collection.authorizationPolicyId()).isEqualTo("issuer-write");
                });
        assertThat(genesis.collections())
                .filteredOn(collection -> collection.id().equals("released-products"))
                .singleElement()
                .satisfies(collection -> {
                    assertThat(collection.authorization())
                            .isEqualTo(AuthenticatedMapContract.AUTH_APPROVAL);
                    assertThat(collection.authorizationPolicyId())
                            .isEqualTo("product-release");
                });
        assertThat(genesis.governedGenesis()).isNotNull();
        assertThat(genesis.governedGenesis().organizations())
                .extracting(organization -> organization.organizationId())
                .containsExactly("acme-manufacturing", "auditor-guild-a", "auditor-guild-b");
        assertThat(genesis.governedGenesis().actors())
                .extracting(actor -> actor.actor().actorId())
                .containsExactly("auditor-a", "auditor-b", "issuer-a", "registry-admin-a");
        assertThat(genesis.governedGenesis().approvalPolicies())
                .singleElement()
                .satisfies(policy -> {
                    assertThat(policy.clauses()).singleElement().satisfies(clause -> {
                        assertThat(clause.minimumCount()).isEqualTo(2);
                        assertThat(clause.distinctBy())
                                .isEqualTo(com.bloxbean.cardano.yano.appchain.roles.contracts
                                        .ApprovalPolicyV1.DistinctBy.ORGANIZATION);
                    });
                });
        assertThat(genesis.validators())
                .extracting(AuthenticatedMapContract.ValidatorDescriptor::id)
                .containsExactly("gtin-v1", "product-v1");
        assertThat(genesis.validators())
                .filteredOn(validator -> validator.id().equals("gtin-v1"))
                .singleElement()
                .satisfies(validator -> {
                    assertThat(validator.kind())
                            .isEqualTo(AuthenticatedMapContract.VALIDATOR_KIND_PLUGIN);
                    assertThat(validator.providerId()).isEqualTo("gs1-gtin-v1");
                    assertThat(validator.definition()).isEqualTo(closure);
                });
        assertThat(genesis.validators())
                .filteredOn(validator -> validator.id().equals("product-v1"))
                .singleElement()
                .satisfies(validator -> {
                    assertThat(validator.kind())
                            .isEqualTo(AuthenticatedMapContract.VALIDATOR_KIND_SCHEMA);
                    AuthenticatedMapSchema.Schema schema = AuthenticatedMapSchema.decode(
                            validator.definition());
                    assertThat(schema.accepts(HexFormat.of().parseHex(
                            "a363736b7565736b752d316673746174757366616374697665"
                                    + "687175616e7469747905"))).isTrue();
                    assertThat(schema.accepts(HexFormat.of().parseHex(
                            "a363736b7565736b752d316673746174757367756e6b6e6f776e"
                                    + "687175616e7469747905"))).isFalse();
                });
        assertThat(settings).containsKeys(
                "state.commitment-profile",
                "state.format-fingerprint",
                "state.genesis-id");
        assertThat(settings.keySet()).containsExactly(
                "machines.authenticated-map.genesis-cbor-hex",
                "state.commitment-profile",
                "state.format-fingerprint",
                "state.genesis-id");
    }

    @Test
    void demoActorSeedsMatchTheShellDerivationContract() {
        // showcase.sh derives the identical value with:
        //   printf 'yano-showcase-demo-actor:issuer-a' | shasum -a 256
        assertThat(HexFormat.of().formatHex(
                ShowcaseAuthenticatedMapConfig.demoActorSeed("issuer-a")))
                .isEqualTo("014e1f28afa92a08710af43d06117868d84bf83185f3a4db14eb358e2e8a468a");
        // tools/showcase_signer.py derives the same public key for that seed,
        // proving the demo shell signer and the genesis key material agree.
        assertThat(HexFormat.of().formatHex(
                com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(
                        ShowcaseAuthenticatedMapConfig.demoActorSeed("issuer-a"))))
                .isEqualTo("67ddc1ac79b76d0e6abae1c133195fa48904948af3b202b761a4c727c4e169ff");
    }

    @Test
    void basicEnvelopeWrappingMatchesTheCompositeAdmissionContract() {
        // showcase.sh authmap_basic_body wraps the codec's legacy command into
        // the final v1 envelope with exactly these CLI invocations; the
        // composite machine admits only the final envelope.
        String legacy = HexFormat.of().formatHex(
                com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract
                        .encodeCommand(AuthenticatedMapContract.Command.single(
                                AuthenticatedMapContract.Mutation.put(
                                        "attachments",
                                        "demo-key".getBytes(StandardCharsets.UTF_8),
                                        "demo-value".getBytes(StandardCharsets.UTF_8)))));
        String action = com.bloxbean.cardano.yano.appchain.stdlib.contracts
                .AuthenticatedMapAuthorizationCli.execute(new String[] {
                        "action", "--command-hex", legacy, "--assignments", "0:owner::0"});
        String wrapped = com.bloxbean.cardano.yano.appchain.stdlib.contracts
                .AuthenticatedMapAuthorizationCli.execute(new String[] {
                        "command", "--action-hex", action, "--evidence-hex", ""});
        var decoded = com.bloxbean.cardano.yano.appchain.stdlib.contracts
                .AuthenticatedMapAuthorizationContract.decodeCommand(
                        HexFormat.of().parseHex(wrapped));
        assertThat(decoded.action().mutations()).hasSize(1);
        assertThat(decoded.evidence()).isEmpty();
    }

    @Test
    void readsOnlyExactArtifactClosureCatalogEvidence() {
        byte[] catalog = ("""
                {"schemaVersion":1,"bundles":[
                  {"manifest":{"id":"%s"},
                   "digest":"sha256:%s","digestMode":"ARTIFACT_CLOSURE"}
                ]}
                """).formatted(
                ShowcaseAuthenticatedMapConfig.VALIDATOR_BUNDLE_ID,
                "ab".repeat(32)).getBytes(StandardCharsets.UTF_8);

        assertThat(ShowcaseAuthenticatedMapConfig.catalogArtifactClosure(
                catalog, ShowcaseAuthenticatedMapConfig.VALIDATOR_BUNDLE_ID))
                .isEqualTo(HexFormat.of().parseHex("ab".repeat(32)));

        byte[] jarMode = new String(catalog, StandardCharsets.UTF_8)
                .replace("ARTIFACT_CLOSURE", "JAR")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ShowcaseAuthenticatedMapConfig.catalogArtifactClosure(
                jarMode, ShowcaseAuthenticatedMapConfig.VALIDATOR_BUNDLE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ARTIFACT_CLOSURE");
    }
}
