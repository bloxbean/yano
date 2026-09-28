package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.NewConstitution;
import com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledgerstate.EpochBoundaryPreview;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.POOL_DEPOSIT;
import static org.yanoproject.runtime.ledger.canonical.TickingTestNode.slot;

/**
 * ADR-056 Phase 1 ticking gate (synthetic, real code paths): the {@link TickedLedgerView} dry run of
 * the boundary 11 → 12 over a snapshot taken just before it equals what the real boundary
 * ({@code handleEpochTransition} → {@code EpochBoundaryProcessor}, governance Phase 1, POOLREAP,
 * {@code handlePostEpochTransition}) persists, read back through a {@link CanonicalLedgerView} of a
 * snapshot taken after it.
 *
 * <p>The state exercises: a pending ParameterChange enactment with a sibling and the sibling's
 * descendant, an UpdateCommittee enactment (removal, addition keeping a pre-authorized hot key, new
 * member, new threshold), a TreasuryWithdrawals enactment to a registered and an unregistered
 * account, an expired NewConstitution with a descendant, deposit refunds to registered and
 * unregistered accounts, a pre-Conway-style pending parameter update, two pools retiring with a
 * registered and an unregistered reward account, and a pool re-registered with a new VRF key.</p>
 */
class TickedLedgerViewEquivalenceTest {

    private static final String R1 = "11".repeat(28);
    private static final String R2 = "12".repeat(28);
    private static final String U1 = "13".repeat(28);
    private static final String P1 = "a1".repeat(28);
    private static final String P2 = "a2".repeat(28);
    private static final String P3 = "a3".repeat(28);
    private static final String V1 = "b1".repeat(32);
    private static final String V2 = "b2".repeat(32);
    private static final String V3A = "b3".repeat(32);
    private static final String V3B = "b4".repeat(32);
    private static final String C1 = "c1".repeat(28);
    private static final String C2 = "c2".repeat(28);
    private static final String C3 = "c3".repeat(28);
    private static final String C4 = "c4".repeat(28);
    private static final String H1 = "91".repeat(28);
    private static final String H2 = "92".repeat(28);
    private static final String H3 = "93".repeat(28);
    private static final String GUARDRAIL = "5c".repeat(28);
    private static final String DR1 = "d1".repeat(28);
    private static final String DR2 = "d2".repeat(28);
    private static final String DR3 = "d3".repeat(28);
    private static final BigInteger DEPOSIT = BigInteger.valueOf(100_000_000L);

