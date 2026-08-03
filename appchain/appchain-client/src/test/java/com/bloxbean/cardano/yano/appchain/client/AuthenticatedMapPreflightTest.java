package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapPreflightTest {
    private static final byte[] KEY = new byte[]{1};
    private static final byte[] VALID_RECORD = Hex.decode("a16371747905");
    private static final byte[] INVALID_RECORD = Hex.decode("a1637174790b");

    @Test
    void validatesOpaqueEncodingAndSchemaWithoutClaimingAuthority() {
        AuthenticatedMapPreflight preflight = AuthenticatedMapPreflight.fromGenesis(genesis());

        AuthenticatedMapPreflight.Result opaque = preflight.validate(
                "raw", KEY, Hex.decode("ff00"));
        AuthenticatedMapPreflight.Result valid = preflight.validate(
                "records", KEY, VALID_RECORD);
        AuthenticatedMapPreflight.Result nonCanonical = preflight.validate(
                "records", KEY, Hex.decode("bf6371747905ff"));
        AuthenticatedMapPreflight.Result wrongShape = preflight.validate(
                "records", KEY, INVALID_RECORD);

        assertThat(opaque.status()).isEqualTo(AuthenticatedMapPreflight.Status.ACCEPTED);
        assertThat(valid.accepted()).isTrue();
        assertThat(valid.authoritative()).isFalse();
        assertThat(nonCanonical.code())
                .isEqualTo(AuthenticatedMapContract.ERROR_VALUE_ENCODING);
        assertThat(wrongShape.code()).isEqualTo(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);
        assertThat(preflight.validate("missing", KEY, VALID_RECORD).code())
                .isEqualTo(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION);
        assertThat(preflight.validate("records", new byte[65], VALID_RECORD).code())
                .isEqualTo(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS);
    }

    @Test
    void pluginPreflightIsUnavailableUnlessApplicationSuppliesTheExactAdapter() {
        AuthenticatedMapPreflight unavailable = AuthenticatedMapPreflight.fromGenesis(genesis());
        AuthenticatedMapPreflight.Result missing = unavailable.validate(
                "identifiers", KEY, new byte[]{7});

        AuthenticatedMapPreflight available = AuthenticatedMapPreflight.fromEncodedGenesis(
                AuthenticatedMapContract.encodeGenesis(genesis()), descriptor -> {
                    assertThat(descriptor.providerId()).isEqualTo("test-provider");
                    assertThat(descriptor.definition()).isEqualTo(new byte[32]);
                    return Optional.of((collection, key, value) -> value.length == 1
                            && value[0] == 7);
                });

        assertThat(missing.status()).isEqualTo(AuthenticatedMapPreflight.Status.UNAVAILABLE);
        assertThat(missing.code()).isEqualTo(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);
        assertThat(available.validate("identifiers", KEY, new byte[]{7}).accepted()).isTrue();
        assertThat(available.validate("identifiers", KEY, new byte[]{8}).status())
                .isEqualTo(AuthenticatedMapPreflight.Status.REJECTED);

        AuthenticatedMapPreflight throwing = AuthenticatedMapPreflight.fromGenesis(
                genesis(), descriptor -> Optional.of((collection, key, value) -> {
                    throw new IllegalStateException("adapter failed");
                }));
        assertThatThrownBy(() -> throwing.validate("identifiers", KEY, new byte[]{7}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter failed");
    }

    @Test
    void commandPreflightPreservesOrderAndFailsAtTheFirstNonAcceptedMutation() {
        AuthenticatedMapPreflight preflight = AuthenticatedMapPreflight.fromGenesis(genesis());
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.batch(List.of(
                AuthenticatedMapContract.Mutation.put("raw", KEY, new byte[]{1}),
                AuthenticatedMapContract.Mutation.put("records", new byte[]{2}, INVALID_RECORD)));

        assertThat(preflight.validate(command)).extracting(AuthenticatedMapPreflight.Result::status)
                .containsExactly(AuthenticatedMapPreflight.Status.ACCEPTED,
                        AuthenticatedMapPreflight.Status.REJECTED);
        assertThatThrownBy(() -> preflight.requireAccepted(command))
                .isInstanceOfSatisfying(AuthenticatedMapPreflight.PreflightException.class,
                        failure -> {
                            assertThat(failure.mutationIndex()).isEqualTo(1);
                            assertThat(failure.result().code())
                                    .isEqualTo(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);
                        });
    }

    private static AuthenticatedMapContract.Genesis genesis() {
        AuthenticatedMapSchema.Schema schema = AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.MapNode(List.of(
                        new AuthenticatedMapSchema.MapField(
                                "qty", true,
                                new AuthenticatedMapSchema.IntegerNode(
                                        AuthenticatedMapSchema.INTEGER_UINT,
                                        BigInteger.ZERO,
                                        BigInteger.TEN)))));
        AuthenticatedMapContract.ValidatorDescriptor schemaValidator =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "record-v1", schema.definition());
        AuthenticatedMapContract.ValidatorDescriptor pluginValidator =
                AuthenticatedMapContract.ValidatorDescriptor.plugin(
                        "identifier-v1", "test-provider", new byte[32],
                        new byte[]{(byte) 0xa0});
        return new AuthenticatedMapContract.Genesis(
                "client-preflight",
                AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1,
                new byte[32], new byte[32], new byte[32], new byte[32],
                16, 65_536,
                List.of(
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "raw", AuthenticatedMapContract.AUTH_OPEN, false,
                                64, 1024),
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "records", AuthenticatedMapContract.AUTH_OPEN, false,
                                64, 1024,
                                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                                schemaValidator.id()),
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "identifiers", AuthenticatedMapContract.AUTH_OPEN, false,
                                64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE,
                                pluginValidator.id())),
                List.of(schemaValidator, pluginValidator),
                List.of());
    }
}
