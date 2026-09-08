package com.bloxbean.cardano.yano.api.utxo.index;

/** Manifest-required provider, instantiated only by explicit startup registration. */
public interface UtxoIndexContributorProvider {
    String id();
    default int schemaVersion() { return 1; }
    default IndexRequirements requirements() { return IndexRequirements.NONE; }
    UtxoIndexContributor create(UtxoIndexContext context);
}
