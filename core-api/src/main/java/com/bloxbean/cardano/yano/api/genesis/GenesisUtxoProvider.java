package com.bloxbean.cardano.yano.api.genesis;

import java.util.List;

/**
 * The single authoritative source of a network's complete genesis distribution.
 *
 * <p>Deliberately returns <strong>both</strong> eras together. An earlier shape had the
 * {@code GenesisBlockEvent} payload carry Shelley initial funds while Byron balances came from
 * elsewhere; those two sources can drift, and a projection that captured a stale half would look
 * complete while being wrong. There is one provider, and it answers for everything.
 *
 * <p>Implementations must be pure with respect to the archive: no store reads, and in particular
 * never a query against the live UTXO column family, which is mutable and reflects spends rather
 * than the original distribution.
 *
 * <p>An empty distribution is a valid answer - devnets and direct-start configurations
 * legitimately distribute nothing - and must still be treated as a completed bootstrap rather
 * than a skipped one.
 */
@FunctionalInterface
public interface GenesisUtxoProvider {

    /**
     * The complete normalised distribution, attributed to the given coordinate.
     *
     * @param blockNumber coordinate to attribute the outputs to, from the genesis block event
     * @param slot        coordinate to attribute the outputs to
     * @param blockHash   hex coordinate to attribute the outputs to
     */
    List<GenesisUtxo> genesisUtxos(long blockNumber, long slot, String blockHash);

    /** A node with no genesis distribution, or one that cannot supply it. */
    GenesisUtxoProvider EMPTY = (blockNumber, slot, blockHash) -> List.of();
}
