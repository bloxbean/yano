package com.bloxbean.cardano.yano.api.utxo;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;

/** Close-scoped, ordered and coordinate-bound view of live stake balances. */
public interface StakeBalanceView extends AutoCloseable {
    CanonicalBlockReference coordinate();

    boolean advance();

    StakeCredentialBalance current();

    @Override
    void close();
}
