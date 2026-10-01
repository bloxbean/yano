package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.scalusbridge.LedgerViewStateProvider;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The node's {@link LedgerStateProvider} contract answered from a case's view, for the legacy validators: the
 * Scalus engine's {@link LedgerViewStateProvider}, plus proposal activity at the validation epoch (a proposal is
 * active while {@code expiresAfterEpoch >= epoch}, as the governance store answers it), which the engine adapter
 * does not need.
 */
final class ViewStateProvider implements LedgerStateProvider {

    private final Supplier<LedgerView> view;
    private final LongSupplier epoch;

    ViewStateProvider(LedgerView view, long epoch) {
        this(() -> view, () -> epoch);
    }

    /** A provider over whatever view and epoch the suppliers return at each call (for a cached validator). */
    ViewStateProvider(Supplier<LedgerView> view, LongSupplier epoch) {
        this.view = view;
        this.epoch = epoch;
    }

    private LedgerViewStateProvider delegate() {
        return new LedgerViewStateProvider(view.get());
    }

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        return delegate().getRewardBalance(credType, credentialHash);
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        return delegate().getStakeDeposit(credType, credentialHash);
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        return delegate().getDelegatedPool(credType, credentialHash);
    }

    @Override
    public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        return delegate().getDRepDelegation(credType, credentialHash);
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        return delegate().isStakeCredentialRegistered(credType, credentialHash);
    }

    @Override
    public BigInteger getTotalDeposited() {
        return delegate().getTotalDeposited();
    }

    @Override
    public boolean isPoolRegistered(String poolHash) {
        return delegate().isPoolRegistered(poolHash);
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        return delegate().getPoolDeposit(poolHash);
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        return delegate().getPoolRetirementEpoch(poolHash);
    }

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        return delegate().isDRepRegistered(credType, credentialHash);
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        return delegate().getDRepDeposit(credType, credentialHash);
    }

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        return delegate().isCommitteeMember(credType, coldCredentialHash);
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        return delegate().getCommitteeHotCredential(credType, coldCredentialHash);
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        return delegate().hasCommitteeMemberResigned(credType, coldCredentialHash);
    }

    @Override
    public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash) {
        return delegate().isCommitteeHotCredentialAuthorized(hotCredType, hotCredentialHash);
    }

    @Override
    public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash,
                                                                long currentEpoch) {
        return delegate().isCommitteeHotCredentialAuthorized(hotCredType, hotCredentialHash);
    }

    @Override
    public Optional<GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex) {
        return getGovernanceAction(txHash, govActionIndex, epoch.getAsLong());
    }

    @Override
    public Optional<GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex, long currentEpoch) {
        Lookup<ProposalState> proposal = view.get().proposal(new GovActionId(txHash, govActionIndex));
        return proposal.orElseThrowUnavailable().map(p -> new GovernanceActionInfo(p.type().name(),
                currentEpoch < 0 || p.expiresAfterEpoch() >= currentEpoch, false));
    }
}
