package org.yanoproject.ledger.rules.view.slice.yaci;

import org.yanoproject.ledger.rules.view.slice.PoolsSlice;
import org.yanoproject.api.account.LedgerStateProvider;

/**
 * Yaci adapter for {@link PoolsSlice} backed by {@link LedgerStateProvider}.
 */
public class YaciPoolsSlice implements PoolsSlice {

    private final LedgerStateProvider provider;

    public YaciPoolsSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean isRegistered(String poolId) {
        return provider.isPoolRegistered(poolId);
    }

    @Override
    public long getRetirementEpoch(String poolId) {
        return provider.getPoolRetirementEpoch(poolId).orElse(-1L);
    }
}
