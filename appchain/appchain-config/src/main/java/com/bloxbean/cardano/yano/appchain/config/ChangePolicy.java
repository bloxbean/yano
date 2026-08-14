package com.bloxbean.cardano.yano.appchain.config;

/** Conservative operational policy for changing a resolved property. */
public enum ChangePolicy {
    LIVE_SAFE,
    RESTART_REQUIRED,
    ROLLING_DEPLOY_FIRST,
    GOVERNED_ACTIVATION,
    NEW_CHAIN_REQUIRED,
    SECRET_ROTATION,
    UNSUPPORTED
}
