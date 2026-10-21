package org.yanoproject.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ReadOptions;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledgerstate.governance.GovernanceBlockProcessor;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 step 1b: snapshot-bound reads of account, pool, DRep and governance state never see
 * writes committed after the snapshot, reuse the store's encodings, and never write.
 */
class LedgerStateSnapshotReaderTest {

    private static final long EPOCH_LENGTH = 432_000L;
    private static final String CRED = "11".repeat(28);
    private static final String POOL = "aa".repeat(28);
    private static final String VRF_1 = "b1".repeat(32);
    private static final String VRF_2 = "b2".repeat(32);
    private static final String DREP = "dd".repeat(28);
    private static final String TX = "cc".repeat(32);

    @TempDir
    Path tempDir;

    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(LedgerStateSnapshotReaderTest.class), true, params());
        store.setGovernanceBlockProcessor(new GovernanceBlockProcessor(rocks.governanceStore(), params()));
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    @Test
    void accountReadsAreIsolatedFromLaterCommits() throws Exception {
        apply(1, slot(10, 0), RegCert.builder().stakeCredential(stake(CRED)).coin(BigInteger.TWO).build(),
                poolRegistration(VRF_1),
                StakeDelegation.builder().stakeCredential(stake(CRED))
                        .stakePoolId(StakePoolId.builder().poolKeyHash(POOL).build()).build());

        try (Bound bound = bind()) {
            LedgerStateSnapshotReader reader = bound.reader();
            assertThat(reader.stakeAccount(0, CRED)).get()
                    .extracting(AccountStateCborCodec.StakeAccount::deposit).isEqualTo(BigInteger.TWO);
            assertThat(reader.delegatedPool(0, CRED)).contains(POOL);

            // Deregister after the snapshot: the live store changes, the snapshot does not.
            apply(2, slot(10, 5), UnregCert.builder().stakeCredential(stake(CRED)).coin(BigInteger.TWO).build());
            assertThat(store.isStakeCredentialRegistered(0, CRED)).isFalse();
            assertThat(reader.stakeAccount(0, CRED)).isPresent();
            assertThat(reader.delegatedPool(0, CRED)).contains(POOL);
        }
        try (Bound bound = bind()) {
            assertThat(bound.reader().stakeAccount(0, CRED)).isEmpty();
            assertThat(bound.reader().delegatedPool(0, CRED)).isEmpty();
        }
    }

    @Test
    void poolHistorySeparatesActiveFromFutureParameters() throws Exception {
        apply(1, slot(10, 0), poolRegistration(VRF_1));
        apply(2, slot(11, 0), poolRegistration(VRF_2), PoolRetirement.builder().poolKeyHash(POOL).epoch(14).build());

        try (Bound bound = bind()) {
            LedgerStateSnapshotReader reader = bound.reader();
            // The live record holds the latest (future) parameters and the lifecycle deposit.
            assertThat(reader.poolRegistration(POOL)).get()
                    .extracting(AccountStateCborCodec.PoolRegistrationData::vrfKeyHash).isEqualTo(VRF_2);
            // Tip epoch 11: fresh registration in 10 is keyed 12, the re-registration in 11 is keyed 14.
            assertThat(reader.poolParamsHistoryAtOrBefore(POOL, 11 + 2)).get()
                    .extracting(AccountStateCborCodec.PoolRegistrationData::vrfKeyHash).isEqualTo(VRF_1);
            assertThat(reader.hasPoolParamsHistoryRow(POOL, 11 + 3)).isTrue();
            assertThat(reader.poolRetirementEpoch(POOL)).contains(14L);
            assertThat(reader.poolParamsHistoryAtOrBefore("ab".repeat(28), 100)).isEmpty();
        }
    }

    @Test
    void drepAndGovernanceReadsUseTheSnapshot() throws Exception {
        apply(1, slot(10, 0), RegDrepCert.builder()
                .drepCredential(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(DREP).build())
                .coin(BigInteger.valueOf(500)).build());
        GovernanceStateStore gov = rocks.governanceStore();
        GovActionRecord proposal = new GovActionRecord(BigInteger.TEN, "e0" + CRED, 10, 16,
                GovActionType.INFO_ACTION, null, null, new InfoAction(), slot(10, 1));
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> ops = new ArrayList<>();
            gov.storeProposal(new GovActionId(TX, 0), proposal, batch, ops);
            gov.storeConstitution(new GovernanceCborCodec.ConstitutionRecord("u", "aa", "ff".repeat(28)), batch, ops);
            gov.storeNumDormantEpochs(3, batch, ops);
            gov.storeLastEnactedAction(GovActionType.UPDATE_COMMITTEE, TX, 1, batch, ops);
            rocks.db().write(options, batch);
        }

        try (Bound bound = bind()) {
            GovernanceSnapshotReader reader = bound.reader().governance().orElseThrow();
            assertThat(bound.reader().drepDeposit(0, DREP)).contains(BigInteger.valueOf(500));
            assertThat(reader.drepState(0, DREP)).get().extracting(r -> r.active()).isEqualTo(true);
            GovernanceSnapshotReader.StoredProposal stored = reader.proposal(TX, 0).orElseThrow();
            assertThat(stored.record().expiresAfterEpoch()).isEqualTo(16);
            assertThat(stored.govActionCbor()).isNotNull();
            assertThat(reader.proposals()).hasSize(1);
            assertThat(reader.proposal(TX, 1)).isEmpty();
            assertThat(reader.proposal(TX, 70_000)).isEmpty();
            assertThat(reader.constitution()).get()
                    .extracting(GovernanceCborCodec.ConstitutionRecord::scriptHash).isEqualTo("ff".repeat(28));
            assertThat(reader.numDormantEpochs()).isEqualTo(3);
            assertThat(reader.lastEnacted(GovActionType.UPDATE_COMMITTEE)).get()
                    .extracting(GovernanceCborCodec.LastEnactedAction::govActionIndex).isEqualTo(1);

            // A later removal does not reach the snapshot.
            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                gov.removeProposal(new GovActionId(TX, 0), batch, new ArrayList<>());
                gov.storeNumDormantEpochs(0, batch, new ArrayList<>());
                rocks.db().write(options, batch);
            }
            assertThat(reader.proposal(TX, 0)).isPresent();
            assertThat(reader.numDormantEpochs()).isEqualTo(3);
        }
    }

    @Test
    void governanceReaderIsAbsentWhenGovernanceTrackingIsDisabled() {
        DefaultAccountStateStore noGovernance = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(LedgerStateSnapshotReaderTest.class), true, params());
        try (ReadOptions reads = new ReadOptions()) {
            assertThat(noGovernance.snapshotReader(rocks.db(), reads).governance()).isEmpty();
        }
    }

    @Test
    void readerRefusesAForeignDatabaseAndADisabledStore() throws Exception {
        Path otherDir = Files.createDirectories(tempDir.resolve("other"));
        try (TestRocksDBHelper other = TestRocksDBHelper.create(otherDir);
             ReadOptions reads = new ReadOptions()) {
            assertThatThrownBy(() -> store.snapshotReader(other.db(), reads))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("different RocksDB");
            DefaultAccountStateStore disabled = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(LedgerStateSnapshotReaderTest.class), false, params());
            assertThatThrownBy(() -> disabled.snapshotReader(rocks.db(), reads))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private record Bound(TestRocksDBHelper rocks, Snapshot snapshot, ReadOptions reads,
                         LedgerStateSnapshotReader reader) implements AutoCloseable {
        @Override
        public void close() {
            reads.close();
            rocks.db().releaseSnapshot(snapshot);
        }
    }

    private Bound bind() {
        Snapshot snapshot = rocks.db().getSnapshot();
        ReadOptions reads = new ReadOptions().setSnapshot(snapshot);
        return new Bound(rocks, snapshot, reads, store.snapshotReader(rocks.db(), reads));
    }

    private void apply(long blockNumber, long slot, Certificate... certificates) {
        TransactionBody tx = TransactionBody.builder()
                .txHash(String.format("%064x", blockNumber))
                .certificates(new ArrayList<>(Arrays.asList(certificates)))
                .build();
        Block block = Block.builder().transactionBodies(new ArrayList<>(List.of(tx))).build();
        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNumber, "hash" + blockNumber, block));
    }

    private static PoolRegistration poolRegistration(String vrf) {
        return PoolRegistration.builder()
                .poolParams(PoolParams.builder()
                        .operator(POOL)
                        .vrfKeyHash(vrf)
                        .pledge(BigInteger.ONE)
                        .cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount("e0" + CRED)
                        .poolOwners(Set.of(CRED))
                        .build())
                .build();
    }

    private static StakeCredential stake(String hash) {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static long slot(int epoch, long offset) {
        return epoch * EPOCH_LENGTH + offset;
    }

    private static EpochParamProvider params() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.TWO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000L);
            }

            @Override
            public int getDRepActivity(long epoch) {
                return 20;
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 10;
            }
        };
    }
}
