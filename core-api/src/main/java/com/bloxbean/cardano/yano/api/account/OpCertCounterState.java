package com.bloxbean.cardano.yano.api.account;

/**
 * Current chain-dependent operational-certificate counter for one issuer.
 *
 * @param issuerKeyHash canonical cold-key hash, hex encoded
 * @param counter latest accepted operational certificate issue number
 * @param lastUpdatedSlot canonical slot that last updated this counter
 * @param lastUpdatedBlockNumber canonical block number that last updated this counter
 * @param lastUpdatedBlockHash canonical block hash that last updated this counter
 */
public record OpCertCounterState(String issuerKeyHash,
                                 long counter,
                                 long lastUpdatedSlot,
                                 long lastUpdatedBlockNumber,
                                 String lastUpdatedBlockHash) {
}
