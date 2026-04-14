package com.bloxbean.cardano.yano.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yano.ledgerstate.governance.ratification.ProposalDropService;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for prevGovSnapshots filtering (Haskell Epoch.hs:270) and deferred
 * sibling/descendant drops in Phase 1.
 * <p>
 * Haskell RATIFY uses prevGovSnapshots. At boundary previousEpoch → newEpoch,
 * prevGovSnapshots contains proposals accumulated in curGovSnapshots through
 * previousEpoch — including proposals submitted during previousEpoch. Only
 * proposals submitted during newEpoch are excluded.
 * <p>
 * Filter: {@code proposedInEpoch <= previousEpoch}
 * <p>
 * Uses real RocksDB via {@link TestRocksDBHelper} — no mocks.
 */
class PrevGovSnapshotsTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore store;
    private ProposalDropService dropService;

    private static final BigInteger GOV_DEPOSIT = BigInteger.valueOf(100_000_000_000L);
    private static final String RETURN_ADDR_A = "e0aaaa000000000000000000000000000000000000000000000000aa";
    private static final String RETURN_ADDR_B = "e0bbbb000000000000000000000000000000000000000000000000bb";
    private static final String RETURN_ADDR_C = "e0cccc000000000000000000000000000000000000000000000000cc";

    // Shared prevActionId for siblings (both point to the same enacted predecessor)
    private static final String PREV_ACTION_TX = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int PREV_ACTION_IDX = 0;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = rocks.governanceStore();
        dropService = new ProposalDropService();
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    private GovActionId id(String txHash, int idx) {
        return new GovActionId(txHash, idx);
    }

    private GovActionRecord paramChangeProposal(int proposedInEpoch, int expiresAfter, String returnAddr) {
        return new GovActionRecord(GOV_DEPOSIT, returnAddr, proposedInEpoch, expiresAfter,
                GovActionType.PARAMETER_CHANGE_ACTION, PREV_ACTION_TX, PREV_ACTION_IDX,
                null, proposedInEpoch * 432000L);
    }

    private GovActionRecord paramChangeProposalNoPrev(int proposedInEpoch, int expiresAfter, String returnAddr) {
        return new GovActionRecord(GOV_DEPOSIT, returnAddr, proposedInEpoch, expiresAfter,
                GovActionType.PARAMETER_CHANGE_ACTION, null, null,
                null, proposedInEpoch * 432000L);
    }

    private GovActionRecord infoProposal(int proposedInEpoch, int expiresAfter, String returnAddr) {
        return new GovActionRecord(GOV_DEPOSIT, returnAddr, proposedInEpoch, expiresAfter,
                GovActionType.INFO_ACTION, null, null,
                null, proposedInEpoch * 432000L);
    }

    // -- Helper to build a child proposal pointing to a given parent --
    private GovActionRecord childOfProposal(GovActionId parentId, int proposedInEpoch, int expiresAfter, String returnAddr) {
        return new GovActionRecord(GOV_DEPOSIT, returnAddr, proposedInEpoch, expiresAfter,
                GovActionType.PARAMETER_CHANGE_ACTION, parentId.getTransactionId(), parentId.getGov_action_index(),
                null, proposedInEpoch * 432000L);
    }

    // ==================== Test 1: Sibling not dropped early ====================

    @Test
    @DisplayName("Test 1: Sibling submitted in newEpoch not dropped at boundary where it was submitted")
    void siblingFromNewEpoch_notDroppedEarly_droppedAtNextBoundary() throws Exception {
        // Proposal A: epoch 606, PARAMETER_CHANGE — will be ratified at 612→613
        GovActionId idA = id("c21b00f900000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propA = paramChangeProposal(606, 612, RETURN_ADDR_A);

        // Proposal B: epoch 613 (newEpoch), PARAMETER_CHANGE, same prevAction — sibling of A
        // Excluded from ratification at 612→613 boundary because 613 <= 612 is false.
        GovActionId idB = id("dfdac59200000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propB = paramChangeProposal(613, 619, RETURN_ADDR_B);

        // Store both proposals
        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idA, propA, batch, new ArrayList<>());
            store.storeProposal(idB, propB, batch, new ArrayList<>());
            commit(batch);
        }

        // --- Boundary 612→613 (previousEpoch=612, newEpoch=613) ---
        int previousEpoch = 612;
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        assertThat(allActive).hasSize(2);

        // prevGovSnapshots filter: proposedInEpoch <= previousEpoch (612)
        Map<GovActionId, GovActionRecord> ratifiable = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= previousEpoch) {
                ratifiable.put(entry.getKey(), entry.getValue());
            }
        }

        // Only A should be ratifiable (epoch 606 <= 612). B (epoch 613) excluded.
        assertThat(ratifiable).hasSize(1);
        assertThat(ratifiable).containsKey(idA);
        assertThat(ratifiable).doesNotContainKey(idB);

        // Simulate: A ratifies → stored as pending enactment
        try (WriteBatch batch = new WriteBatch()) {
            store.storePendingEnactment(idA, batch, new ArrayList<>());
            commit(batch);
        }

        // B should still be active (NOT dropped — it wasn't in ratifiable set)
        assertThat(store.getAllActiveProposals()).containsKey(idB);

        // --- Boundary 613→614: Phase 1 processes pending enactment of A ---
        Map<GovActionId, GovActionRecord> allProposals = store.getAllActiveProposals();
        List<GovActionId> pendingEnactments = store.getPendingEnactments();
        assertThat(pendingEnactments).hasSize(1);

        // Phase 1: refund A + find siblings against FULL active set
        Set<GovActionId> removedIds = new LinkedHashSet<>();
        Map<String, BigInteger> refunds = new HashMap<>();

        // Refund enacted proposal A
        GovActionRecord pA = allProposals.get(idA);
        assertThat(pA).isNotNull();
        removedIds.add(idA);
        refunds.merge(pA.returnAddress(), pA.deposit(), BigInteger::add);

        // Find siblings of A in full active set (should find B)
        Set<GovActionId> siblings = dropService.findSiblings(idA, pA, allProposals);
        assertThat(siblings).containsExactly(idB);

        // Refund sibling B
        for (GovActionId sibId : siblings) {
            GovActionRecord sib = allProposals.get(sibId);
            assertThat(sib).isNotNull();
            removedIds.add(sibId);
            refunds.merge(sib.returnAddress(), sib.deposit(), BigInteger::add);
        }

        // Remove both from store
        try (WriteBatch batch = new WriteBatch()) {
            for (GovActionId rid : removedIds) {
                store.removeProposal(rid, batch, new ArrayList<>());
            }
            store.clearPending(batch, new ArrayList<>());
            commit(batch);
        }

        // Both A and B should be removed
        assertThat(store.getAllActiveProposals()).isEmpty();

        // Refunds: A's deposit to RETURN_ADDR_A, B's deposit to RETURN_ADDR_B
        assertThat(refunds).containsEntry(RETURN_ADDR_A, GOV_DEPOSIT);
        assertThat(refunds).containsEntry(RETURN_ADDR_B, GOV_DEPOSIT);
    }

    // ==================== Test 2: newEpoch proposal excluded from ratification ====================

    @Test
    @DisplayName("Test 2: Proposal submitted in newEpoch is excluded from ratification set")
    void newEpochProposal_excludedFromRatification() throws Exception {
        // Proposal submitted in epoch 613 (the newEpoch at boundary 612→613)
        GovActionId idC = id("aabb000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propC = infoProposal(613, 619, RETURN_ADDR_C);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idC, propC, batch, new ArrayList<>());
            commit(batch);
        }

        // Boundary 612→613: previousEpoch=612
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        Map<GovActionId, GovActionRecord> ratifiable612 = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable612.put(entry.getKey(), entry.getValue());
            }
        }
        // C (epoch 613) should NOT be in ratification set at 612→613
        assertThat(ratifiable612).isEmpty();

        // Boundary 613→614: previousEpoch=613
        Map<GovActionId, GovActionRecord> ratifiable613 = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 613) {
                ratifiable613.put(entry.getKey(), entry.getValue());
            }
        }
        // C (epoch 613) IS in ratification set at 613→614 (613 <= 613)
        assertThat(ratifiable613).containsKey(idC);
    }

    // ==================== Test 2b: previousEpoch proposal included in ratification ====================

    @Test
    @DisplayName("Test 2b: Proposal submitted in previousEpoch IS ratifiable at that boundary")
    void previousEpochProposal_isRatifiableAtBoundary() throws Exception {
        // Proposal submitted in epoch 612 (the previousEpoch at boundary 612→613)
        GovActionId idP = id("bbcc000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propP = infoProposal(612, 618, RETURN_ADDR_A);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idP, propP, batch, new ArrayList<>());
            commit(batch);
        }

        // Boundary 612→613: previousEpoch=612
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        Map<GovActionId, GovActionRecord> ratifiable = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable.put(entry.getKey(), entry.getValue());
            }
        }
        // P (epoch 612) IS ratifiable at 612→613 (612 <= 612 is true)
        assertThat(ratifiable).containsKey(idP);
    }

    // ==================== Test 3: De-dup prevents double refund ====================

    @Test
    @DisplayName("Test 3: Proposal that is both pending-drop AND sibling of enacted is refunded once")
    void dedup_pendingDropAndSibling_refundedOnce() throws Exception {
        // Proposal E: ratified at previous boundary (pending enactment)
        GovActionId idE = id("eeee000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propE = paramChangeProposal(606, 612, RETURN_ADDR_A);

        // Proposal D: expired at previous boundary (pending drop) AND sibling of E
        GovActionId idD = id("dddd000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propD = paramChangeProposal(608, 612, RETURN_ADDR_B);

        // Store proposals
        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idE, propE, batch, new ArrayList<>());
            store.storeProposal(idD, propD, batch, new ArrayList<>());
            // D is both pending drop AND sibling of E
            store.storePendingEnactment(idE, batch, new ArrayList<>());
            store.storePendingDrop(idD, batch, new ArrayList<>());
            commit(batch);
        }

        Map<GovActionId, GovActionRecord> allProposals = store.getAllActiveProposals();
        Set<GovActionId> removedIds = new LinkedHashSet<>();
        Map<String, BigInteger> refunds = new HashMap<>();
        BigInteger totalRefunds = BigInteger.ZERO;

        // 3a. Enacted: refund E
        GovActionRecord pE = allProposals.get(idE);
        if (pE != null && removedIds.add(idE)) {
            refunds.merge(pE.returnAddress(), pE.deposit(), BigInteger::add);
            totalRefunds = totalRefunds.add(pE.deposit());
        }

        // 3b. Expired (pending drop): refund D
        GovActionRecord pD = allProposals.get(idD);
        if (pD != null && removedIds.add(idD)) {
            refunds.merge(pD.returnAddress(), pD.deposit(), BigInteger::add);
            totalRefunds = totalRefunds.add(pD.deposit());
        }

        // 3c. Siblings of E — should find D, but D already in removedIds → skipped
        Set<GovActionId> siblings = dropService.findSiblings(idE, propE, allProposals);
        assertThat(siblings).contains(idD); // D is a sibling of E
        for (GovActionId sibId : siblings) {
            GovActionRecord sib = allProposals.get(sibId);
            if (sib != null && removedIds.add(sibId)) {
                // This should NOT execute for D (already in removedIds)
                refunds.merge(sib.returnAddress(), sib.deposit(), BigInteger::add);
                totalRefunds = totalRefunds.add(sib.deposit());
            }
        }

        // D should be refunded exactly once (200B total = E + D, not 300B)
        assertThat(totalRefunds).isEqualTo(GOV_DEPOSIT.multiply(BigInteger.TWO));
        assertThat(removedIds).hasSize(2);
    }

    // ==================== Test 4: Descendant drop in Phase 1 ====================

    @Test
    @DisplayName("Test 4: Descendants of siblings are dropped in Phase 1")
    void descendantsOfSiblings_droppedInPhase1() throws Exception {
        // F: ratified at previous boundary (pending enactment)
        GovActionId idF = id("ffff000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propF = paramChangeProposal(610, 616, RETURN_ADDR_A);

        // H: sibling of F (same prevAction, same type)
        GovActionId idH = id("1111000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propH = paramChangeProposal(611, 617, RETURN_ADDR_B);

        // G: child of H (prevAction points to H)
        GovActionId idG = id("2222000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propG = childOfProposal(idH, 611, 617, RETURN_ADDR_C);

        // Store all proposals + pending enactment for F
        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idF, propF, batch, new ArrayList<>());
            store.storeProposal(idH, propH, batch, new ArrayList<>());
            store.storeProposal(idG, propG, batch, new ArrayList<>());
            store.storePendingEnactment(idF, batch, new ArrayList<>());
            commit(batch);
        }

        Map<GovActionId, GovActionRecord> allProposals = store.getAllActiveProposals();
        assertThat(allProposals).hasSize(3);

        Set<GovActionId> removedIds = new LinkedHashSet<>();
        Map<String, BigInteger> refunds = new HashMap<>();
        BigInteger totalRefunds = BigInteger.ZERO;

        // 3a. Enacted: refund F
        removedIds.add(idF);
        refunds.merge(propF.returnAddress(), propF.deposit(), BigInteger::add);
        totalRefunds = totalRefunds.add(propF.deposit());

        // 3c. Siblings of F → finds H
        Set<GovActionId> siblings = dropService.findSiblings(idF, propF, allProposals);
        assertThat(siblings).containsExactly(idH);

        for (GovActionId sibId : siblings) {
            GovActionRecord sib = allProposals.get(sibId);
            if (sib != null && removedIds.add(sibId)) {
                refunds.merge(sib.returnAddress(), sib.deposit(), BigInteger::add);
                totalRefunds = totalRefunds.add(sib.deposit());
            }
            // Descendants of H → finds G
            if (sib != null) {
                Set<GovActionId> descendants = dropService.findDescendants(sibId, sib, allProposals);
                assertThat(descendants).containsExactly(idG);
                for (GovActionId descId : descendants) {
                    GovActionRecord desc = allProposals.get(descId);
                    if (desc != null && removedIds.add(descId)) {
                        refunds.merge(desc.returnAddress(), desc.deposit(), BigInteger::add);
                        totalRefunds = totalRefunds.add(desc.deposit());
                    }
                }
            }
        }

        // All three should be removed: F (enacted), H (sibling), G (descendant of H)
        assertThat(removedIds).containsExactlyInAnyOrder(idF, idH, idG);
        assertThat(totalRefunds).isEqualTo(GOV_DEPOSIT.multiply(BigInteger.valueOf(3)));
    }

    // ==================== Test 5: Dormant epoch uses ratifiableProposals ====================

    @Test
    @DisplayName("Test 5: Epoch with only newEpoch proposals is dormant")
    void dormant_onlyNewEpochProposals_isDormant() throws Exception {
        // Proposal submitted in epoch 613 (the newEpoch at boundary 612→613)
        GovActionId idFresh = id("5555000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propFresh = infoProposal(613, 619, RETURN_ADDR_A);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idFresh, propFresh, batch, new ArrayList<>());
            commit(batch);
        }

        // Boundary 612→613: previousEpoch=612
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        assertThat(allActive).hasSize(1); // proposal exists

        Map<GovActionId, GovActionRecord> ratifiable = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable.put(entry.getKey(), entry.getValue());
            }
        }

        // ratifiable is empty (613 <= 612 is false) → epoch is dormant
        boolean epochHadActiveProposals = !ratifiable.isEmpty();
        assertThat(epochHadActiveProposals).isFalse();
    }

    // ==================== Test 5b: Non-dormant even when all ratify ====================

    @Test
    @DisplayName("Test 5b: Epoch with proposals that all ratify is still non-dormant")
    void dormant_allProposalsRatify_stillNotDormant() throws Exception {
        // Proposal from epoch N-1 — in ratifiable set
        GovActionId idOld = id("6666000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propOld = infoProposal(611, 617, RETURN_ADDR_A);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idOld, propOld, batch, new ArrayList<>());
            commit(batch);
        }

        // Boundary 612→613: previousEpoch=612
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        Map<GovActionId, GovActionRecord> ratifiable = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable.put(entry.getKey(), entry.getValue());
            }
        }

        assertThat(ratifiable).hasSize(1);

        // Haskell: wasPrevEpochDormant = Seq.null prevGovSnapshots
        // ratifiable is non-empty → NOT dormant, even if this proposal ratifies
        boolean epochHadActiveProposals = !ratifiable.isEmpty();
        assertThat(epochHadActiveProposals).isTrue();
    }

    // ==================== Test 5c: previousEpoch proposal makes epoch non-dormant ====================

    @Test
    @DisplayName("Test 5c: Proposal submitted in previousEpoch makes epoch non-dormant")
    void dormant_previousEpochProposal_isNotDormant() throws Exception {
        // Proposal submitted in epoch 612 (the previousEpoch at boundary 612→613)
        GovActionId idSameEpoch = id("8888000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propSameEpoch = infoProposal(612, 618, RETURN_ADDR_A);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idSameEpoch, propSameEpoch, batch, new ArrayList<>());
            commit(batch);
        }

        // Boundary 612→613: previousEpoch=612
        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();
        Map<GovActionId, GovActionRecord> ratifiable = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable.put(entry.getKey(), entry.getValue());
            }
        }

        // 612 <= 612 is true → proposal is ratifiable → epoch is NOT dormant
        assertThat(ratifiable).hasSize(1);
        boolean epochHadActiveProposals = !ratifiable.isEmpty();
        assertThat(epochHadActiveProposals).isTrue();
    }

    // ==================== Test 6: newEpoch proposal ratifiable at next boundary ====================

    @Test
    @DisplayName("Test 6: Proposal submitted in newEpoch becomes ratifiable at next boundary")
    void newEpochProposal_ratifiableAtNextBoundary() throws Exception {
        // Proposal submitted in epoch 613 (the newEpoch at boundary 612→613)
        GovActionId idFresh = id("7777000000000000000000000000000000000000000000000000000000000000", 0);
        GovActionRecord propFresh = paramChangeProposalNoPrev(613, 619, RETURN_ADDR_A);

        try (WriteBatch batch = new WriteBatch()) {
            store.storeProposal(idFresh, propFresh, batch, new ArrayList<>());
            commit(batch);
        }

        Map<GovActionId, GovActionRecord> allActive = store.getAllActiveProposals();

        // Boundary 612→613: NOT ratifiable (613 <= 612 is false)
        Map<GovActionId, GovActionRecord> ratifiable612 = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 612) {
                ratifiable612.put(entry.getKey(), entry.getValue());
            }
        }
        assertThat(ratifiable612).doesNotContainKey(idFresh);

        // Boundary 613→614: IS ratifiable (613 <= 613 is true)
        Map<GovActionId, GovActionRecord> ratifiable613 = new LinkedHashMap<>();
        for (var entry : allActive.entrySet()) {
            if (entry.getValue().proposedInEpoch() <= 613) {
                ratifiable613.put(entry.getKey(), entry.getValue());
            }
        }
        assertThat(ratifiable613).containsKey(idFresh);
    }
}
