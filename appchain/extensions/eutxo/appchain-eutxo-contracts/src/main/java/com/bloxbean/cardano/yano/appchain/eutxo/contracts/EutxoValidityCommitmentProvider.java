package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.Map;

/**
 * Family-private ServiceLoader boundary for optional validity modules.
 *
 * <p>This is intentionally not a Yano core SPI. A selected EUTxO plugin and
 * its optional validity artifact share the runtime's executable plugin
 * classloader.</p>
 */
public interface EutxoValidityCommitmentProvider {

    String id();

    EutxoValidityCommitmentEngine create(
            String chainId,
            EutxoProfile profile,
            Map<String, String> settings);
}