    private static final Gid A = new Gid("a0".repeat(32), 0);
    private static final Gid A_SIB = new Gid("a5".repeat(32), 0);
    private static final Gid A_SIB_CHILD = new Gid("a6".repeat(32), 1);
    private static final Gid B = new Gid("b0".repeat(32), 0);
    private static final Gid T = new Gid("c0".repeat(32), 0);
    private static final Gid D = new Gid("d0".repeat(32), 0);
    private static final Gid D_CHILD = new Gid("d5".repeat(32), 0);
    private static final Gid K = new Gid("e0".repeat(32), 0);
    private static final Gid N = new Gid("f0".repeat(32), 2);
    private static final List<Gid> ALL = List.of(A, A_SIB, A_SIB_CHILD, B, T, D, D_CHILD, K, N);

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
    void tickedViewEqualsTheStatePersistedByTheRealBoundary() throws Exception {
        node = new TickingTestNode(tempDir.resolve("db"), true, true);
        prepareStateBeforeBoundary();
        assertThat(node.gate.tip().ledgerEpoch()).isEqualTo(11);

        // Fail-closed reads of the ticked view.
        try (CanonicalLedgerView base = view(); TickedLedgerView ticked = TickedLedgerView.of(base, slot(12) + 10)) {
            assertThat(ticked.mode()).isEqualTo(TickedLedgerView.Mode.TICKED);
            assertThat(ticked.account(CredentialKey.key(R1))).isInstanceOf(Lookup.Unavailable.class);
            assertThat(ticked.treasury()).isInstanceOf(Lookup.Unavailable.class);
        }

        // Tick, run the real boundary (with rewards), compare.
        TickingGate.Result result = TickingGate.run(node, explicitProbes(), false);
        assertThat(result.mismatches()).isEmpty();

        // The fixture really changes each value, so the equality above is not vacuous.
        EpochBoundaryPreview preview = result.preview();
        assertThat(preview.retiredPools()).containsExactlyInAnyOrder(P1, P2);
        assertThat(preview.unclaimedPoolDeposits()).isEqualTo(POOL_DEPOSIT);
        assertThat(preview.poolDepositRefunds()).containsExactly(Map.entry("0:" + R1, POOL_DEPOSIT));
        EpochBoundaryPreview.GovernanceEffects gov = preview.governance();
        assertThat(gov.removedProposals()).hasSize(7);
        assertThat(gov.unclaimedToTreasury())
                .isEqualTo(BigInteger.valueOf(2_000).add(DEPOSIT.multiply(BigInteger.TWO)));
        assertThat(gov.treasuryDelta()).isEqualTo(DEPOSIT.multiply(BigInteger.TWO).subtract(BigInteger.valueOf(1_000)));
        TickingGate.Values ticked = result.ticked();
        assertThat(ticked.protocolParams().maxTxSize()).isEqualTo(20_000);
        assertThat(ticked.protocolParams().minFeeA()).isEqualTo(99);
        assertThat(ticked.protocolParams().minFeeB()).isEqualTo(155_000);
        assertThat(ticked.protocolParams().costModels()).isNotEmpty();
        assertThat(ticked.candidates()).isEmpty();
        assertThat(ticked.activeProposals()).hasSize(2);
        assertThat(ticked.proposals().get(A.view().toString())).isEqualTo("absent");
        assertThat(ticked.proposals().get(K.view().toString())).startsWith(K.view().toString());
        assertThat(ticked.pools().get(P1)).isEqualTo("absent");
        assertThat(ticked.pools().get(P3)).contains(V3B);
        assertThat(ticked.vrfs().get(V3A)).isEqualTo("absent");
        assertThat(ticked.vrfs().get(V3B)).isEqualTo(new PoolId(P3).toString());
        assertThat(ticked.committeeByCold().get(CredentialKey.key(C2).toString())).isEqualTo("absent");
        assertThat(ticked.committeeByCold().get(CredentialKey.key(C3).toString()))
                .isEqualTo(new CommitteeMemberState(CredentialKey.key(C3), CredentialKey.key(H3), false, 120L).toString());
        assertThat(ticked.guardrail()).isEqualTo(GUARDRAIL);
        assertThat(result.real().activeProposals()).isEqualTo(ticked.activeProposals());
        assertThat(ticked.dreps()).isEqualTo(Map.of(
                CredentialKey.key(DR1).toString(), "registered/deposit=500",
                CredentialKey.key(DR2).toString(), "registered/deposit=600",
                CredentialKey.key(DR3).toString(), "absent"));
        assertThat(ticked.drepExpiries().get(CredentialKey.key(DR2).toString())).isEqualTo(10L);
        // No dormant flush at this boundary, so even the passed-through expiries match.
        assertThat(result.drepExpiryDifferences()).isEmpty();

        // Reward-account credits: the Phase 1 reward_rest writes (compared inside the gate), then the
        // PostEpochTransition credit plus the POOLREAP refunds (no rewards are earned in this fixture).
        assertThat(result.persistedRewardRest()).hasSize(3);
        Map<String, BigInteger> expectedCredit = new LinkedHashMap<>();
        preview.poolDepositRefunds().forEach((k, v) -> expectedCredit.merge(k, v, BigInteger::add));
        preview.rewardRestCredits().forEach((k, v) -> expectedCredit.merge(k, v, BigInteger::add));
        assertThat(result.rewardDeltas()).isEqualTo(Map.of(
                "0:" + R1, POOL_DEPOSIT.add(DEPOSIT.multiply(BigInteger.TWO)).add(BigInteger.valueOf(1_000)),
                "0:" + R2, DEPOSIT.multiply(BigInteger.valueOf(3))));
        assertThat(result.rewardDeltas()).isEqualTo(expectedCredit);
    }

