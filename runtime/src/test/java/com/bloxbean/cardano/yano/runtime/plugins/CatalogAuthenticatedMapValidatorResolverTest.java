package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogAuthenticatedMapValidatorResolverTest {
    private static final String PROVIDER = "example-validator-v1";
    private static final String CONTRACT = "authenticated-map-validator-v1";
    private static final byte[] DIGEST = HexFormat.of().parseHex("42".repeat(32));
    private static final String ENCODED_DIGEST = "sha256:" + HexFormat.of().formatHex(DIGEST);

    @Test
    void resolvesOnlyAnExplicitlyAllowedClosurePinnedFactory() {
        ValidatorInitContext context = context();
        AtomicBoolean created = new AtomicBoolean();
        AuthenticatedMapValueValidatorFactory factory = factory(
                PROVIDER, CONTRACT, init -> {
                    created.set(true);
                    assertThat(init).isEqualTo(context);
                    return (collection, key, value) -> ValidatorVerdict.ACCEPT;
                });
        PluginProviderRegistry registry = registry(factory,
                provenance(ENCODED_DIGEST, PluginDigestMode.ARTIFACT_CLOSURE, true));

        AuthenticatedMapValueValidator validator =
                new CatalogAuthenticatedMapValidatorResolver(registry)
                        .resolve(DIGEST, context);

        assertThat(created).isTrue();
        assertThat(validator.validate("products", new byte[0], new byte[0]))
                .isEqualTo(ValidatorVerdict.ACCEPT);
    }

    @Test
    void refusesUnlistedWeakOrMismatchedEvidenceBeforeExecutingTheFactory() {
        AtomicBoolean created = new AtomicBoolean();
        AuthenticatedMapValueValidatorFactory factory = factory(
                PROVIDER, CONTRACT, context -> {
                    created.set(true);
                    return (collection, key, value) -> ValidatorVerdict.ACCEPT;
                });

        assertRejected(factory, provenance(
                ENCODED_DIGEST, PluginDigestMode.ARTIFACT_CLOSURE, false),
                DIGEST, "explicitly allow-listed");
        assertRejected(factory, provenance(
                ENCODED_DIGEST, PluginDigestMode.JAR, true),
                DIGEST, "ARTIFACT_CLOSURE");
        assertRejected(factory, provenance(
                "sha256:" + "24".repeat(32), PluginDigestMode.ARTIFACT_CLOSURE, true),
                DIGEST, "differs from genesis");
        assertThat(created).isFalse();
    }

    @Test
    void refusesFactoryIdentityContractAndMissingProvenance() {
        assertThatThrownBy(() -> new CatalogAuthenticatedMapValidatorResolver(
                registry(factory("other", CONTRACT, context -> accepting()),
                        provenance(ENCODED_DIGEST,
                                PluginDigestMode.ARTIFACT_CLOSURE, true)))
                .resolve(DIGEST, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity differs");
        assertThatThrownBy(() -> new CatalogAuthenticatedMapValidatorResolver(
                registry(factory(PROVIDER, "wrong-v1", context -> accepting()),
                        provenance(ENCODED_DIGEST,
                                PluginDigestMode.ARTIFACT_CLOSURE, true)))
                .resolve(DIGEST, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract version differs");
        assertThatThrownBy(() -> new CatalogAuthenticatedMapValidatorResolver(
                registry(factory(PROVIDER, CONTRACT, context -> accepting()), null))
                .resolve(DIGEST, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no manifested artifact provenance");
    }

    private static void assertRejected(
            AuthenticatedMapValueValidatorFactory factory,
            PluginProviderRegistry.ContributionProvenance provenance,
            byte[] digest,
            String message
    ) {
        assertThatThrownBy(() -> new CatalogAuthenticatedMapValidatorResolver(
                registry(factory, provenance)).resolve(digest, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private static PluginProviderRegistry registry(
            AuthenticatedMapValueValidatorFactory factory,
            PluginProviderRegistry.ContributionProvenance provenance
    ) {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                return providerType == AuthenticatedMapValueValidatorFactory.class
                        && PROVIDER.equals(selector)
                        ? Optional.of(providerType.cast(factory)) : Optional.empty();
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return List.of(PROVIDER);
            }

            @Override
            public <P> Optional<ContributionProvenance> contributionProvenance(
                    Class<P> providerType,
                    String selector
            ) {
                return Optional.ofNullable(provenance);
            }
        };
    }

    private static PluginProviderRegistry.ContributionProvenance provenance(
            String digest,
            PluginDigestMode mode,
            boolean allowed
    ) {
        return new PluginProviderRegistry.ContributionProvenance(
                "com.example.validator", digest, mode, allowed);
    }

    private static AuthenticatedMapValueValidatorFactory factory(
            String id,
            String contract,
            java.util.function.Function<ValidatorInitContext,
                    AuthenticatedMapValueValidator> create
    ) {
        return new AuthenticatedMapValueValidatorFactory() {
            @Override public String id() { return id; }
            @Override public String contractVersion() { return contract; }
            @Override public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
                return create.apply(context);
            }
        };
    }

    private static AuthenticatedMapValueValidator accepting() {
        return (collection, key, value) -> ValidatorVerdict.ACCEPT;
    }

    private static ValidatorInitContext context() {
        return new ValidatorInitContext("product-validator", PROVIDER, CONTRACT,
                new byte[]{(byte) 0xa0}, List.of("products"));
    }
}
