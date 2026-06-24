package com.bloxbean.cardano.yano.runtime.chain;

import java.util.List;

/**
 * Metadata required to remove Byron genesis UTXOs at the Allegra boundary.
 */
public interface ByronGenesisUtxoMetadataStore {
    void setByronGenesisUtxoKeys(List<byte[]> outpointKeys);

    List<byte[]> getByronGenesisUtxoKeys();

    boolean isAllegraBootstrapDone();

    byte[] getAllegraBootstrapDoneKey();
}
