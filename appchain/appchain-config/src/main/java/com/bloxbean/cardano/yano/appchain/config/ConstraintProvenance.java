package com.bloxbean.cardano.yano.appchain.config;

/** Evidence supporting a constraint exposed by the property registry. */
public enum ConstraintProvenance {
    PUBLIC_RUNTIME_DEFINITION(true),
    RUNTIME_PARSER_TEST(true),
    DOCUMENTED_UNVERIFIED(false),
    NOT_APPLICABLE(false);

    private final boolean enforceable;

    ConstraintProvenance(boolean enforceable) {
        this.enforceable = enforceable;
    }

    /** Whether M0a may reject input using this constraint. */
    public boolean enforceable() {
        return enforceable;
    }
}
