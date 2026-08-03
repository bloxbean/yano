package com.bloxbean.cardano.yano.api.appchain.authmap;

/**
 * Host-owned bridge that resolves a genesis-pinned validator through the
 * selected plugin catalog. State machines cannot use this bridge to enumerate
 * plugins, read node configuration, or obtain any other host service.
 */
@FunctionalInterface
public interface AuthenticatedMapValidatorResolver {

    /**
     * Resolves and constructs a validator only when provider identity, SPI
     * version, explicit allow-list selection, and artifact-closure digest all
     * match the supplied genesis binding.
     */
    AuthenticatedMapValueValidator resolve(
            byte[] artifactClosureDigest,
            ValidatorInitContext context
    );
}
