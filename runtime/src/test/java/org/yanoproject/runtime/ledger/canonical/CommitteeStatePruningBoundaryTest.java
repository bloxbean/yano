package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.AuthCommitteeHotCert;
import com.bloxbean.cardano.yaci.core.model.certs.ResignCommitteeColdCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.slot;

/**
 * Haskell's {@code updateCommitteeState} (cardano-ledger f649f975, {@code Conway/Rules/Epoch.hs:343, 419-423}):
 * every epoch boundary keeps the committee state (hot keys, resignations) of the committee's members only.
 * The real boundary ({@code CommitteeStatePruning} in governance Phase 2) and the {@link TickedLedgerView} dry
 * run agree ({@link TickingGate}), and a rollback of the boundary restores what it pruned.
 */
class CommitteeStatePruningBoundaryTest {

    /** Enrolled member, authorises a hot key. */
    private static final String MEMBER = "c1".repeat(28);
    /** Potential future member that authorises a hot key, not enrolled at the boundary. */
    private static final String CANDIDATE = "c5".repeat(28);
    /** Potential future member that resigns, not enrolled at the boundary, enrolled later. */
    private static final String RESIGNED = "c6".repeat(28);
    private static final String MEMBER_HOT = "91".repeat(28);
    private static final String CANDIDATE_HOT = "95".repeat(28);
    private static final String RESIGNED_HOT = "96".repeat(28);
    private static final GovActionId ENROLL = new GovActionId("e1".repeat(32), 0);

    @TempDir
    Path tempDir;

