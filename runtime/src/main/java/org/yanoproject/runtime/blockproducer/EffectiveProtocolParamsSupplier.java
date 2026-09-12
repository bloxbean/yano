package org.yanoproject.runtime.blockproducer;

import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.api.util.EpochSlotCalc;

/**
 * @deprecated use {@link org.yanoproject.runtime.tx.EffectiveProtocolParamsSupplier}.
 */
@Deprecated(forRemoval = false)
public class EffectiveProtocolParamsSupplier
        extends org.yanoproject.runtime.tx.EffectiveProtocolParamsSupplier {

    public EffectiveProtocolParamsSupplier(LedgerStateProvider ledgerStateProvider,
                                           EpochSlotCalc epochSlotCalc) {
        super(ledgerStateProvider, epochSlotCalc);
    }
}
