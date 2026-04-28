package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for boundary-delta recovery idempotency:
 * - committed boundary delta + missing step marker → recovery skips phase (no double-apply)
 * - no boundary delta + no step marker → recovery reruns phase (normal)
 * - rollback undoes boundary delta writes
 */
class BoundaryRecoveryIdempotencyTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    private static final String CRED_HASH = "1c46955f71c49a6c987104145d5a18154883f51c846c12a6a02dcd60";
    private static final long BOUNDARY_SLOT = 86400; // epoch 1 start for a preview-like network

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(BoundaryRecoveryIdempotencyTest.class), true);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    // --- getCommittedBoundaryPhases ---

    @Test
    void committedPhases_empty_whenNoDeltasExist() {
        Set<Byte> phases = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(phases).isEmpty();
    }

    @Test
    void committedPhases_returnsPhases_afterBoundaryDeltaCommit() throws Exception {
        // Simulate a committed reward phase boundary delta
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        byte[] testKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
        byte[] testVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(1000), BigInteger.ZERO);
        deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT, testKey, null));

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(), testKey, testVal);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            rocks.db().write(wo, batch);
        }

        Set<Byte> phases = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(phases).containsExactly(DefaultAccountStateStore.PHASE_REWARDS);
    }

    @Test
    void committedPhases_returnsMultiplePhases() throws Exception {
        // Commit two phases at the same boundary slot
        for (byte phase : new byte[]{DefaultAccountStateStore.PHASE_REWARDS, DefaultAccountStateStore.PHASE_POOLREAP}) {
            List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
            deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT,
                    new byte[]{0x01}, null));
            try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
                store.commitBoundaryDelta(BOUNDARY_SLOT, phase, batch, deltaOps);
                rocks.db().write(wo, batch);
            }
        }

        Set<Byte> phases = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(phases).containsExactlyInAnyOrder(
                DefaultAccountStateStore.PHASE_REWARDS,
                DefaultAccountStateStore.PHASE_POOLREAP);
    }

    @Test
    void committedPhases_doesNotReturnOtherSlots() throws Exception {
        long otherSlot = BOUNDARY_SLOT + 86400;

        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT,
                new byte[]{0x01}, null));
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            store.commitBoundaryDelta(otherSlot, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            rocks.db().write(wo, batch);
        }

        // Query for BOUNDARY_SLOT should be empty
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).isEmpty();
        // Query for otherSlot should find it
        assertThat(store.getCommittedBoundaryPhases(otherSlot))
                .containsExactly(DefaultAccountStateStore.PHASE_REWARDS);
    }

    // --- Reward credit + rollback via boundary delta ---

    @Test
    void rewardCredit_survivesCommit_undoneByRollback() throws Exception {
        // Register credential with zero reward
        registerCredential(0, CRED_HASH, BigInteger.ZERO);
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);

        // Simulate reward credit via putStateWithDelta in a boundary batch
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(5000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, deltaOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            // Also set META_LAST_APPLIED_SLOT so rollbackInternal can compute epoch
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }

        // Reward should be credited
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(5000));

        // Boundary delta should exist
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT))
                .contains(DefaultAccountStateStore.PHASE_REWARDS);

        // Rollback to before boundary slot
        store.rollbackToSlot(BOUNDARY_SLOT - 1);

        // Reward should be reverted
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);

        // Boundary delta should be gone
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).isEmpty();
    }

    @Test
    void multiplePhases_allUndoneByRollback() throws Exception {
        registerCredential(0, CRED_HASH, BigInteger.ZERO);

        // Phase 1: rewards at BOUNDARY_SLOT
        List<DefaultAccountStateStore.DeltaOp> rewardOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(3000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, rewardOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, rewardOps);
            rocks.db().write(wo, batch);
        }

        // Phase 2: pool refund at same slot
        List<DefaultAccountStateStore.DeltaOp> poolOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(8000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, poolOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_POOLREAP, batch, poolOps);
            // Set META_LAST_APPLIED_SLOT
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }

        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(8000));
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).hasSize(2);

        // Rollback undoes both phases
        store.rollbackToSlot(BOUNDARY_SLOT - 1);
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).isEmpty();
    }

    @Test
    void sameKey_multipleWritesInOneBatch_usePendingValueAndRollbackCorrectly() throws Exception {
        registerCredential(0, CRED_HASH, BigInteger.valueOf(1000));

        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        var overlay = new DefaultAccountStateStore.BatchStateOverlay();

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);

            byte[] firstVal = store.getStateWithOverlay(acctKey, overlay);
            var firstAcct = AccountStateCborCodec.decodeStakeAccount(firstVal);
            store.putStateWithDelta(acctKey,
                    TestCborHelper.encodeStakeAccount(firstAcct.reward().add(BigInteger.valueOf(200)), firstAcct.deposit()),
                    batch, deltaOps, overlay);

            byte[] secondVal = store.getStateWithOverlay(acctKey, overlay);
            var secondAcct = AccountStateCborCodec.decodeStakeAccount(secondVal);
            store.putStateWithDelta(acctKey,
                    TestCborHelper.encodeStakeAccount(secondAcct.reward().add(BigInteger.valueOf(300)), secondAcct.deposit()),
                    batch, deltaOps, overlay);

            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }

        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(1500));

        store.rollbackToSlot(BOUNDARY_SLOT - 1);
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(1000));
    }

    // --- Recovery simulation: step marker missing but boundary delta committed ---

    @Test
    void recovery_skipsPhase_whenBoundaryDeltaExists_butStepMarkerMissing() throws Exception {
        // Simulate: rewards committed (boundary delta exists) but step marker NOT written (crash)
        registerCredential(0, CRED_HASH, BigInteger.valueOf(1000));

        // Write boundary delta for PHASE_REWARDS
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(6000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, deltaOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            rocks.db().write(wo, batch);
        }

        // META_BOUNDARY_STEP is NOT set (simulating crash before step marker write)
        int lastStep = store.getBoundaryStep(1); // epoch 1
        assertThat(lastStep).isEqualTo(-1); // no step marker

        // But boundary delta evidence exists
        Set<Byte> committed = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(committed).contains(DefaultAccountStateStore.PHASE_REWARDS);

        // Recovery logic: resumeFromStep should advance past STEP_REWARDS
        int resumeFromStep = EpochBoundaryProcessor.STEP_STARTED;
        if (lastStep >= EpochBoundaryProcessor.STEP_STARTED && lastStep < EpochBoundaryProcessor.STEP_COMPLETE) {
            resumeFromStep = lastStep + 1;
        }
        // Before delta check: would resume from STEP_STARTED (rerun everything = double-apply!)
        assertThat(resumeFromStep).isEqualTo(EpochBoundaryProcessor.STEP_STARTED);

        // Apply delta evidence
        if (committed.contains(DefaultAccountStateStore.PHASE_GOV_RATIFY)) {
            resumeFromStep = Math.max(resumeFromStep, EpochBoundaryProcessor.STEP_GOVERNANCE + 1);
        } else if (committed.contains(DefaultAccountStateStore.PHASE_POOLREAP)) {
            resumeFromStep = Math.max(resumeFromStep, EpochBoundaryProcessor.STEP_POOLREAP + 1);
        } else if (committed.contains(DefaultAccountStateStore.PHASE_REWARDS)) {
            resumeFromStep = Math.max(resumeFromStep, EpochBoundaryProcessor.STEP_REWARDS + 1);
        }

        // After delta check: skips rewards (would resume from STEP_SNAPSHOT)
        assertThat(resumeFromStep).isEqualTo(EpochBoundaryProcessor.STEP_REWARDS + 1);

        // Balance is still the credited value (not double-applied)
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(6000));
    }

    @Test
    void recovery_rerunsPhase_whenNoBoundaryDelta_andNoStepMarker() {
        // No boundary delta, no step marker → normal full rerun
        int lastStep = store.getBoundaryStep(1);
        assertThat(lastStep).isEqualTo(-1);

        Set<Byte> committed = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(committed).isEmpty();

        // resumeFromStep stays at STEP_STARTED → full rerun (correct behavior)
        int resumeFromStep = EpochBoundaryProcessor.STEP_STARTED;
        assertThat(resumeFromStep).isEqualTo(EpochBoundaryProcessor.STEP_STARTED);
    }

    @Test
    void recovery_governancePhase_skipped_whenPhaseGovRatifyCommitted() throws Exception {
        // Simulate governance Phase 2 committed but step marker missing
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT,
                    new byte[]{0x42}, null));
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_GOV_RATIFY, batch, deltaOps);
            rocks.db().write(wo, batch);
        }

        Set<Byte> committed = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(committed).contains(DefaultAccountStateStore.PHASE_GOV_RATIFY);

        // Recovery should skip up to STEP_GOVERNANCE
        int resumeFromStep = EpochBoundaryProcessor.STEP_STARTED;
        if (committed.contains(DefaultAccountStateStore.PHASE_GOV_RATIFY)) {
            resumeFromStep = Math.max(resumeFromStep, EpochBoundaryProcessor.STEP_GOVERNANCE + 1);
        }
        assertThat(resumeFromStep).isEqualTo(EpochBoundaryProcessor.STEP_GOVERNANCE + 1);
    }

    @Test
    void recovery_govEnactAlone_doesNotSkipGovernance() throws Exception {
        // Only PHASE_GOV_ENACT committed (Phase 1 done, Phase 2 crashed)
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT,
                    new byte[]{0x42}, null));
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_GOV_ENACT, batch, deltaOps);
            rocks.db().write(wo, batch);
        }

        Set<Byte> committed = store.getCommittedBoundaryPhases(BOUNDARY_SLOT);
        assertThat(committed).contains(DefaultAccountStateStore.PHASE_GOV_ENACT);
        assertThat(committed).doesNotContain(DefaultAccountStateStore.PHASE_GOV_RATIFY);

        // PHASE_GOV_ENACT alone should NOT skip STEP_GOVERNANCE
        int resumeFromStep = EpochBoundaryProcessor.STEP_STARTED;
        if (committed.contains(DefaultAccountStateStore.PHASE_GOV_RATIFY)) {
            resumeFromStep = Math.max(resumeFromStep, EpochBoundaryProcessor.STEP_GOVERNANCE + 1);
        }
        // Still at STEP_STARTED — governance will be rerun (correct)
        assertThat(resumeFromStep).isEqualTo(EpochBoundaryProcessor.STEP_STARTED);
    }

    // --- Same-key multi-phase undo order ---

    @Test
    void sameKey_twoPhases_rollbackRestoresOriginalValue() throws Exception {
        // MIR credits 1000 to account, then REWARDS credits 5000 (overwriting to 5000).
        // Correct rollback must undo REWARDS first (restore to 1000), then MIR (restore to 0).
        // Wrong order would leave intermediate value 1000.
        registerCredential(0, CRED_HASH, BigInteger.ZERO);
        byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);

        // Phase 1 (MIR): 0 → 1000
        List<DefaultAccountStateStore.DeltaOp> mirOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(1000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, mirOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_MIR, batch, mirOps);
            rocks.db().write(wo, batch);
        }
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(1000));

        // Phase 2 (REWARDS): 1000 → 5000
        List<DefaultAccountStateStore.DeltaOp> rewardOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(5000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, rewardOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, rewardOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(5000));

        // Rollback — must restore to 0 (pre-MIR), not 1000 (intermediate)
        store.rollbackToSlot(BOUNDARY_SLOT - 1);
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);
    }

    @Test
    void adaPot_rewardsThenGovernance_rollbackRestoresPreRewardValue() throws Exception {
        int epoch = 1;
        byte[] adaPotKey = DefaultAccountStateStore.adaPotKey(epoch);

        // Phase 1 (REWARDS): creates AdaPot with treasury=100000
        var rewardPot = new AccountStateCborCodec.AdaPot(
                BigInteger.valueOf(100_000), BigInteger.valueOf(900_000),
                BigInteger.ZERO, BigInteger.valueOf(2000),
                BigInteger.valueOf(5000), BigInteger.ZERO,
                BigInteger.valueOf(7000), BigInteger.valueOf(7000));
        List<DefaultAccountStateStore.DeltaOp> rewardOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(rewardPot), batch, rewardOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, rewardOps);
            rocks.db().write(wo, batch);
        }
        assertThat(readAdaPot(epoch).treasury()).isEqualTo(BigInteger.valueOf(100_000));

        // Phase 2 (GOV_RATIFY): adjusts treasury to 95000
        var govPot = new AccountStateCborCodec.AdaPot(
                BigInteger.valueOf(95_000), BigInteger.valueOf(900_000),
                BigInteger.ZERO, BigInteger.valueOf(2000),
                BigInteger.valueOf(5000), BigInteger.ZERO,
                BigInteger.valueOf(7000), BigInteger.valueOf(7000));
        List<DefaultAccountStateStore.DeltaOp> govOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(govPot), batch, govOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_GOV_RATIFY, batch, govOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }
        assertThat(readAdaPot(epoch).treasury()).isEqualTo(BigInteger.valueOf(95_000));

        // Rollback — must restore to null (pre-reward), not 100000 (intermediate)
        store.rollbackToSlot(BOUNDARY_SLOT - 1);
        assertAdaPotAbsent(epoch);
    }

    // --- AdaPot atomicity with phase commit ---

    @Test
    void rewardPhase_adaPotInSameBatch_bothPresentAfterCommit() throws Exception {
        // Simulate the real commit path: reward credits + AdaPot in same batch
        registerCredential(0, CRED_HASH, BigInteger.ZERO);
        int epoch = 1;

        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Reward credit
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(5000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, deltaOps);

            // AdaPot in same batch
            byte[] adaPotKey = DefaultAccountStateStore.adaPotKey(epoch);
            var pot = new AccountStateCborCodec.AdaPot(
                    BigInteger.valueOf(100_000), BigInteger.valueOf(900_000),
                    BigInteger.ZERO, BigInteger.valueOf(2000),
                    BigInteger.valueOf(5000), BigInteger.ZERO,
                    BigInteger.valueOf(7000), BigInteger.valueOf(7000));
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(pot), batch, deltaOps);

            // Boundary delta journal
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);

            // Set META_LAST_APPLIED_SLOT
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());

            rocks.db().write(wo, batch);
        }
        // NO step marker written (simulating crash)

        // Both reward and AdaPot are present
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(5000));
        assertAdaPotPresent(epoch);

        // Boundary delta evidence exists → recovery would skip rewards
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT))
                .contains(DefaultAccountStateStore.PHASE_REWARDS);
        // Step marker missing
        assertThat(store.getBoundaryStep(epoch)).isEqualTo(-1);
    }

    @Test
    void rewardPhase_adaPotInSameBatch_bothUndoneByRollback() throws Exception {
        // Same setup as above
        registerCredential(0, CRED_HASH, BigInteger.ZERO);
        int epoch = 1;

        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
            byte[] newVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(5000), BigInteger.ZERO);
            store.putStateWithDelta(acctKey, newVal, batch, deltaOps);

            byte[] adaPotKey = DefaultAccountStateStore.adaPotKey(epoch);
            var pot = new AccountStateCborCodec.AdaPot(
                    BigInteger.valueOf(100_000), BigInteger.valueOf(900_000),
                    BigInteger.ZERO, BigInteger.valueOf(2000),
                    BigInteger.valueOf(5000), BigInteger.ZERO,
                    BigInteger.valueOf(7000), BigInteger.valueOf(7000));
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(pot), batch, deltaOps);

            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_REWARDS, batch, deltaOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }

        // Verify both present
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(5000));
        assertAdaPotPresent(epoch);

        // Rollback to before boundary
        store.rollbackToSlot(BOUNDARY_SLOT - 1);

        // Both reward and AdaPot are undone
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);
        assertAdaPotAbsent(epoch);
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).isEmpty();
    }

    @Test
    void governancePhase_adaPotAdjustmentInSameBatch_presentAfterCommit() throws Exception {
        int epoch = 1;

        // Pre-store an initial AdaPot (from reward phase)
        byte[] adaPotKey = DefaultAccountStateStore.adaPotKey(epoch);
        var initialPot = new AccountStateCborCodec.AdaPot(
                BigInteger.valueOf(100_000), BigInteger.valueOf(900_000),
                BigInteger.ZERO, BigInteger.valueOf(2000),
                BigInteger.valueOf(5000), BigInteger.ZERO,
                BigInteger.valueOf(7000), BigInteger.valueOf(7000));
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(), adaPotKey, AccountStateCborCodec.encodeAdaPot(initialPot));
            rocks.db().write(wo, batch);
        }

        // Simulate governance Phase 2: adjust treasury + commit boundary delta atomically
        BigInteger treasuryDelta = BigInteger.valueOf(-5000); // treasury withdrawal
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Read current pot, adjust, write back via delta-aware helper
            var adjustedPot = new AccountStateCborCodec.AdaPot(
                    initialPot.treasury().add(treasuryDelta), initialPot.reserves(),
                    initialPot.deposits(), initialPot.fees(), initialPot.distributed(),
                    initialPot.undistributed(), initialPot.rewardsPot(), initialPot.poolRewardsPot());
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(adjustedPot), batch, deltaOps);

            // Some governance state mutation
            deltaOps.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT,
                    new byte[]{0x42, 0x01}, null));

            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_GOV_RATIFY, batch, deltaOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }
        // NO step marker (simulating crash)

        // Adjusted AdaPot is present
        var storedPot = readAdaPot(epoch);
        assertThat(storedPot).isNotNull();
        assertThat(storedPot.treasury()).isEqualTo(BigInteger.valueOf(95_000)); // 100000 - 5000

        // Boundary delta evidence exists
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT))
                .contains(DefaultAccountStateStore.PHASE_GOV_RATIFY);
    }

    @Test
    void governancePhase_adaPotAdjustment_undoneByRollback() throws Exception {
        int epoch = 1;

        // Pre-store initial AdaPot
        byte[] adaPotKey = DefaultAccountStateStore.adaPotKey(epoch);
        var initialPot = new AccountStateCborCodec.AdaPot(
                BigInteger.valueOf(100_000), BigInteger.valueOf(900_000),
                BigInteger.ZERO, BigInteger.valueOf(2000),
                BigInteger.valueOf(5000), BigInteger.ZERO,
                BigInteger.valueOf(7000), BigInteger.valueOf(7000));
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(), adaPotKey, AccountStateCborCodec.encodeAdaPot(initialPot));
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT - 1).array());
            rocks.db().write(wo, batch);
        }

        // Governance Phase 2 with treasury adjustment
        List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            var adjustedPot = new AccountStateCborCodec.AdaPot(
                    BigInteger.valueOf(95_000), initialPot.reserves(),
                    initialPot.deposits(), initialPot.fees(), initialPot.distributed(),
                    initialPot.undistributed(), initialPot.rewardsPot(), initialPot.poolRewardsPot());
            store.putStateWithDelta(adaPotKey, AccountStateCborCodec.encodeAdaPot(adjustedPot), batch, deltaOps);
            store.commitBoundaryDelta(BOUNDARY_SLOT, DefaultAccountStateStore.PHASE_GOV_RATIFY, batch, deltaOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(BOUNDARY_SLOT + 100).array());
            rocks.db().write(wo, batch);
        }

        // Verify adjusted
        assertThat(readAdaPot(epoch).treasury()).isEqualTo(BigInteger.valueOf(95_000));

        // Rollback
        store.rollbackToSlot(BOUNDARY_SLOT - 1);

        // AdaPot reverted to original
        var reverted = readAdaPot(epoch);
        assertThat(reverted).isNotNull();
        assertThat(reverted.treasury()).isEqualTo(BigInteger.valueOf(100_000));
        assertThat(store.getCommittedBoundaryPhases(BOUNDARY_SLOT)).isEmpty();
    }

    @Test
    void interleavedBlockAndBoundaryRollback_restoresPreWithdrawalRewardBalance() throws Exception {
        byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
        byte[] initialVal = TestCborHelper.encodeStakeAccount(BigInteger.valueOf(1000), BigInteger.ZERO);
        registerCredential(0, CRED_HASH, BigInteger.valueOf(1000));

        // Block delta after target slot: reward withdrawal 1000 -> 0 at slot 200.
        writeBlockDelta(10L, 200L, List.of(
                new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT, acctKey, initialVal)
        ), TestCborHelper.encodeStakeAccount(BigInteger.ZERO, BigInteger.ZERO));
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);

        // Later boundary phase uses the post-withdrawal state (0) as its prev value.
        List<DefaultAccountStateStore.DeltaOp> rewardOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            store.putStateWithDelta(acctKey,
                    TestCborHelper.encodeStakeAccount(BigInteger.valueOf(500), BigInteger.ZERO), batch, rewardOps);
            store.commitBoundaryDelta(300L, DefaultAccountStateStore.PHASE_REWARDS, batch, rewardOps);
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(350L).array());
            rocks.db().write(wo, batch);
        }
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(500));

        // Rollback must undo boundary slot 300 first, then block slot 200.
        store.rollbackToSlot(150L);
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(1000));
    }

    @Test
    void equalSlot_blockDeltaUndoneBeforeBoundaryDelta() throws Exception {
        byte[] acctKey = DefaultAccountStateStore.accountKey(0, CRED_HASH);
        registerCredential(0, CRED_HASH, BigInteger.ZERO);

        // Boundary phase at slot 200: reward credit 0 -> 1000.
        List<DefaultAccountStateStore.DeltaOp> rewardOps = new ArrayList<>();
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            store.putStateWithDelta(acctKey,
                    TestCborHelper.encodeStakeAccount(BigInteger.valueOf(1000), BigInteger.ZERO), batch, rewardOps);
            store.commitBoundaryDelta(200L, DefaultAccountStateStore.PHASE_REWARDS, batch, rewardOps);
            rocks.db().write(wo, batch);
        }
        assertRewardBalance(0, CRED_HASH, BigInteger.valueOf(1000));

        // Block at the same slot happens after the boundary transition: withdraw 1000 -> 0.
        writeBlockDelta(11L, 200L, List.of(
                new DefaultAccountStateStore.DeltaOp(
                        DefaultAccountStateStore.OP_PUT,
                        acctKey,
                        TestCborHelper.encodeStakeAccount(BigInteger.valueOf(1000), BigInteger.ZERO))
        ), TestCborHelper.encodeStakeAccount(BigInteger.ZERO, BigInteger.ZERO));
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(250L).array());
            rocks.db().write(wo, batch);
        }

        // Rollback must undo block first (0 -> 1000), then boundary (1000 -> 0).
        store.rollbackToSlot(150L);
        assertRewardBalance(0, CRED_HASH, BigInteger.ZERO);
    }

    // --- Helpers ---

    private void registerCredential(int credType, String credHash, BigInteger initialReward) throws Exception {
        byte[] key = DefaultAccountStateStore.accountKey(credType, credHash);
        byte[] val = TestCborHelper.encodeStakeAccount(initialReward, BigInteger.ZERO);
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            batch.put(rocks.cfState(), key, val);
            rocks.db().write(wo, batch);
        }
    }

    private void assertRewardBalance(int credType, String credHash, BigInteger expected) throws Exception {
        byte[] key = DefaultAccountStateStore.accountKey(credType, credHash);
        byte[] val = rocks.db().get(rocks.cfState(), key);
        assertThat(val).isNotNull();
        var acct = AccountStateCborCodec.decodeStakeAccount(val);
        assertThat(acct.reward()).isEqualTo(expected);
    }

    private void assertAdaPotPresent(int epoch) throws Exception {
        byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
        byte[] val = rocks.db().get(rocks.cfState(), key);
        assertThat(val).as("AdaPot for epoch %d should be present", epoch).isNotNull();
    }

    private void assertAdaPotAbsent(int epoch) throws Exception {
        byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
        byte[] val = rocks.db().get(rocks.cfState(), key);
        assertThat(val).as("AdaPot for epoch %d should be absent", epoch).isNull();
    }

    private AccountStateCborCodec.AdaPot readAdaPot(int epoch) throws Exception {
        byte[] key = DefaultAccountStateStore.adaPotKey(epoch);
        byte[] val = rocks.db().get(rocks.cfState(), key);
        if (val == null) return null;
        return AccountStateCborCodec.decodeAdaPot(val);
    }

    private void writeBlockDelta(long blockNo, long slot, List<DefaultAccountStateStore.DeltaOp> ops, byte[] currentVal)
            throws Exception {
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (ops.size() == 1 && currentVal != null) {
                batch.put(rocks.cfState(), ops.get(0).key(), currentVal);
            }

            byte[] deltaKey = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
            batch.put(rocks.cfDelta(), deltaKey, encodeDelta(slot, ops));
            batch.put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(slot).array());
            rocks.db().write(wo, batch);
        }
    }

    private byte[] encodeDelta(long slot, List<DefaultAccountStateStore.DeltaOp> ops) {
        int size = 8 + 4;
        for (DefaultAccountStateStore.DeltaOp op : ops) {
            size += 1 + 2 + op.key().length + 2 + (op.prevValue() != null ? op.prevValue().length : 0);
        }

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(slot);
        buf.putInt(ops.size());
        for (DefaultAccountStateStore.DeltaOp op : ops) {
            buf.put(op.opType());
            buf.putShort((short) op.key().length);
            buf.put(op.key());
            if (op.prevValue() != null) {
                buf.putShort((short) op.prevValue().length);
                buf.put(op.prevValue());
            } else {
                buf.putShort((short) 0);
            }
        }
        return buf.array();
    }
}