    private TickingTestNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.close();
        }
    }

    @Test
    void nonMembersCommitteeStateIsPrunedAtTheBoundaryAsTheTickedViewPredicts() throws Exception {
        prepareEpoch11();

        TickingGate.Result result = TickingGate.run(node, probes(), false);

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.preview().governance().prunedCommitteeColds()).containsExactlyInAnyOrder(
                new org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey(0, CANDIDATE),
                new org.yanoproject.ledgerstate.governance.GovernanceStateStore.CredentialKey(0, RESIGNED));
        assertThat(result.real().committeeByCold().get(CredentialKey.key(CANDIDATE).toString())).isEqualTo("absent");
        assertThat(result.real().committeeByCold().get(CredentialKey.key(RESIGNED).toString())).isEqualTo("absent");
        assertThat(result.real().committeeByCold().get(CredentialKey.key(MEMBER).toString())).isEqualTo(
                new CommitteeMemberState(CredentialKey.key(MEMBER), CredentialKey.key(MEMBER_HOT), false, 100L)
                        .toString());
        assertThat(node.governance.getCommitteeMember(0, CANDIDATE)).isEmpty();
        assertThat(node.accounts.getCommitteeHotCredential(0, CANDIDATE)).isEmpty();
        assertThat(node.accounts.hasCommitteeMemberResigned(0, RESIGNED)).isFalse();
        assertThat(node.accounts.getCommitteeHotCredential(0, MEMBER)).contains(MEMBER_HOT);
    }

    @Test
    void aResignedFutureMemberWhoseProposalExpiredCanLaterBeEnrolledAndAuthorizeAHotKey() throws Exception {
        prepareEpoch11();
        node.crossBoundary(12);

        // Enrolled by an UpdateCommittee enacted at the boundary into 13.
        node.writeGovernance((store, batch, ops) -> {
            Map<Credential, Integer> added = new LinkedHashMap<>();
            added.put(new Credential(StakeCredType.ADDR_KEYHASH, RESIGNED), 130);
            store.storeProposal(ENROLL, new GovActionRecord(BigInteger.ONE, "e0" + "11".repeat(28), 11, 17,
                    GovActionType.UPDATE_COMMITTEE, null, null,
                    UpdateCommittee.builder().membersForRemoval(new LinkedHashSet<>()).newMembersAndTerms(added)
                            .threshold(new UnitInterval(BigInteger.ONE, BigInteger.TWO)).build(), slot(11) + 50), batch, ops);
            store.storePendingEnactment(ENROLL, batch, ops);
        });
        node.crossBoundary(13);

        // Haskell pruned the resignation at the boundary into 12, so the new member is not resigned and
        // its AuthCommitteeHot passes (no ConwayCommitteeHasPreviouslyResigned, GovCert.hs:190-196).
        assertThat(committeeMember(RESIGNED)).isEqualTo(
                new CommitteeMemberState(CredentialKey.key(RESIGNED), null, false, 130L));
        node.applyCerts(slot(13) + 20, auth(RESIGNED, RESIGNED_HOT));
        assertThat(committeeMember(RESIGNED)).isEqualTo(new CommitteeMemberState(
                CredentialKey.key(RESIGNED), CredentialKey.key(RESIGNED_HOT), false, 130L));
    }

    @Test
    void aMemberRemovedByUpdateCommitteeLosesItsCertificatePathHotKey() throws Exception {
        prepareEpoch11();
        // The removal deletes the member's record in Phase 1; its certificate-path hot key has no record
        // left and is pruned in Phase 2.
        node.writeGovernance((store, batch, ops) -> {
            store.storeProposal(ENROLL, new GovActionRecord(BigInteger.ONE, "e0" + "11".repeat(28), 10, 16,
                    GovActionType.UPDATE_COMMITTEE, null, null,
                    UpdateCommittee.builder().membersForRemoval(new LinkedHashSet<>(Set.of(cold(MEMBER))))
                            .newMembersAndTerms(new LinkedHashMap<>())
                            .threshold(new UnitInterval(BigInteger.ONE, BigInteger.TWO)).build(),
                    slot(10) + 50), batch, ops);
            store.storePendingEnactment(ENROLL, batch, ops);
        });

        TickingGate.Result result = TickingGate.run(node, probes(), false);

        assertThat(result.mismatches()).isEmpty();
        assertThat(node.governance.getCommitteeMember(0, MEMBER)).isEmpty();
        assertThat(node.accounts.getCommitteeHotCredential(0, MEMBER)).isEmpty();
        assertThat(result.real().committeeByCold().get(CredentialKey.key(MEMBER).toString())).isEqualTo("absent");
        assertThat(result.ticked().committeeByCold().get(CredentialKey.key(MEMBER).toString())).isEqualTo("absent");
    }

    @Test
    void rollbackAcrossTheBoundaryRestoresThePrunedCommitteeState() throws Exception {
        prepareEpoch11();
        CommitteeMemberRecord candidate = node.governance.getCommitteeMember(0, CANDIDATE).orElseThrow();
        CommitteeMemberRecord resigned = node.governance.getCommitteeMember(0, RESIGNED).orElseThrow();

        node.crossBoundary(12);
        assertThat(node.governance.getCommitteeMember(0, CANDIDATE)).isEmpty();

        node.gate.runWrite(() -> node.accounts.rollbackToSlot(slot(11) + 400_000));

        assertThat(node.governance.getCommitteeMember(0, CANDIDATE)).contains(candidate);
        assertThat(node.governance.getCommitteeMember(0, RESIGNED)).contains(resigned);
        assertThat(node.accounts.getCommitteeHotCredential(0, CANDIDATE)).contains(CANDIDATE_HOT);
        assertThat(node.accounts.hasCommitteeMemberResigned(0, RESIGNED)).isTrue();
    }

    // ------------------------------------------------------------------ fixture

    private void prepareEpoch11() throws Exception {
        node = new TickingTestNode(tempDir.resolve("db"), true, true);
        node.finalizeParams(10);
        node.finalizeParams(11);
        node.writeGovernance((store, batch, ops) -> {
            store.storeCommitteeMember(0, MEMBER, CommitteeMemberRecord.noHotKey(100), batch, ops);
            store.storeCommitteeThreshold(BigInteger.ONE, BigInteger.TWO, batch, ops);
            store.storeCommitteePresent(true, batch, ops);
            store.storeConstitution(new GovernanceCborCodec.ConstitutionRecord("https://c", "00".repeat(32), null),
                    batch, ops);
        });
        node.applyCerts(slot(11) + 5,
                auth(MEMBER, MEMBER_HOT),
                auth(CANDIDATE, CANDIDATE_HOT),
                ResignCommitteeColdCert.builder().committeeColdCredential(cold(RESIGNED)).build());
        node.setTip(slot(11) + 400_000);
    }

    private CommitteeMemberState committeeMember(String cold) {
        CanonicalSnapshot snapshot = node.acquire();
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            snapshot.release();
            Lookup<CommitteeMemberState> member = view.committeeMemberByCold(CredentialKey.key(cold));
            return ((Lookup.Present<CommitteeMemberState>) member).value();
        }
    }

    private static AuthCommitteeHotCert auth(String cold, String hot) {
        return AuthCommitteeHotCert.builder().committeeColdCredential(cold(cold))
                .committeeHotCredential(cold(hot)).build();
    }

    private static Credential cold(String hash) {
        return new Credential(StakeCredType.ADDR_KEYHASH, hash);
    }

    private static TickingGate.Probes probes() {
        Set<CredentialKey> colds = Set.of(CredentialKey.key(MEMBER), CredentialKey.key(CANDIDATE),
                CredentialKey.key(RESIGNED));
        Set<CredentialKey> hots = Set.of(CredentialKey.key(MEMBER_HOT), CredentialKey.key(CANDIDATE_HOT));
        return new TickingGate.Probes(Set.of(), Set.of(), Set.of(), colds, hots, Set.of());
    }
}
