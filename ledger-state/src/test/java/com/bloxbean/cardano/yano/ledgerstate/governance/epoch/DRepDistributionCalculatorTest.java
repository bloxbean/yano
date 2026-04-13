package com.bloxbean.cardano.yano.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.TestCborHelper;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DRepDistributionCalculator using real RocksDB.
 * Populates RocksDB with known delegations and DRep states, then verifies
 * the computed distribution matches expected values.
 */
class DRepDistributionCalculatorTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore govStore;
    private DRepDistributionCalculator calculator;

    // Real preprod DRep hashes
    static final String DREP_A = "03ccae794affbe27a5f5f74da6266002db11daa6ae446aea783b972d";
    static final String DREP_B = "232ab6c11464fcdeb92b69f8f0958c1349b44a732b85248e4371caba";
    // Stake credential hashes
    static final String CRED1 = "aabbccdd11223344aabbccdd11223344aabbccdd11223344aabbccdd";
    static final String CRED2 = "11223344aabbccdd11223344aabbccdd11223344aabbccdd11223344";
    static final String CRED3 = "ffeeddcc99887766ffeeddcc99887766ffeeddcc99887766ffeeddcc";

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        govStore = rocks.governanceStore();
        calculator = new DRepDistributionCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(), govStore);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    /**
     * Store a DRep delegation: PREFIX_DREP_DELEG (0x03) + credType + credHash → drepType + drepHash + slot
     */
    private void storeDRepDelegation(int credType, String credHash, int drepType, String drepHash, long slot) throws Exception {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hashBytes.length];
        key[0] = DefaultAccountStateStore.PREFIX_DREP_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, hashBytes.length);

        byte[] val = TestCborHelper.encodeDRepDelegation(drepType, drepHash, slot, 0, 0);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, val);
            commit(batch);
        }
    }

    /**
     * Store a stake account: PREFIX_ACCT (0x01) + credType + credHash → reward + deposit
     */
    private void storeStakeAccount(int credType, String credHash, BigInteger reward, BigInteger deposit) throws Exception {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hashBytes.length];
        key[0] = DefaultAccountStateStore.PREFIX_ACCT;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, hashBytes.length);

        byte[] val = TestCborHelper.encodeStakeAccount(reward, deposit);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, val);
            commit(batch);
        }
    }

    /**
     * Register a DRep in governance store.
     */
    private void registerDRep(int drepType, String drepHash, int epoch, long slot) throws Exception {
        var state = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                epoch, null, epoch + 20, true, slot, 10, null);
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(drepType, drepHash, state, batch, new ArrayList<>());
            commit(batch);
        }
    }

    // ===== Tests =====

    @Test
    @DisplayName("Basic distribution: two delegators to one DRep")
    void basic_twoDelegatorsOneDRep() throws Exception {
        // Register DRep A
        registerDRep(0, DREP_A, 200, 84974395L);

        // Two credentials delegate to DRep A
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeDRepDelegation(0, CRED2, 0, DREP_A, 85000001L);

        // Both credentials have registered stake accounts
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));
        storeStakeAccount(0, CRED2, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        // Provide UTXO balances
        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000),
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED2), BigInteger.valueOf(50_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist).containsKey(key);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(150_000_000)); // 100M + 50M
    }

    @Test
    @DisplayName("Distribution includes rewards in stake amount")
    void distribution_includesRewards() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.valueOf(5_000_000), BigInteger.valueOf(2_000_000)); // reward=5M, deposit=2M

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(10_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(15_000_000)); // 10M utxo + 5M rewards
    }

    @Test
    @DisplayName("Distribution includes spendable reward_rest")
    void distribution_includesRewardRest() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(10_000_000));

        // reward_rest: "credType:credHash" → amount
        var rewardRest = Map.of("0:" + CRED1, BigInteger.valueOf(100_000_000_000L));

        var dist = calculator.calculate(230, utxoBalances, rewardRest);

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(100_010_000_000L)); // 10M + 100B reward_rest
    }

    @Test
    @DisplayName("Deregistered DRep excluded from distribution")
    void deregisteredDRep_excluded() throws Exception {
        // DRep with previousDeregistrationSlot AFTER registration → deregistered
        var deregState = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                200, null, 220, false, 84974395L, 10, 85000000L); // prevDeregSlot > regSlot
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_B, deregState, batch, new ArrayList<>());
            commit(batch);
        }

        storeDRepDelegation(0, CRED1, 0, DREP_B, 85000001L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_B);
        assertThat(dist).doesNotContainKey(key);
    }

    @Test
    @DisplayName("Unregistered stake credential excluded from distribution")
    void unregisteredCredential_excluded() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED3, 0, DREP_A, 85000000L);
        // No storeStakeAccount for CRED3 → unregistered

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED3), BigInteger.valueOf(100_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        // DRep A should not be in distribution since its only delegator is unregistered
        assertThat(dist.getOrDefault(key, BigInteger.ZERO)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("Zero-balance DRep still included in distribution (with 0 amount)")
    void zeroBalanceDRep_included() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        // UTXO balance is 0
        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.ZERO);

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist).containsKey(key);
        assertThat(dist.get(key)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("Multiple DReps with different delegators")
    void multipleDReps_differentDelegators() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        registerDRep(0, DREP_B, 181, 76909405L);

        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeDRepDelegation(0, CRED2, 0, DREP_B, 85000001L);

        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));
        storeStakeAccount(0, CRED2, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000),
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED2), BigInteger.valueOf(200_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        assertThat(dist.get(new DRepDistributionCalculator.DRepDistKey(0, DREP_A))).isEqualTo(BigInteger.valueOf(100_000_000));
        assertThat(dist.get(new DRepDistributionCalculator.DRepDistKey(0, DREP_B))).isEqualTo(BigInteger.valueOf(200_000_000));
    }

    /**
     * Verifies that a credential delegated to a deregistered DRep is excluded from
     * distribution. In normal operation, the cleanup on DRep deregistration would have
     * already cleared this delegation. This test verifies the tombstone filter as a
     * secondary defense: even if a delegation persists, resolveDRepKey() returns null
     * because the DRep is not in activeDReps.
     * <p>
     * Haskell origin/master ConwayUnRegDRep clears delegations via drepDelegs, AND
     * computeDRepDistr checks {@code Map.member cred regDReps}. Both layers exclude it.
     */
    @Test
    @DisplayName("Delegation to deregistered DRep excluded from distribution without cleanup")
    void delegationToDeregisteredDRep_excludedWithoutCleanup() throws Exception {
        // Register DRep A, then deregister it (simulated by storing with prevDeregSlot > registeredAtSlot)
        var deregisteredState = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                507, null, 527, true, 136000000L, 10, 150000000L); // prevDeregSlot=150M > registeredAt=136M
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_A, deregisteredState, batch, new ArrayList<>());
            commit(batch);
        }

        // Credential Y has a forward delegation to deregistered DRep A (persists in RocksDB)
        storeDRepDelegation(0, CRED1, 0, DREP_A, 137000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1),
                BigInteger.valueOf(500_000_000_000L));

        var dist = calculator.calculate(540, utxoBalances, Map.of());

        // DRep A should NOT be in distribution — it's deregistered.
        // resolveDRepKey() returns null, so Y's 500B stake is NOT counted.
        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.getOrDefault(key, BigInteger.ZERO)).isEqualTo(BigInteger.ZERO);
    }

    /**
     * Verifies that after credential X re-delegates from DRep A to DRep B, and DRep A
     * deregisters, X's stake is correctly counted toward DRep B.
     * <p>
     * This is the root cause scenario of the 15 PV10 mismatches: in PV9, the stale
     * reverse entry A→{X} caused cleanup to clear X→B on A's deregistration. After the
     * PV10 hardfork reverse-index rebuild, the stale entry is removed (X now delegates
     * to B, not A), so A's cleanup does not touch X→B.
     */
    @Test
    @DisplayName("Re-delegated credential counted toward new DRep after old DRep deregisters")
    void reDelegatedCredential_countedTowardNewDRep_afterOldDRepDeregisters() throws Exception {
        // DRep A: deregistered (prevDeregSlot > registeredAtSlot)
        var deregisteredA = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                507, null, 527, true, 136000000L, 10, 150000000L);
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_A, deregisteredA, batch, new ArrayList<>());
            commit(batch);
        }

        // DRep B: registered and active
        registerDRep(0, DREP_B, 507, 136000001L);

        // Credential X has forward delegation X→B (re-delegated from A to B before A deregistered)
        // The forward delegation points to B, which is the current state after re-delegation
        storeDRepDelegation(0, CRED1, 0, DREP_B, 140000000L);
        storeStakeAccount(0, CRED1, BigInteger.valueOf(1_000_000), BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1),
                BigInteger.valueOf(1_700_000_000_000L)); // ~1.7T — matches the 1c12c18f case

        var dist = calculator.calculate(562, utxoBalances, Map.of());

        // DRep A should NOT be in distribution (deregistered)
        assertThat(dist.getOrDefault(new DRepDistributionCalculator.DRepDistKey(0, DREP_A), BigInteger.ZERO))
                .isEqualTo(BigInteger.ZERO);

        // DRep B SHOULD have X's stake (forward delegation X→B survives A's deregistration)
        var keyB = new DRepDistributionCalculator.DRepDistKey(0, DREP_B);
        assertThat(dist.get(keyB)).isEqualTo(BigInteger.valueOf(1_700_001_000_000L)); // 1.7T utxo + 1M reward
    }

    /**
     * Verifies that the defensive timing guard filters delegations made before a DRep's
     * previous deregistration. If DRep X deregisters (prevDeregSlot=200) and re-registers
     * (registeredAtSlot=300), a delegation at slot 100 (before deregistration) is filtered.
     * <p>
     * The DRep deregistration cleanup should have already cleared this delegation via the
     * reverse index. The timing guard is a defensive safety net for Yano's tombstone model.
     * Haskell's DRep distribution only checks {@code Map.member cred regDReps} and does not
     * have this guard; correctness comes from cleanup clearing the delegation at deregistration.
     */
    @Test
    @DisplayName("Delegation before DRep re-registration filtered by defensive timing guard")
    void delegationBeforeReRegistration_filteredByTimingGuard() throws Exception {
        // DRep X: deregistered at slot 200, re-registered at slot 300
        var reRegisteredState = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                507, null, 527, true, 300L, 10, 200L); // registeredAt=300, prevDeregSlot=200
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_A, reRegisteredState, batch, new ArrayList<>());
            commit(batch);
        }

        // Credential A delegated at slot 100 — BEFORE the deregistration at slot 200
        // In practice, cleanup would have cleared this. The timing guard is defensive backup.
        storeDRepDelegation(0, CRED1, 0, DREP_A, 100L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1),
                BigInteger.valueOf(50_000_000));

        var dist = calculator.calculate(540, utxoBalances, Map.of());

        // Timing guard: delegSlot=100 <= prevDeregSlot=200 → filtered
        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.getOrDefault(key, BigInteger.ZERO)).isEqualTo(BigInteger.ZERO);
    }

    /**
     * Verifies that re-delegation from X to Y is preserved when X deregisters.
     * After PV10 reverse-index rebuild, X's reverse entry for this credential is removed
     * (credential now delegates to Y, not X). So X's deregistration cleanup does not
     * touch the credential's forward delegation to Y.
     */
    @Test
    @DisplayName("Re-delegation to Y survives when old DRep X deregisters")
    void reDelegation_survivesOldDRepDeregistration() throws Exception {
        // DRep X: deregistered (tombstone)
        var deregisteredX = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                507, null, 527, true, 136000000L, 10, 150000000L);
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_A, deregisteredX, batch, new ArrayList<>());
            commit(batch);
        }

        // DRep Y: registered and active
        registerDRep(0, DREP_B, 507, 136000001L);

        // A delegates to X then re-delegates to Y — forward delegation now points to Y
        storeDRepDelegation(0, CRED1, 0, DREP_B, 140000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1),
                BigInteger.valueOf(100_000_000));

        var dist = calculator.calculate(562, utxoBalances, Map.of());

        // X should NOT be in distribution (deregistered tombstone)
        assertThat(dist.getOrDefault(new DRepDistributionCalculator.DRepDistKey(0, DREP_A), BigInteger.ZERO))
                .isEqualTo(BigInteger.ZERO);

        // Y SHOULD have A's stake
        assertThat(dist.get(new DRepDistributionCalculator.DRepDistKey(0, DREP_B)))
                .isEqualTo(BigInteger.valueOf(100_000_000));
    }
}
