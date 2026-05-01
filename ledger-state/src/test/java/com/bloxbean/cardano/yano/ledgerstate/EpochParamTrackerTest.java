package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EpochParamTracker with RocksDB-backed persistence.
 * Validates pending update durability, restart recovery, and rollback correctness.
 */
class EpochParamTrackerTest {

    @TempDir Path tempDir;

    private RocksDB db;
    private ColumnFamilyHandle cfEpochParams;
    private List<ColumnFamilyHandle> allHandles;

    /** Stub base provider — returns defaults for all params. */
    private static final EpochParamProvider BASE_PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
        @Override public int getProtocolMajor(long epoch) { return 2; }
        @Override public int getProtocolMinor(long epoch) { return 0; }
    };

    @BeforeEach
    void setUp() throws Exception {
        RocksDB.loadLibrary();

        List<ColumnFamilyDescriptor> cfDescriptors = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor(AccountStateCfNames.EPOCH_PARAMS.getBytes())
        );

        allHandles = new ArrayList<>();
        DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        db = RocksDB.open(dbOptions, tempDir.resolve("testdb").toString(), cfDescriptors, allHandles);
        cfEpochParams = allHandles.get(1);
    }

    @AfterEach
    void tearDown() {
        for (ColumnFamilyHandle h : allHandles) h.close();
        db.close();
    }

    private EpochParamTracker createTracker() {
        return new EpochParamTracker(BASE_PROVIDER, true, db, cfEpochParams);
    }

    private EpochParamTracker createTracker(EpochParamProvider provider, EraProvider eraProvider) {
        EpochParamTracker tracker = new EpochParamTracker(provider, true, db, cfEpochParams);
        tracker.setEraProvider(eraProvider);
        return tracker;
    }

    private static EraProvider directStartEraProvider(Era startEra) {
        return new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return startEra.getValue() >= eraValue ? 0 : null;
            }
        };
    }

    private static Map<String, Object> orderedCostModel(Long... values) {
        Map<String, Object> model = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            model.put("cost-" + i, values[i]);
        }
        return model;
    }

    private void commitBatch(WriteBatch batch) throws RocksDBException {
        try (WriteOptions wo = new WriteOptions()) {
            db.write(wo, batch);
        }
    }

    /**
     * Build a TransactionBody with a pre-Conway Update proposing param changes.
     *
     * @param proposalEpoch The epoch in the Update (effective epoch = proposalEpoch + 1)
     * @param update        The proposed parameter changes
     */
    private static TransactionBody txWithUpdate(int proposalEpoch, ProtocolParamUpdate update) {
        return TransactionBody.builder()
                .update(Update.builder()
                        .epoch(proposalEpoch)
                        .protocolParamUpdates(Map.of("genesis-key-1", update))
                        .build())
                .build();
    }

    // ===== Test 1: pending update survives restart and finalizes =====

    @Test
    @DisplayName("Pending update persisted via WriteBatch survives restart and finalizes correctly")
    void pendingUpdateSurvivesRestartAndFinalizes() throws Exception {
        // Epoch 5 proposal at slot 518600: d=0, protocolMajor=4
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .protocolMajorVer(4)
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        // Process and commit
        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx, 518600, 0, batch);
            commitBatch(batch);
        }

        // Simulate restart: create a new tracker from the same RocksDB
        EpochParamTracker restarted = createTracker();

        // Before finalize: no resolved params for epoch 6
        assertThat(restarted.getResolvedParams(6)).isNull();

        // Finalize epoch 6 (the effective epoch for proposal epoch 5)
        restarted.finalizeEpoch(6);

        // Verify epoch 6 has the update
        ProtocolParamUpdate resolved = restarted.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getDecentralisationParam().safeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(4);
    }

    // ===== Test 2: rollback before update tx removes pending =====

    @Test
    @DisplayName("Rollback before update slot removes pending key, finalize sees no update")
    void rollbackBeforeUpdateRemovesPending() throws Exception {
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .protocolMajorVer(4)
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx, 518600, 0, batch);
            commitBatch(batch);
        }

        // Rollback to slot 518599 (before the update tx at 518600)
        try (WriteBatch batch = new WriteBatch()) {
            tracker.addRollbackOps(518599, 5, batch);
            commitBatch(batch);
        }
        tracker.reloadAfterRollback();

        // Finalize epoch 6 — the update should be gone
        tracker.finalizeEpoch(6);

        // epoch 6 should contain the carried base snapshot, not the rolled-back update.
        ProtocolParamUpdate resolved = tracker.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(2);
    }

    // ===== Test 3: rollback after update but before boundary keeps pending =====

    @Test
    @DisplayName("Rollback after update slot but before epoch boundary keeps pending, finalize applies it")
    void rollbackAfterUpdateBeforeBoundaryKeepsPending() throws Exception {
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .protocolMajorVer(4)
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx, 518600, 0, batch);
            commitBatch(batch);
        }

        // Rollback to slot 600000 (after update at 518600, still in epoch 5 before boundary)
        try (WriteBatch batch = new WriteBatch()) {
            tracker.addRollbackOps(600000, 5, batch);
            commitBatch(batch);
        }
        tracker.reloadAfterRollback();

        // Finalize epoch 6 — the pending update should still be present
        tracker.finalizeEpoch(6);

        ProtocolParamUpdate resolved = tracker.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getDecentralisationParam().safeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(4);
    }

    // ===== Test 4: rollback before boundary removes finalized but can re-apply from pending =====

    @Test
    @DisplayName("Rollback removes finalized params, retained pending key re-finalizes correctly")
    void rollbackBeforeBoundaryRemovesFinalizedButCanReapplyPending() throws Exception {
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .protocolMajorVer(4)
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx, 518600, 0, batch);
            commitBatch(batch);
        }

        // Finalize epoch 6
        tracker.finalizeEpoch(6);
        assertThat(tracker.getResolvedParams(6)).isNotNull();
        assertThat(tracker.getResolvedParams(6).getProtocolMajorVer()).isEqualTo(4);

        // Rollback to slot 600000, epoch 5 — removes finalized epoch 6
        try (WriteBatch batch = new WriteBatch()) {
            tracker.addRollbackOps(600000, 5, batch);
            commitBatch(batch);
        }
        tracker.reloadAfterRollback();

        // Finalized epoch 6 key should be gone
        assertThat(tracker.getResolvedParams(6)).isNull();

        // Re-finalize epoch 6 — retained pending key should re-apply
        tracker.finalizeEpoch(6);
        ProtocolParamUpdate resolved = tracker.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getDecentralisationParam().safeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(4);
    }

    // ===== Test 5: pending keys ignored after finalized epoch on reload =====

    @Test
    @DisplayName("Pending keys for already-finalized epoch are ignored on reload")
    void pendingKeysIgnoredAfterFinalizedEpochReload() throws Exception {
        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .protocolMajorVer(4)
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx, 518600, 0, batch);
            commitBatch(batch);
        }

        // Finalize epoch 6 — pending key remains in RocksDB, finalized key also written
        tracker.finalizeEpoch(6);

        // Simulate restart
        EpochParamTracker restarted = createTracker();

        // Epoch 6 should be loaded from finalized key, not re-entered as pending
        assertThat(restarted.getResolvedParams(6)).isNotNull();
        assertThat(restarted.getResolvedParams(6).getProtocolMajorVer()).isEqualTo(4);

        // Calling finalizeEpoch(6) again should not change anything
        restarted.finalizeEpoch(6);
        assertThat(restarted.getResolvedParams(6).getProtocolMajorVer()).isEqualTo(4);
    }

    // ===== Test 6: multiple tx updates merge deterministically =====

    @Test
    @DisplayName("Multiple tx updates for same effective epoch merge deterministically by key order")
    void multipleTxUpdatesMergeDeterministically() throws Exception {
        // First tx: protocolMajor=3
        ProtocolParamUpdate update1 = ProtocolParamUpdate.builder()
                .protocolMajorVer(3)
                .build();
        TransactionBody tx1 = txWithUpdate(5, update1);

        // Second tx (later slot): protocolMajor=4, nOpt=150
        ProtocolParamUpdate update2 = ProtocolParamUpdate.builder()
                .protocolMajorVer(4)
                .nOpt(150)
                .build();
        TransactionBody tx2 = txWithUpdate(5, update2);

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(tx1, 518600, 0, batch);
            tracker.processTransaction(tx2, 518700, 0, batch);
            commitBatch(batch);
        }

        // Simulate restart — pending keys load in RocksDB iteration order
        EpochParamTracker restarted = createTracker();
        restarted.finalizeEpoch(6);

        ProtocolParamUpdate resolved = restarted.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        // Later key (slot 518700) wins for conflicting field protocolMajorVer
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(4);
        // nOpt only in second update
        assertThat(resolved.getNOpt()).isEqualTo(150);
    }

    // ===== Test 7: Conway finalized params removed on rollback =====

    @Test
    @DisplayName("Conway enacted params beyond target epoch are removed on rollback")
    void conwayFinalizedParamsRemovedOnRollback() throws Exception {
        EpochParamTracker tracker = createTracker();

        // Simulate Conway governance enactment at epoch 100
        ProtocolParamUpdate conwayUpdate = ProtocolParamUpdate.builder()
                .govActionLifetime(8)
                .nOpt(600)
                .build();
        tracker.applyEnactedParamChange(100, conwayUpdate);

        // Verify epoch 100 has the update
        assertThat(tracker.getResolvedParams(100)).isNotNull();
        assertThat(tracker.getResolvedParams(100).getGovActionLifetime()).isEqualTo(8);
        assertThat(tracker.getResolvedParams(100).getNOpt()).isEqualTo(600);

        // Rollback to epoch 99 (some slot in epoch 99)
        try (WriteBatch batch = new WriteBatch()) {
            tracker.addRollbackOps(42_940_800, 99, batch);
            commitBatch(batch);
        }
        tracker.reloadAfterRollback();

        // Epoch 100 finalized params should be gone
        assertThat(tracker.getResolvedParams(100)).isNull();
    }

    @Test
    @DisplayName("Governance-enacted params are persisted through the caller batch")
    void conwayEnactedParamsUseCallerBatch() throws Exception {
        ProtocolParamUpdate conwayUpdate = ProtocolParamUpdate.builder()
                .govActionLifetime(8)
                .nOpt(600)
                .build();

        EpochParamTracker tracker = createTracker();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.applyEnactedParamChange(100, conwayUpdate, batch);
            // Intentionally do not commit: the update must not be durable outside
            // the governance boundary batch.
        }

        EpochParamTracker notCommitted = createTracker();
        assertThat(notCommitted.getResolvedParams(100)).isNull();

        try (WriteBatch batch = new WriteBatch()) {
            notCommitted.applyEnactedParamChange(100, conwayUpdate, batch);
            commitBatch(batch);
        }

        EpochParamTracker committed = createTracker();
        assertThat(committed.getResolvedParams(100)).isNotNull();
        assertThat(committed.getResolvedParams(100).getGovActionLifetime()).isEqualTo(8);
        assertThat(committed.getResolvedParams(100).getNOpt()).isEqualTo(600);
    }

    // ===== Test 8: applyBlock persists pending via WriteBatch (integration) =====

    @Test
    @DisplayName("Pending param update persisted atomically through block WriteBatch")
    void blockApplyPersistsPendingInSameBatch() throws Exception {
        // This test simulates the DefaultAccountStateStore.applyBlock() path:
        // the caller creates a WriteBatch, calls processTransaction with it,
        // then commits. After restart, the pending update is recoverable.

        EpochParamTracker tracker = createTracker();

        ProtocolParamUpdate update = ProtocolParamUpdate.builder()
                .protocolMajorVer(4)
                .decentralisationParam(new UnitInterval(BigInteger.ZERO, BigInteger.ONE))
                .build();
        TransactionBody tx = txWithUpdate(5, update);

        // Simulate applyBlock: create batch, process tx, add other ops, commit
        try (WriteBatch batch = new WriteBatch()) {
            // The batch would also contain cfState/cfDelta ops from applyBlock,
            // but for this test we only care about the epoch param path.
            tracker.processTransaction(tx, 518600, 0, batch);

            // Simulate another tx in same block (no update)
            TransactionBody noUpdateTx = TransactionBody.builder().build();
            tracker.processTransaction(noUpdateTx, 518600, 1, batch);

            commitBatch(batch);
        }

        // Simulate restart before epoch boundary
        EpochParamTracker restarted = createTracker();

        // The pending update should have been recovered from RocksDB
        restarted.finalizeEpoch(6);

        ProtocolParamUpdate resolved = restarted.getResolvedParams(6);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getProtocolMajorVer()).isEqualTo(4);
        assertThat(resolved.getDecentralisationParam().safeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Full snapshots preserve base params and merge cost models by Plutus language")
    void fullSnapshotsMergeCostModelsByLanguage() throws Exception {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public int getProtocolMajor(long epoch) { return 2; }
            @Override public int getProtocolMinor(long epoch) { return 0; }
            @Override public Map<String, Object> getCostModels(long epoch) {
                return Map.of("PlutusV1", orderedCostModel(1L, 2L), "PlutusV3", List.of(5L, 6L));
            }
            @Override public Map<String, Object> getAlonzoCostModels(long epoch) {
                return Map.of("PlutusV1", orderedCostModel(1L, 2L));
            }
            @Override public Map<String, Object> getConwayCostModels(long epoch) { return Map.of("PlutusV3", List.of(5L, 6L)); }
            @Override public BigInteger getCoinsPerUtxoWord(long epoch) { return BigInteger.valueOf(34_482); }
        };
        EraProvider eraProvider = new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return switch (eraValue) {
                    case 5 -> 10; // Alonzo
                    case 6 -> 20; // Babbage
                    case 7 -> 30; // Conway
                    default -> 0;
                };
            }
        };

        EpochParamTracker tracker = createTracker(provider, eraProvider);

        tracker.finalizeEpoch(5);
        assertThat(tracker.getCostModels(5)).isNull();
        assertThat(tracker.getCostModelsRaw(5)).isNull();

        tracker.finalizeEpoch(10);
        assertThat(tracker.getCostModels(10)).containsOnlyKeys("PlutusV1");
        assertThat(tracker.getCostModels(10).get("PlutusV1"))
                .isEqualTo(Map.of("000", 1L, "001", 2L));
        assertThat(tracker.getCostModelsRaw(10)).containsEntry("PlutusV1", List.of(1L, 2L));
        assertThat(tracker.getResolvedParams(10).getExpansionRate().getNumerator()).isEqualTo(BigInteger.valueOf(3));
        assertThat(tracker.getResolvedParams(10).getExpansionRate().getDenominator()).isEqualTo(BigInteger.valueOf(1000));

        ProtocolParamUpdate plutusV2Update = ProtocolParamUpdate.builder()
                .costModels(Map.of(1, "[3,4]"))
                .build();
        try (WriteBatch batch = new WriteBatch()) {
            tracker.processTransaction(txWithUpdate(24, plutusV2Update), 1234, 0, batch);
        }
        tracker.finalizeEpoch(25);
        assertThat(tracker.getCostModels(25)).containsOnlyKeys("PlutusV1", "PlutusV2");
        assertThat(tracker.getCostModelsRaw(25)).containsEntry("PlutusV2", List.of(3L, 4L));

        tracker.finalizeEpoch(30);
        assertThat(tracker.getCostModels(30)).containsOnlyKeys("PlutusV1", "PlutusV2", "PlutusV3");
        assertThat(tracker.getCostModelsRaw(30)).containsEntry("PlutusV3", List.of(5L, 6L));
    }

    @Test
    @DisplayName("Era-cleared fields do not fall back to genesis values")
    void eraClearedFieldsDoNotFallbackToGenesis() throws Exception {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public int getProtocolMajor(long epoch) { return 6; }
            @Override public int getProtocolMinor(long epoch) { return 0; }
            @Override public String getExtraEntropy(long epoch) { return "genesis-entropy"; }
            @Override public BigInteger getMinUtxo(long epoch) { return BigInteger.valueOf(1_000_000); }
            @Override public BigDecimal getDecentralization(long epoch) { return BigDecimal.ONE; }
            @Override public UnitInterval getDecentralizationInterval(long epoch) {
                return new UnitInterval(BigInteger.ONE, BigInteger.ONE);
            }
            @Override public BigInteger getCoinsPerUtxoWord(long epoch) { return BigInteger.valueOf(34_482); }
        };
        EraProvider eraProvider = new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return switch (eraValue) {
                    case 5 -> 10; // Alonzo
                    case 6 -> 20; // Babbage
                    default -> null;
                };
            }
        };

        EpochParamTracker tracker = createTracker(provider, eraProvider);

        tracker.finalizeEpoch(10);
        assertThat(tracker.getMinUtxo(10)).isNull();
        assertThat(tracker.getExtraEntropy(10)).isEqualTo("genesis-entropy");
        assertThat(tracker.getDecentralization(10)).isEqualByComparingTo(BigDecimal.ONE);

        tracker.finalizeEpoch(20);
        assertThat(tracker.getMinUtxo(20)).isNull();
        assertThat(tracker.getExtraEntropy(20)).isNull();
        assertThat(tracker.getDecentralization(20)).isNull();
    }

    @Test
    @DisplayName("Direct Conway start materializes Alonzo, Babbage, and Conway overlays at genesis")
    void directConwayStartMaterializesAllRequiredOverlays() throws Exception {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public int getProtocolMajor(long epoch) { return 10; }
            @Override public int getProtocolMinor(long epoch) { return 0; }
            @Override public String getExtraEntropy(long epoch) { return "genesis-entropy"; }
            @Override public BigInteger getMinUtxo(long epoch) { return BigInteger.valueOf(1_000_000); }
            @Override public BigDecimal getDecentralization(long epoch) { return BigDecimal.ONE; }
            @Override public UnitInterval getDecentralizationInterval(long epoch) {
                return new UnitInterval(BigInteger.ONE, BigInteger.ONE);
            }
            @Override public Map<String, Object> getAlonzoCostModels(long epoch) {
                return Map.of("PlutusV1", List.of(1L, 2L), "PlutusV2", List.of(3L, 4L));
            }
            @Override public Map<String, Object> getConwayCostModels(long epoch) {
                return Map.of("PlutusV3", List.of(5L, 6L));
            }
            @Override public BigInteger getCoinsPerUtxoWord(long epoch) { return BigInteger.valueOf(34_482); }
            @Override public int getGovActionLifetime(long epoch) { return 6; }
        };

        EpochParamTracker tracker = createTracker(provider, directStartEraProvider(Era.Conway));

        tracker.finalizeEpoch(0);

        assertThat(tracker.getCostModels(0)).containsOnlyKeys("PlutusV1", "PlutusV2", "PlutusV3");
        assertThat(tracker.getMinUtxo(0)).isNull();
        assertThat(tracker.getExtraEntropy(0)).isNull();
        assertThat(tracker.getDecentralization(0)).isNull();
        assertThat(tracker.getCoinsPerUtxoSize(0)).isEqualTo(BigInteger.valueOf(4_310));
        assertThat(tracker.getGovActionLifetime(0)).isEqualTo(6);
    }

    @Test
    void bootstrapEpochIfNeeded_materializesGenesisSnapshotOnce() throws Exception {
        EpochParamTracker tracker = createTracker();

        assertThat(tracker.bootstrapEpochIfNeeded(0)).isTrue();
        assertThat(tracker.getResolvedParams(0)).isNotNull();
        assertThat(tracker.getProtocolMajor(0)).isEqualTo(2);
        assertThat(tracker.bootstrapEpochIfNeeded(0)).isFalse();

        EpochParamTracker restarted = createTracker();
        assertThat(restarted.getResolvedParams(0)).isNotNull();
        assertThat(restarted.bootstrapEpochIfNeeded(0)).isFalse();
    }

    @Test
    void bootstrapEpochIfNeeded_beforeFirstNonByronEpochIsNoOp() throws Exception {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public long getEpochLength() { return 432000; }
            @Override public long getByronSlotsPerEpoch() { return 21600; }
            @Override public long getShelleyStartSlot() { return 86400; }
        };
        EpochParamTracker tracker = new EpochParamTracker(provider, true, db, cfEpochParams);

        assertThat(tracker.bootstrapEpochIfNeeded(0)).isFalse();
        assertThat(tracker.getResolvedParams(0)).isNull();
    }

    @Test
    void bootstrapEpochIfNeeded_afterFirstNonByronEpochIsNoOp() throws Exception {
        EpochParamTracker tracker = createTracker();

        assertThat(tracker.bootstrapEpochIfNeeded(42)).isFalse();
        assertThat(tracker.getResolvedParams(42)).isNull();
    }
}
