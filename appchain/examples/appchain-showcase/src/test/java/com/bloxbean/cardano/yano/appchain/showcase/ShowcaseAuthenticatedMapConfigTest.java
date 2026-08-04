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
                .containsExactly("attachments", "canonical-events", "gtins", "products");
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
