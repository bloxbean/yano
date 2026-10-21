package org.yanoproject.scalusbridge;

import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * An overlay-aware {@link LedgerStateProvider} over a {@link LedgerView} (ADR-056 §6, "Scalus under
 * overlays"), so {@code CertStateBridge} reads mempool-local, block-local and ticked state instead of the
 * canonical tip.
 *
 * <p>Lookups map as ADR-056 invariant 2 requires: Present → the value; Absent → empty or {@code false}
 * (ordinary ledger data, for example a first-time registration); Unavailable → a
 * {@link LedgerStateUnavailableException}, which the engine turns into
 * {@code ENGINE.LedgerStateUnavailable} (fail closed). Only the queries {@code CertStateBridge} and the
 * supplementary rules use are answered from the view; reward, snapshot and pot history queries keep the
 * interface defaults (empty), because transaction validation never reads them.</p>
 */
public final class LedgerViewStateProvider implements LedgerStateProvider {

    private final LedgerView view;

    public LedgerViewStateProvider(LedgerView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    private static <T> Optional<T> value(Lookup<T> lookup) {
        return lookup.orElseThrowUnavailable();
    }

    private Optional<AccountState> account(int credType, String hash) {
        return value(view.account(new CredentialKey(CredentialType.fromTag(credType), hash)));
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        return account(credType, credentialHash).map(AccountState::rewardBalance);
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        return account(credType, credentialHash).map(AccountState::deposit);
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        return account(credType, credentialHash).map(AccountState::delegatedPool).map(PoolId::hashHex);
    }

    @Override
    public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        return account(credType, credentialHash).map(AccountState::drepDelegation).map(target -> switch (target.kind()) {
            case CREDENTIAL -> new DRepDelegation(target.credential().type().tag(), target.credential().hashHex());
            case ALWAYS_ABSTAIN -> new DRepDelegation(2, null);
            case ALWAYS_NO_CONFIDENCE -> new DRepDelegation(3, null);
        });
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        return account(credType, credentialHash).isPresent();
    }

    /**
     * Scalus uses the total only for internal assertions ({@code State.deposited}); a view has no such
     * aggregate, so zero is returned.
     */
    @Override
    public BigInteger getTotalDeposited() {
        return BigInteger.ZERO;
    }

    // ------------------------------------------------------------------ pools

    private Optional<PoolState> pool(String poolHash) {
        return value(view.pool(new PoolId(poolHash)));
    }

    @Override
    public boolean isPoolRegistered(String poolHash) {
        return pool(poolHash).isPresent();
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        return pool(poolHash).map(PoolState::deposit);
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        return pool(poolHash).map(PoolState::retiringEpoch);
    }

    // ------------------------------------------------------------------ DReps

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        return drep(credType, credentialHash).isPresent();
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        return drep(credType, credentialHash).map(DRepState::deposit);
    }

    private Optional<DRepState> drep(int credType, String hash) {
        return value(view.drep(new CredentialKey(CredentialType.fromTag(credType), hash)));
    }

    // ------------------------------------------------------------------ committee

    private Optional<CommitteeMemberState> member(int credType, String coldHash) {
        return value(view.committeeMemberByCold(new CredentialKey(CredentialType.fromTag(credType), coldHash)));
    }

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        return member(credType, coldCredentialHash).map(CommitteeMemberState::isElected).orElse(false);
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        return member(credType, coldCredentialHash).map(CommitteeMemberState::hot).map(CredentialKey::hashHex);
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        return member(credType, coldCredentialHash).map(CommitteeMemberState::resigned).orElse(false);
    }

    @Override
    public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash) {
        CredentialKey hot = new CredentialKey(CredentialType.fromTag(hotCredType), hotCredentialHash);
        return Optional.of(!value(view.committeeMembersByHot(hot)).orElseThrow(
                () -> new LedgerStateUnavailableException("committee members by hot key answered Absent"))
                .isEmpty());
    }

    // ------------------------------------------------------------------ governance

    @Override
    public Optional<GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex) {
        return value(view.proposal(new GovActionId(txHash, govActionIndex)))
                .map(p -> new GovernanceActionInfo(p.type().name(), true, false));
    }

    /** @return the view this provider reads */
    public LedgerView view() {
        return view;
    }
}
