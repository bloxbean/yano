package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;

import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless validator fixture used only by packaged plugin conformance checks. */
public final class ConformanceAuthenticatedMapValidatorFactory
        implements AuthenticatedMapValueValidatorFactory {
    public static final String ID = "conformance-map-validator";
    public static final String CONTRACT = "authenticated-map-validator-v1";

    @Override public String id() { return ID; }
    @Override public String contractVersion() { return CONTRACT; }

    @Override
    public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
        AtomicBoolean firstCallback = new AtomicBoolean(true);
        AuthenticatedMapValueValidator validator = (collection, key, value) -> {
            ConformanceTcclProbe.productCallback(firstCallback,
                    "authenticated-map validator invocation");
            return ValidatorVerdict.ACCEPT;
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return validator;
    }
}
