package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Optional provider boundary. Implementations translate their canonical
 * lifecycle artifacts into neutral records without entering indexer core.
 */
public interface EutxoValidityIndexSourceProvider {
    String id();

    Optional<EutxoValidityIndexSource> open(
            Path lifecycleRoot,
            String chainId,
            String network);
}