    /**
     * The path the opt-in chain-state harness takes: the store is reopened from disk (the tracker's
     * finalized and pending parameters reload from {@code epoch_params}), and the real boundary
     * resumes after its reward step.
     */
    @Test
    void reopenedStoreWithoutRewardStepPassesTheGate() throws Exception {
        Path directory = tempDir.resolve("reopened");
        node = new TickingTestNode(directory, true, true);
        prepareStateBeforeBoundary();
        node.close();
        node = new TickingTestNode(directory, true, true);
        assertThat(node.gate.tip().ledgerEpoch()).isEqualTo(11);

        TickingGate.Result result = TickingGate.run(node, explicitProbes(), true);

        assertThat(result.mismatches()).isEmpty();
        assertThat(result.ticked().protocolParams().minFeeA()).isEqualTo(99);
        assertThat(result.preview().retiredPools()).containsExactlyInAnyOrder(P1, P2);
        assertThat(result.persistedRewardRest()).hasSize(3);
        assertThat(result.rewardDeltas().get("0:" + R2)).isEqualTo(DEPOSIT.multiply(BigInteger.valueOf(3)));
    }

    /** Records the fixture touches, including ones that exist on neither side of the boundary. */
    private static TickingGate.Probes explicitProbes() {
        Set<GovActionId> proposals = new LinkedHashSet<>();
        ALL.forEach(id -> proposals.add(id.view()));
        Set<CredentialKey> colds = new LinkedHashSet<>();
        List.of(C1, C2, C3, C4).forEach(c -> colds.add(CredentialKey.key(c)));
        Set<CredentialKey> hots = new LinkedHashSet<>();
        List.of(H1, H2, H3).forEach(h -> hots.add(CredentialKey.key(h)));
        Set<CredentialKey> dreps = new LinkedHashSet<>();
        List.of(DR1, DR2, DR3).forEach(d -> dreps.add(CredentialKey.key(d)));
        return new TickingGate.Probes(Set.of(P1, P2, P3), Set.of(V1, V2, V3A, V3B), proposals, colds, hots, dreps);
    }

    private CanonicalLedgerView view() {
        CanonicalSnapshot snapshot = node.acquire();
        CanonicalLedgerView view = CanonicalLedgerView.over(snapshot);
        snapshot.release();
        return view;
    }

    // ------------------------------------------------------------------ fixture

