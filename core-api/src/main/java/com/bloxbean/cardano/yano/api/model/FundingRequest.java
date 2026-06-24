package com.bloxbean.cardano.yano.api.model;

/**
 * Batch faucet request for devnet and testkit funding helpers.
 *
 * @param address target Cardano address
 * @param lovelace amount to fund in lovelace
 */
public record FundingRequest(String address, long lovelace) {
}
