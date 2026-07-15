package com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo;

import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClient;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClientFactory;

import java.util.Objects;

/** Opens Kubo HTTP clients bound to one validated target configuration. */
public final class KuboIpfsPinClientFactory implements IpfsPinClientFactory {
    private final KuboClientConfig config;

    /**
     * Creates a bound Kubo factory.
     *
     * @param config validated internal construction values
     */
    public KuboIpfsPinClientFactory(KuboClientConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public IpfsPinClient open() {
        return new KuboIpfsPinClient(config);
    }
}
