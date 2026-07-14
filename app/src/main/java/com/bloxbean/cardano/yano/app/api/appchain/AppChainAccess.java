package com.bloxbean.cardano.yano.app.api.appchain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Semantic authorization class for one host-owned app-layer operation.
 *
 * <p>HTTP verbs are intentionally insufficient: a bounded query uses POST but
 * remains a read, while effect claims and administrative POSTs are privileged.
 * Unannotated GET/HEAD/OPTIONS methods default to {@link Level#READ}; every
 * other unannotated method defaults to {@link Level#PRIVILEGED}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AppChainAccess {
    Level value();

    enum Level {
        READ,
        SUBMIT,
        PRIVILEGED,
        INTERNAL
    }
}
