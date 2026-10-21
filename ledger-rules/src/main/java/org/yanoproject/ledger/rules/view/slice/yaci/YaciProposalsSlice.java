package org.yanoproject.ledger.rules.view.slice.yaci;

import org.yanoproject.ledger.rules.view.slice.ProposalsSlice;
import org.yanoproject.api.account.LedgerStateProvider;

/**
 * Yaci adapter for {@link ProposalsSlice} backed by {@link LedgerStateProvider}.
 */
public class YaciProposalsSlice implements ProposalsSlice {

    private final LedgerStateProvider provider;

    public YaciProposalsSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean exists(String txHash, int index) {
        return provider.getGovernanceAction(txHash, index).isPresent();
    }

    @Override
    public boolean isActive(String txHash, int index) {
        return provider.getGovernanceAction(txHash, index)
                .map(LedgerStateProvider.GovernanceActionInfo::active)
                .orElse(false);
    }

    @Override
    public boolean isActive(String txHash, int index, long currentEpoch) {
        return provider.getGovernanceAction(txHash, index, currentEpoch)
                .map(LedgerStateProvider.GovernanceActionInfo::active)
                .orElse(false);
    }

    @Override
    public String getActionType(String txHash, int index) {
        return provider.getGovernanceAction(txHash, index)
                .map(LedgerStateProvider.GovernanceActionInfo::actionType)
                .orElse(null);
    }
}
