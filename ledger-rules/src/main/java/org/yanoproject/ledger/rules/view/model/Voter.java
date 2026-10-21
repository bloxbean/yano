package org.yanoproject.ledger.rules.view.model;

import java.util.Objects;

/**
 * A governance voter.
 *
 * <p>A committee voter is identified by its <em>hot</em> credential, a DRep by its credential, and a
 * stake pool by its pool key hash (always {@link CredentialType#KEY}).</p>
 *
 * @param role       voter class
 * @param credential hot credential, DRep credential, or pool key hash
 */
public record Voter(Role role, CredentialKey credential) {

    public enum Role {
        CONSTITUTIONAL_COMMITTEE,
        DREP,
        STAKE_POOL
    }

    public Voter {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(credential, "credential");
        if (role == Role.STAKE_POOL && credential.type() != CredentialType.KEY) {
            throw new IllegalArgumentException("A stake pool voter is always a key hash");
        }
    }

    /** Converts a CCL governance voter. */
    public static Voter of(com.bloxbean.cardano.client.transaction.spec.governance.Voter voter) {
        // Fully qualified: the CCL type has the same simple name as this record.
        Objects.requireNonNull(voter, "voter");
        CredentialKey cred = CredentialKey.of(voter.getCredential());
        Role role = switch (voter.getType()) {
            case CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH ->
                    Role.CONSTITUTIONAL_COMMITTEE;
            case DREP_KEY_HASH, DREP_SCRIPT_HASH -> Role.DREP;
            case STAKING_POOL_KEY_HASH -> Role.STAKE_POOL;
        };
        return new Voter(role, cred);
    }

    /** @return the pool id when {@link #role()} is {@link Role#STAKE_POOL} */
    public PoolId poolId() {
        if (role != Role.STAKE_POOL) {
            throw new IllegalStateException("Not a stake pool voter: " + this);
        }
        return new PoolId(credential.hashHex());
    }
}
