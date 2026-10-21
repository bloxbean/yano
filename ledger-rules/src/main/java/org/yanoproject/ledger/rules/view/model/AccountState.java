package org.yanoproject.ledger.rules.view.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A registered stake account (Haskell {@code ConwayAccountState}).
 *
 * @param credential     the stake credential
 * @param deposit        the deposit recorded at registration (the refund on deregistration)
 * @param rewardBalance  the withdrawable reward balance
 * @param delegatedPool  the stake pool delegation, or {@code null}
 * @param drepDelegation the vote delegation, or {@code null}
 */
public record AccountState(CredentialKey credential, BigInteger deposit, BigInteger rewardBalance,
                           PoolId delegatedPool, DRepTarget drepDelegation) {

    public AccountState {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(deposit, "deposit");
        Objects.requireNonNull(rewardBalance, "rewardBalance");
    }

    /** A freshly registered account: zero balance, no delegations. */
    public static AccountState registered(CredentialKey credential, BigInteger deposit) {
        return new AccountState(credential, deposit, BigInteger.ZERO, null, null);
    }

    public AccountState withRewardBalance(BigInteger balance) {
        return new AccountState(credential, deposit, balance, delegatedPool, drepDelegation);
    }

    public AccountState withDelegatedPool(PoolId pool) {
        return new AccountState(credential, deposit, rewardBalance, pool, drepDelegation);
    }

    public AccountState withDRepDelegation(DRepTarget drep) {
        return new AccountState(credential, deposit, rewardBalance, delegatedPool, drep);
    }
}
