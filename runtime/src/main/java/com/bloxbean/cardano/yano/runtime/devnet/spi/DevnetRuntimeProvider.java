package com.bloxbean.cardano.yano.runtime.devnet.spi;

import java.util.Optional;

/**
 * Internal provider implemented by runtime assembly handles that can expose
 * devnet-safe runtime ports to optional Yano modules.
 */
public interface DevnetRuntimeProvider {
    /**
     * Returns devnet runtime ports when the assembled node role supports
     * devnet controls.
     *
     * @return devnet runtime ports, or empty for non-devnet recipes
     */
    Optional<DevnetRuntime> devnetRuntime();
}
