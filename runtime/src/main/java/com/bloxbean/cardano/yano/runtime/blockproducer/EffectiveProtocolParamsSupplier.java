package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;

/**
 * @deprecated use {@link com.bloxbean.cardano.yano.runtime.tx.EffectiveProtocolParamsSupplier}.
 */
@Deprecated(forRemoval = false)
public class EffectiveProtocolParamsSupplier
        extends com.bloxbean.cardano.yano.runtime.tx.EffectiveProtocolParamsSupplier {

    public EffectiveProtocolParamsSupplier(LedgerStateProvider ledgerStateProvider,
                                           EpochSlotCalc epochSlotCalc) {
        super(ledgerStateProvider, epochSlotCalc);
    }
}
