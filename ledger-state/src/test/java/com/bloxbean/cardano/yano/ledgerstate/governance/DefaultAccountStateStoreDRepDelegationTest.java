package com.bloxbean.cardano.yano.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.TestCborHelper;
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
 * Tests for DRep deregistration cleanup and PV10 reverse-index rebuild.
 * <p>
 * Haskell origin/master model:
 * <ul>
 *   <li>PV9: drepDelegs has stale entries; ConwayUnRegDRep clears via stale set (over-clears)</li>
 *   <li>PV10 hardfork: updateDRepDelegations rebuilds drepDelegs from forward delegations</li>
 *   <li>PV10+: ConwayUnRegDRep clears via clean drepDelegs (correct)</li>
 * </ul>
 * Yano replicates this via PREFIX_DREP_DELEG_REVERSE + rebuildDRepDelegReverseIndex.
 * <p>
 * Uses real RocksDB via {@link TestRocksDBHelper}.
 */
class DefaultAccountStateStoreDRepDelegationTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore govStore;

    static final String DREP_A = "8ae1a94d6e4a0fa64476ddffd3495973987a062d0a615597c1d79ac7";
    static final String DREP_B = "1c12c18fff75038f1181d6dcca10c5255df430301c0a264a9500381e";
    static final String DREP_C = "b102197ee2affaebd50fcb8ca69fb4fa9eba931f4cc219f18db6d7e6";
    static final String CRED_X = "df74e3532e6740fc5a9b6cc3515808178264cda1c3e9672cd3ade4af";
    static final String CRED_Y = "9aba7c04ece28b5f20dd4afe574b31aeb98d2c299052eba0976084b2";

    private static final byte PREFIX_DREP_DELEG_REVERSE = 0x04;

    /** EpochParamProvider that returns PV10 for all epochs. */
    private static final EpochParamProvider PV10_PROVIDER = new EpochParamProvider() {
        @Override public int getProtocolMajor(long epoch) { return 10; }
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
    };

    /** EpochParamProvider that returns PV9 for all epochs. */
    private static final EpochParamProvider PV9_PROVIDER = new EpochParamProvider() {
        @Override public int getProtocolMajor(long epoch) { return 9; }
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
    };

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        govStore = rocks.governanceStore();
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    // -- Helpers --

    private void storeForwardDelegation(int credType, String credHash,
                                         int drepType, String drepHash, long slot) throws Exception {
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

    private byte[] readForwardDelegation(int credType, String credHash) throws Exception {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hashBytes.length];
        key[0] = DefaultAccountStateStore.PREFIX_DREP_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, hashBytes.length);
        return rocks.db().get(rocks.cfState(), key);
    }

    private void storeReverseEntry(int drepType, String drepHash,
                                    int credType, String credHash) throws Exception {
        byte[] drepBytes = HexUtil.decodeHexString(drepHash);
        byte[] credBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + 28 + 1 + 28];
        key[0] = PREFIX_DREP_DELEG_REVERSE;
        key[1] = (byte) drepType;
        System.arraycopy(drepBytes, 0, key, 2, 28);
        key[30] = (byte) credType;
        System.arraycopy(credBytes, 0, key, 31, 28);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, new byte[]{1});
            commit(batch);
        }
    }

    private boolean reverseEntryExists(int drepType, String drepHash,
                                        int credType, String credHash) throws Exception {
        byte[] drepBytes = HexUtil.decodeHexString(drepHash);
        byte[] credBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + 28 + 1 + 28];
        key[0] = PREFIX_DREP_DELEG_REVERSE;
        key[1] = (byte) drepType;
        System.arraycopy(drepBytes, 0, key, 2, 28);
        key[30] = (byte) credType;
        System.arraycopy(credBytes, 0, key, 31, 28);
        return rocks.db().get(rocks.cfState(), key) != null;
    }

    private void registerDRep(int drepType, String drepHash, int epoch, long slot) throws Exception {
        var state = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                epoch, null, epoch + 20, true, slot, 10, null);
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(drepType, drepHash, state, batch, new ArrayList<>());
            commit(batch);
        }
    }

    // ===== Test B: PV10 rebuild removes stale entries =====

    @Test
    @DisplayName("PV10 rebuild removes stale reverse entry, preserves correct forward delegation")
    void pv10Rebuild_removesStaleReverse_preservesForward() throws Exception {
        // DRep A: registered, DRep B: registered
        registerDRep(0, DREP_A, 507, 133000000L);
        registerDRep(0, DREP_B, 507, 133000001L);

        // Forward: X→B (credential re-delegated from A to B)
        storeForwardDelegation(0, CRED_X, 0, DREP_B, 140000000L);

        // Stale reverse: A→{X} (PV9 didn't remove on re-delegation)
        storeReverseEntry(0, DREP_A, 0, CRED_X);

        // Build registered set
        Set<String> registered = Set.of("0:" + DREP_A, "0:" + DREP_B);

        // Run PV10 rebuild
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Use reflection-free approach: call the public method with a mock EpochParamProvider
            // that returns PV10
            // Actually, we need to test the private rebuildDRepDelegReverseIndex directly.
            // Since it's in DefaultAccountStateStore, let's create a minimal store instance.
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    org.slf4j.LoggerFactory.getLogger(DefaultAccountStateStore.class), true);
            store.rebuildDRepDelegReverseIndexIfNeeded(537, registered, PV10_PROVIDER);
        }

        // After rebuild: stale A→{X} should be gone, B→{X} should exist
        assertThat(reverseEntryExists(0, DREP_A, 0, CRED_X))
                .as("Stale reverse A→X should be removed").isFalse();
        assertThat(reverseEntryExists(0, DREP_B, 0, CRED_X))
                .as("Correct reverse B→X should be added").isTrue();

        // Forward X→B should survive
        byte[] fwd = readForwardDelegation(0, CRED_X);
        assertThat(fwd).isNotNull();
        var deleg = TestCborHelper.decodeDRepDelegation(fwd);
        assertThat(deleg.drepHash()).isEqualTo(DREP_B);
    }

    // ===== Test C: PV10 rebuild removes dangling forward delegations =====

    @Test
    @DisplayName("PV10 rebuild deletes dangling forward delegation to non-existent DRep")
    void pv10Rebuild_deletesDanglingForwardDelegation() throws Exception {
        // DRep C: NOT registered (no entry in governance store)
        // Forward: Y→C (dangling — DRep C doesn't exist)
        storeForwardDelegation(0, CRED_Y, 0, DREP_C, 135000000L);

        // Register only A and B (not C)
        Set<String> registered = Set.of("0:" + DREP_A, "0:" + DREP_B);

        var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    org.slf4j.LoggerFactory.getLogger(DefaultAccountStateStore.class), true);
        store.rebuildDRepDelegReverseIndexIfNeeded(537, registered, PV10_PROVIDER);

        // Forward Y→C should be deleted (dangling)
        assertThat(readForwardDelegation(0, CRED_Y))
                .as("Dangling forward delegation Y→C should be deleted").isNull();
    }

    // ===== Test E: Idempotence =====

    @Test
    @DisplayName("PV10 rebuild is idempotent — running twice produces same result")
    void pv10Rebuild_idempotent() throws Exception {
        registerDRep(0, DREP_A, 507, 133000000L);
        storeForwardDelegation(0, CRED_X, 0, DREP_A, 140000000L);
        storeReverseEntry(0, DREP_A, 0, CRED_X); // will be rebuilt

        Set<String> registered = Set.of("0:" + DREP_A);

        // First rebuild (marker will be written)
        var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    org.slf4j.LoggerFactory.getLogger(DefaultAccountStateStore.class), true);
        store.rebuildDRepDelegReverseIndexIfNeeded(537, registered, PV10_PROVIDER);

        // Verify state after first rebuild
        assertThat(reverseEntryExists(0, DREP_A, 0, CRED_X)).isTrue();
        byte[] fwd1 = readForwardDelegation(0, CRED_X);
        assertThat(fwd1).isNotNull();

        // Second rebuild — should be skipped (marker exists)
        store.rebuildDRepDelegReverseIndexIfNeeded(538, registered, PV10_PROVIDER);

        // State unchanged
        assertThat(reverseEntryExists(0, DREP_A, 0, CRED_X)).isTrue();
        byte[] fwd2 = readForwardDelegation(0, CRED_X);
        assertThat(fwd2).isNotNull();
        assertThat(fwd2).isEqualTo(fwd1);
    }

    @Test
    @DisplayName("PV9 same-tx redelegation before old DRep unregistration preserves new forward delegation")
    void pv9SameTxRedelegation_thenOldDRepUnreg_preservesNewDelegation() throws Exception {
        String oldDRep = CRED_X; // mirror preview case: old DRep credential hash equals delegator hash
        var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                org.slf4j.LoggerFactory.getLogger(DefaultAccountStateStore.class), true, PV9_PROVIDER);

        applyBlockWithCerts(store, 1, epochStartSlot(734),
                RegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .coin(BigInteger.valueOf(2_000_000))
                        .build(),
                RegDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, CRED_X))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build(),
                RegDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DREP_B))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build(),
                VoteDelegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .drep(Drep.addrKeyHash(oldDRep))
                        .build());

        applyBlockWithCerts(store, 2, epochStartSlot(734) + 27,
                VoteDelegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .drep(Drep.addrKeyHash(DREP_B))
                        .build(),
                UnregDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, oldDRep))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build());

        byte[] fwd = readForwardDelegation(0, CRED_X);
        assertThat(fwd).isNotNull();
        var deleg = TestCborHelper.decodeDRepDelegation(fwd);
        assertThat(deleg.drepType()).isEqualTo(0);
        assertThat(deleg.drepHash()).isEqualTo(DREP_B);

        assertThat(reverseEntryExists(0, DREP_B, 0, CRED_X)).isTrue();
    }

    @Test
    @DisplayName("PV9 stale reverse entry must not clear current delegation on later old-DRep unregistration")
    void pv9StaleReverse_thenLaterOldDRepUnreg_preservesCurrentDelegation() throws Exception {
        var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                org.slf4j.LoggerFactory.getLogger(DefaultAccountStateStore.class), true, PV9_PROVIDER);

        applyBlockWithCerts(store, 1, epochStartSlot(705),
                RegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .coin(BigInteger.valueOf(2_000_000))
                        .build(),
                RegDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DREP_A))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build(),
                RegDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DREP_B))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build(),
                VoteDelegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .drep(Drep.addrKeyHash(DREP_A))
                        .build());

        applyBlockWithCerts(store, 2, epochStartSlot(705) + 100,
                VoteDelegCert.builder()
                        .stakeCredential(stakeCred(CRED_X))
                        .drep(Drep.addrKeyHash(DREP_B))
                        .build());

        applyBlockWithCerts(store, 3, epochStartSlot(724),
                UnregDrepCert.builder()
                        .drepCredential(new Credential(StakeCredType.ADDR_KEYHASH, DREP_A))
                        .coin(BigInteger.valueOf(500_000_000))
                        .build());

        byte[] fwd = readForwardDelegation(0, CRED_X);
        assertThat(fwd).isNotNull();
        var deleg = TestCborHelper.decodeDRepDelegation(fwd);
        assertThat(deleg.drepType()).isEqualTo(0);
        assertThat(deleg.drepHash()).isEqualTo(DREP_B);

        assertThat(reverseEntryExists(0, DREP_B, 0, CRED_X)).isTrue();
    }

    private static StakeCredential stakeCred(String hash) {
        return StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH)
                .hash(hash)
                .build();
    }

    private static void applyBlockWithCerts(DefaultAccountStateStore store, long blockNo, long slot,
                                            Certificate... certs) {
        var txs = new ArrayList<TransactionBody>();
        var tx = TransactionBody.builder()
                .certificates(new ArrayList<>(Arrays.asList(certs)))
                .build();
        txs.add(tx);

        Block block = Block.builder()
                .transactionBodies(txs)
                .build();

        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNo, "hash" + blockNo, block));
    }

    private static long epochStartSlot(int epoch) {
        return epoch * 432000L;
    }
}