    private void prepareStateBeforeBoundary() throws Exception {
        node.finalizeParams(10);
        node.finalizeParams(11);
        node.applyCerts(slot(10) + 1,
                StakeRegistration.builder().stakeCredential(stake(R1)).build(),
                StakeRegistration.builder().stakeCredential(stake(R2)).build(),
                poolRegistration(P1, V1, R1),
                poolRegistration(P2, V2, U1),
                poolRegistration(P3, V3A, R2));
        node.applyCerts(slot(11) + 5,
                PoolRetirement.builder().poolKeyHash(P1).epoch(12).build(),
                PoolRetirement.builder().poolKeyHash(P2).epoch(12).build(),
                poolRegistration(P3, V3B, R2),
                RegDrepCert.builder().drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DR1))
                        .coin(BigInteger.valueOf(500)).build(),
                RegDrepCert.builder().drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DR2))
                        .coin(BigInteger.valueOf(600)).build());
        // Pre-Conway-style parameter update proposed in epoch 11, effective at 12 ('P' key).
        node.apply(slot(11) + 6, List.of(TransactionBody.builder()
                .txHash("77".repeat(32))
                .update(new Update(Map.of("genesis", ProtocolParamUpdate.builder().minFeeA(99).build()), 11))
                .build()));

        node.writeGovernance((store, batch, ops) -> {
            // DR2 is registered but inactive (a ratification flag): it must stay Present.
            store.storeDRepState(0, DR2, store.getDRepState(0, DR2).orElseThrow().withExpiry(10, false), batch, ops);
            store.storeCommitteeMember(0, C1, new CommitteeMemberRecord(0, H1, 100, false), batch, ops);
            store.storeCommitteeMember(0, C2, new CommitteeMemberRecord(0, H2, 100, false), batch, ops);
            store.storeCommitteeMember(0, C3, new CommitteeMemberRecord(0, H3, 0, false), batch, ops);
            store.storeCommitteeThreshold(BigInteger.TWO, BigInteger.valueOf(3), batch, ops);
            store.storeCommitteePresent(true, batch, ops);
            store.storeConstitution(new GovernanceCborCodec.ConstitutionRecord("https://c", "00".repeat(32),
                    GUARDRAIL), batch, ops);

            store.storeProposal(A.yaci(), proposal(GovActionType.PARAMETER_CHANGE_ACTION, R1, null,
                    new ParameterChangeAction(null, ProtocolParamUpdate.builder().maxTxSize(20_000)
                            .minFeeB(155_000).build(), null)), batch, ops);
            store.storeProposal(A_SIB.yaci(), proposal(GovActionType.PARAMETER_CHANGE_ACTION, R2, null,
                    new ParameterChangeAction(null, ProtocolParamUpdate.builder().maxTxSize(1).build(), null)),
                    batch, ops);
            store.storeProposal(A_SIB_CHILD.yaci(), proposal(GovActionType.PARAMETER_CHANGE_ACTION, U1, A_SIB,
                    new ParameterChangeAction(A_SIB.yaci(), ProtocolParamUpdate.builder().maxTxSize(2).build(),
                            null)), batch, ops);
            Map<Credential, Integer> added = new LinkedHashMap<>();
            added.put(new Credential(StakeCredType.ADDR_KEYHASH, C3), 120);
            added.put(new Credential(StakeCredType.ADDR_KEYHASH, C4), 130);
            store.storeProposal(B.yaci(), proposal(GovActionType.UPDATE_COMMITTEE, R1, null,
                    UpdateCommittee.builder()
                            .membersForRemoval(new LinkedHashSet<>(List.of(
                                    new Credential(StakeCredType.ADDR_KEYHASH, C2))))
                            .newMembersAndTerms(added)
                            .threshold(new UnitInterval(BigInteger.valueOf(3), BigInteger.valueOf(5)))
                            .build()), batch, ops);
            Map<String, BigInteger> withdrawals = new LinkedHashMap<>();
            withdrawals.put("e0" + R1, BigInteger.valueOf(1_000));
            withdrawals.put("e0" + U1, BigInteger.valueOf(2_000));
            store.storeProposal(T.yaci(), proposal(GovActionType.TREASURY_WITHDRAWALS_ACTION, R2, null,
                    new TreasuryWithdrawalsAction(withdrawals, null)), batch, ops);
            store.storeProposal(D.yaci(), proposal(GovActionType.NEW_CONSTITUTION, U1, null,
                    constitution(null, "d")), batch, ops);
            store.storeProposal(D_CHILD.yaci(), proposal(GovActionType.NEW_CONSTITUTION, R2, D,
                    constitution(D, "e")), batch, ops);
            store.storeProposal(K.yaci(), proposal(GovActionType.INFO_ACTION, R1, null, new InfoAction()),
                    batch, ops);
            store.storeProposal(N.yaci(), proposal(GovActionType.NEW_CONSTITUTION, R2, null,
                    constitution(null, "f")), batch, ops);

            store.storePendingEnactment(A.yaci(), batch, ops);
            store.storePendingEnactment(B.yaci(), batch, ops);
            store.storePendingEnactment(T.yaci(), batch, ops);
            store.storePendingDrop(D.yaci(), batch, ops);
        });
        node.setTip(slot(11) + 400_000);
    }

    private static GovActionRecord proposal(GovActionType type, String returnCredential, Gid prev, GovAction action) {
        return new GovActionRecord(DEPOSIT, "e0" + returnCredential, 10, 16, type,
                prev != null ? prev.tx() : null, prev != null ? prev.index() : null, action, slot(10) + 100);
    }

    private static NewConstitution constitution(Gid prev, String tag) {
        return new NewConstitution(prev != null ? prev.yaci() : null,
                new Constitution(new Anchor("https://" + tag, tag.repeat(64)), "5d".repeat(28)));
    }

    private static PoolRegistration poolRegistration(String pool, String vrf, String rewardCredential) {
        return PoolRegistration.builder()
                .poolParams(PoolParams.builder()
                        .operator(pool)
                        .vrfKeyHash(vrf)
                        .pledge(BigInteger.ONE)
                        .cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount("e0" + rewardCredential)
                        .poolOwners(Set.of(rewardCredential))
                        .build())
                .build();
    }

    private static StakeCredential stake(String hash) {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    // ------------------------------------------------------------------ ids

    private record Gid(String tx, int index) {
        com.bloxbean.cardano.yaci.core.model.governance.GovActionId yaci() {
            // Fully qualified: the view model's GovActionId is imported.
            return new com.bloxbean.cardano.yaci.core.model.governance.GovActionId(tx, index);
        }

        GovActionId view() {
            return new GovActionId(tx, index);
        }
    }
}
