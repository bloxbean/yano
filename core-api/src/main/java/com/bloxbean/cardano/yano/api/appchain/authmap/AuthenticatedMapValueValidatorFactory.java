package com.bloxbean.cardano.yano.api.appchain.authmap;

/** ServiceLoader contribution that creates one chain-scoped validator instance. */
public interface AuthenticatedMapValueValidatorFactory {

    /** Exact selector pinned by the authenticated-map genesis descriptor. */
    String id();

    /** Exact SPI contract version supported by this factory. */
    String contractVersion();

    /** Creates one fresh immutable or thread-safe validator. */
    AuthenticatedMapValueValidator create(ValidatorInitContext context);
}
