package com.bloxbean.cardano.yano.runtime.devnet.spi;

import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Devnet genesis metadata port.
 */
public interface DevnetGenesisAccess {
    /**
     * Returns the Shelley genesis file when the assembly is file-backed.
     *
     * @return Shelley genesis path
     */
    Optional<Path> shelleyGenesisFile();

    /**
     * Returns the Byron genesis file when configured.
     *
     * @return Byron genesis path
     */
    Optional<Path> byronGenesisFile();

    /**
     * Returns the Alonzo genesis file when configured.
     *
     * @return Alonzo genesis path
     */
    Optional<Path> alonzoGenesisFile();

    /**
     * Returns the Conway genesis file when configured.
     *
     * @return Conway genesis path
     */
    Optional<Path> conwayGenesisFile();

    /**
     * Returns the protocol-parameters file when configured.
     *
     * @return protocol-parameters path
     */
    Optional<Path> protocolParametersFile();

    /**
     * Returns the current parsed runtime genesis configuration.
     *
     * @return parsed genesis configuration
     */
    GenesisConfig currentGenesisConfig();
}
