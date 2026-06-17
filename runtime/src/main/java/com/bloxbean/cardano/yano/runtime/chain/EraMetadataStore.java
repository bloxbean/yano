package com.bloxbean.cardano.yano.runtime.chain;

import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Persists era-boundary metadata used by chronology, nonce, and ledger-state services.
 */
public interface EraMetadataStore {
    void setEraStartSlot(int eraValue, long slot);

    OptionalLong getEraStartSlot(int eraValue);

    OptionalLong getFirstNonByronEraStartSlot();

    void setShelleyStartUtxoTotal(BigInteger total);

    Optional<BigInteger> getShelleyStartUtxoTotal();
}
