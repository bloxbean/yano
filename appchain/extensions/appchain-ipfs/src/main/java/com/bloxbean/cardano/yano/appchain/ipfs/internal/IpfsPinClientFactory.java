package com.bloxbean.cardano.yano.appchain.ipfs.internal;

/**
 * Opens a fresh provider client from a factory already bound to one validated
 * target. Keeping the target binding outside this interface prevents the
 * provider-neutral boundary from depending on public configuration types.
 */
@FunctionalInterface
public interface IpfsPinClientFactory {
    /**
     * Opens a client owned by the caller.
     *
     * @return a fresh provider client
     */
    IpfsPinClient open();
}
