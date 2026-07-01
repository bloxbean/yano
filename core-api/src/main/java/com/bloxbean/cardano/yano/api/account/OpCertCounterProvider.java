package com.bloxbean.cardano.yano.api.account;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Read-only operational-certificate counter evidence keyed by block issuer
 * cold-key hash.
 */
public interface OpCertCounterProvider {

    default Optional<OpCertCounterState> getOpCertCounterState(String issuerKeyHash) {
        return Optional.empty();
    }

    default OptionalLong getOpCertCounter(String issuerKeyHash) {
        return getOpCertCounterState(issuerKeyHash)
                .map(state -> OptionalLong.of(state.counter()))
                .orElseGet(OptionalLong::empty);
    }
}
