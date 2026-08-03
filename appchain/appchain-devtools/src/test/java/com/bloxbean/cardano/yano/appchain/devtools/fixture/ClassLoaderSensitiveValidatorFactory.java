package com.bloxbean.cardano.yano.appchain.devtools.fixture;

import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidator;
import com.bloxbean.cardano.yano.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorInitContext;
import com.bloxbean.cardano.yano.api.appchain.authmap.ValidatorVerdict;

/** Deliberately invalid fixture whose verdict depends on an ambient class-loader resource. */
public final class ClassLoaderSensitiveValidatorFactory
        implements AuthenticatedMapValueValidatorFactory {
    public static final String ID = "classloader-sensitive-v1";

    @Override public String id() { return ID; }
    @Override public String contractVersion() { return "authenticated-map-validator-v1"; }

    @Override
    public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
        boolean ambient = getClass().getClassLoader()
                .getResource("ambient-validator-flag") != null;
        return (collection, key, value) -> ambient
                ? ValidatorVerdict.ACCEPT : ValidatorVerdict.REJECT;
    }
}
