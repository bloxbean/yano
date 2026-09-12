package org.yanoproject.runtime.devnet.spi;

import org.yanoproject.api.model.FundResult;
import org.yanoproject.api.model.FundingRequest;

import java.util.List;

/**
 * Devnet faucet port backed by runtime-owned UTXO mutation discipline.
 */
public interface DevnetFundingAccess {
    /**
     * Funds one address.
     *
     * @param address target address
     * @param lovelace amount in lovelace
     * @return faucet transaction result
     */
    FundResult fundAddress(String address, long lovelace);

    /**
     * Funds multiple addresses in order.
     *
     * <p>The runtime applies requests sequentially. The current contract is not
     * atomic: if a later request fails, earlier successful funding writes remain
     * committed.</p>
     *
     * @param requests funding requests
     * @return faucet transaction results
     */
    List<FundResult> fundAddresses(List<FundingRequest> requests);
}
