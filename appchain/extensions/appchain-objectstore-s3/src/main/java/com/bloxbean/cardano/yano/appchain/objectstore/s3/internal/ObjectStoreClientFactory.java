package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;

/** Opens one provider client for one validated target policy. */
@FunctionalInterface
public interface ObjectStoreClientFactory {
    /**
     * Opens a fresh client owned by the caller.
     * @param target validated immutable target configuration
     * @return a fresh provider client
     */
    ObjectStoreClient open(ObjectStoreS3EffectConfig.Target target);
}
