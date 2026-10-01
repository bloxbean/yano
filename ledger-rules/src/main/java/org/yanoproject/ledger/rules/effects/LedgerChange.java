package org.yanoproject.ledger.rules.effects;

import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;

import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.Voter;

import java.math.BigInteger;
import java.util.Objects;

/**
 * One certificate-, withdrawal- or governance-state change of a transaction, in Haskell order.
 *
 * <p>Changes are deltas: an {@link org.yanoproject.ledger.rules.view.OverlayLedgerView} applies them
 * in order to the state below it. Amounts such as refunds are recorded for consumers (value
 * conservation, effects cross-checks); the overlay takes the resulting state from the ledger rules,
 * not from those amounts.</p>
 */
public sealed interface LedgerChange {

    /** Stake credential registered with the protocol key deposit (Conway/Rules/Deleg.hs:233-239). */
    record AccountRegistered(CredentialKey credential, BigInteger deposit) implements LedgerChange {
        public AccountRegistered {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(deposit, "deposit");
        }
    }

    /** Stake credential deregistered; {@code refund} is the recorded deposit (Deleg.hs:240-247). */
    record AccountUnregistered(CredentialKey credential, BigInteger refund) implements LedgerChange {
        public AccountUnregistered {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(refund, "refund");
        }
    }

    record StakeDelegated(CredentialKey credential, PoolId pool) implements LedgerChange {
        public StakeDelegated {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(pool, "pool");
        }
    }

    record VoteDelegated(CredentialKey credential, DRepTarget drep) implements LedgerChange {
        public VoteDelegated {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(drep, "drep");
        }
    }

    /** A withdrawal drains the account balance to zero (cardano-ledger-core State/Account.hs:277). */
    record RewardWithdrawn(CredentialKey credential, BigInteger amount) implements LedgerChange {
        public RewardWithdrawn {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** First registration of a pool, charged the pool deposit (Shelley/Rules/Pool.hs:263-275). */
    record PoolRegistered(PoolId pool, PoolRegistration params, BigInteger deposit) implements LedgerChange {
        public PoolRegistered {
            Objects.requireNonNull(pool, "pool");
            Objects.requireNonNull(params, "params");
            Objects.requireNonNull(deposit, "deposit");
        }
    }

    /**
     * Re-registration of a registered pool: new future parameters, pending retirement cancelled,
     * deposit unchanged (Shelley/Rules/Pool.hs:277-305).
     */
    record PoolReregistered(PoolId pool, PoolRegistration params) implements LedgerChange {
        public PoolReregistered {
            Objects.requireNonNull(pool, "pool");
            Objects.requireNonNull(params, "params");
        }
    }

    record PoolRetirementScheduled(PoolId pool, long epoch) implements LedgerChange {
        public PoolRetirementScheduled {
            Objects.requireNonNull(pool, "pool");
        }
    }

    /** DRep registered with the protocol DRep deposit and its computed expiry (GovCert.hs:210-232). */
    record DRepRegistered(CredentialKey credential, BigInteger deposit, long expiryEpoch) implements LedgerChange {
        public DRepRegistered {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(deposit, "deposit");
        }
    }

    /**
     * {@code UpdateDRepCert}: new anchor and refreshed expiry (GovCert.hs:256-272). Unlike
     * registration, the expiry always subtracts the dormant epochs, even in PV9.
     */
    record DRepUpdated(CredentialKey credential, long expiryEpoch) implements LedgerChange {
        public DRepUpdated {
            Objects.requireNonNull(credential, "credential");
        }
    }

    /**
     * DRep deregistered; {@code refund} is the recorded deposit. Vote delegations to it are
     * cleared (GovCert.hs:234-255).
     */
    record DRepUnregistered(CredentialKey credential, BigInteger refund) implements LedgerChange {
        public DRepUnregistered {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(refund, "refund");
        }
    }

    /** A DRep that votes in the transaction gets a refreshed expiry (Certs.hs:272-292). */
    record DRepActivityUpdated(CredentialKey credential, long expiryEpoch) implements LedgerChange {
        public DRepActivityUpdated {
            Objects.requireNonNull(credential, "credential");
        }
    }

    /**
     * A transaction with proposals ends a dormant period: every DRep's expiry is bumped by
     * {@code dormantEpochs} unless the bumped expiry is still before {@code currentEpoch}, and the
     * dormant counter resets to zero (Certs.hs:257-266, 308-328).
     */
    record DormantDRepExpiriesBumped(long dormantEpochs, long currentEpoch) implements LedgerChange {
        public DormantDRepExpiriesBumped {
            if (dormantEpochs <= 0) {
                throw new IllegalArgumentException("dormantEpochs must be > 0: " + dormantEpochs);
            }
        }

        /** Haskell {@code updateDormantDRepExpiry.updateExpiry}. */
        public long bump(long expiry) {
            long actual = expiry + dormantEpochs;
            return actual < currentEpoch ? expiry : actual;
        }
    }

    record CommitteeHotAuthorized(CredentialKey cold, CredentialKey hot) implements LedgerChange {
        public CommitteeHotAuthorized {
            Objects.requireNonNull(cold, "cold");
            Objects.requireNonNull(hot, "hot");
        }
    }

    record CommitteeResigned(CredentialKey cold) implements LedgerChange {
        public CommitteeResigned {
            Objects.requireNonNull(cold, "cold");
        }
    }

    record ProposalSubmitted(ProposalState proposal) implements LedgerChange {
        public ProposalSubmitted {
            Objects.requireNonNull(proposal, "proposal");
        }
    }

    record VoteCast(Voter voter, GovActionId actionId, Vote vote) implements LedgerChange {
        public VoteCast {
            Objects.requireNonNull(voter, "voter");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(vote, "vote");
        }
    }
}
