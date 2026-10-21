package org.yanoproject.ledger.rules.view.model;

import java.util.Objects;

/**
 * What the ledger knows about one committee cold credential.
 *
 * <p>This joins two Haskell structures: the elected committee ({@code committeeMembers}, cold →
 * expiry epoch) and the committee state ({@code csCommitteeCreds}, cold → hot credential or
 * resigned). A cold credential can have an entry in either or both: a candidate from a pending
 * {@code UpdateCommittee} proposal can authorize a hot key before it is elected.</p>
 *
 * @param cold        the cold credential
 * @param hot         the authorized hot credential, or {@code null}
 * @param resigned    true when the member has resigned ({@code CommitteeMemberResigned})
 * @param expiryEpoch the term's expiry epoch when the credential is a member of the current
 *                    committee, otherwise {@code null}
 */
public record CommitteeMemberState(CredentialKey cold, CredentialKey hot, boolean resigned, Long expiryEpoch) {

    public CommitteeMemberState {
        Objects.requireNonNull(cold, "cold");
        if (resigned && hot != null) {
            throw new IllegalArgumentException("A resigned member has no hot credential");
        }
    }

    /** @return true when the credential is a member of the current (elected) committee */
    public boolean isElected() {
        return expiryEpoch != null;
    }

    /** Haskell {@code CommitteeHotCredential}: overwrites any previous hot key (GovCert.hs:273-274). */
    public CommitteeMemberState withHot(CredentialKey newHot) {
        return new CommitteeMemberState(cold, Objects.requireNonNull(newHot, "hot"), false, expiryEpoch);
    }

    /** Haskell {@code CommitteeMemberResigned} (GovCert.hs:275-276). */
    public CommitteeMemberState asResigned() {
        return new CommitteeMemberState(cold, null, true, expiryEpoch);
    }
}
