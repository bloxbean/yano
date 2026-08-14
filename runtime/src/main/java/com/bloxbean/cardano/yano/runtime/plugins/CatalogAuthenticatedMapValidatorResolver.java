package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Catalog-owned resolver for trusted, genesis-pinned authenticated-map validators. */
public final class CatalogAuthenticatedMapValidatorResolver
        implements AuthenticatedMapValidatorResolver {
    private static final String SHA256_PREFIX = "sha256:";
    private final PluginProviderRegistry providers;

    public CatalogAuthenticatedMapValidatorResolver(PluginProviderRegistry providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public AuthenticatedMapValueValidator resolve(
            byte[] artifactClosureDigest,
            ValidatorInitContext context
    ) {
        byte[] pinnedDigest = requireDigest(artifactClosureDigest);
        Objects.requireNonNull(context, "context");

        PluginProviderRegistry.ContributionProvenance provenance = providers
                .contributionProvenance(
                        AuthenticatedMapValueValidatorFactory.class, context.providerId())
                .orElseThrow(() -> activationFailure(context,
                        "has no manifested artifact provenance"));
        if (!provenance.explicitlyAllowListed()) {
            throw activationFailure(context,
                    "is not owned by an explicitly allow-listed plugin bundle");
        }
        if (provenance.digestMode() != PluginDigestMode.ARTIFACT_CLOSURE) {
            throw activationFailure(context,
                    "is not backed by ARTIFACT_CLOSURE evidence");
        }
        byte[] resolvedDigest = parseCatalogDigest(provenance.digest(), context);
        if (!MessageDigest.isEqual(pinnedDigest, resolvedDigest)) {
            throw activationFailure(context,
                    "artifact-closure digest differs from genesis");
        }

        AuthenticatedMapValueValidatorFactory factory = providers.find(
                        AuthenticatedMapValueValidatorFactory.class, context.providerId())
                .orElseThrow(() -> activationFailure(context,
                        "has no selected validator factory"));
        if (!context.providerId().equals(factory.id())) {
            throw activationFailure(context, "factory identity differs from genesis");
        }
        if (!context.contractVersion().equals(factory.contractVersion())) {
            throw activationFailure(context, "SPI contract version differs from genesis");
        }
        return Objects.requireNonNull(factory.create(context),
                "Authenticated-map validator factory returned null");
    }

    private static byte[] requireDigest(byte[] digest) {
        byte[] copy = Objects.requireNonNull(digest, "artifactClosureDigest").clone();
        if (copy.length != 32) {
            throw new IllegalArgumentException(
                    "artifactClosureDigest must contain exactly 32 bytes");
        }
        return copy;
    }

    private static byte[] parseCatalogDigest(
            String encoded,
            ValidatorInitContext context
    ) {
        if (encoded == null || !encoded.startsWith(SHA256_PREFIX)
                || encoded.length() != SHA256_PREFIX.length() + 64) {
            throw activationFailure(context,
                    "has malformed artifact-closure evidence");
        }
        try {
            return HexFormat.of().parseHex(encoded.substring(SHA256_PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            throw activationFailure(context,
                    "has malformed artifact-closure evidence");
        }
    }

    private static IllegalStateException activationFailure(
            ValidatorInitContext context,
            String reason
    ) {
        return new IllegalStateException("Authenticated-map validator '"
                + context.descriptorId() + "' provider '" + context.providerId()
                + "' " + reason);
    }
}
