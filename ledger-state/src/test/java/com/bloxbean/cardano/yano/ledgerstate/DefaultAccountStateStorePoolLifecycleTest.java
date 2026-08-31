package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAccountStateStorePoolLifecycleTest {

    private static final long EPOCH_LENGTH = 432_000L;
    private static final String POOL_HASH = "aa".repeat(28);
    private static final String VRF_HASH = "bb".repeat(32);
    private static final List<String> CREDENTIAL_HASHES = List.of(
            "11".repeat(28),
            "22".repeat(28),
            "33".repeat(28),
            "44".repeat(28),
            "55".repeat(28));
    private static final String REWARD_ACCOUNT = "e0" + CREDENTIAL_HASHES.getFirst();

    @TempDir
    Path tempDir;

    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(
                rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(DefaultAccountStateStorePoolLifecycleTest.class),
                true, epochParams());
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    @Test
    void retirementThenRegistrationInSameTransactionCancelsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build(),
                poolRegistration());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
    }

    @Test
    void retirementThenRegistrationInLaterTransactionOfSameBlockCancelsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        applyBlock(2, epochStartSlot(11), List.of(
                transaction(PoolRetirement.builder()
                        .poolKeyHash(POOL_HASH).epoch(12).build()),
                transaction(poolRegistration())));

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
    }

    @Test
    void retirementThenRegistrationInLaterBlockCancelsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());
        applyBlockWithCerts(3, epochStartSlot(11) + 1, poolRegistration());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
    }

    @Test
    void registrationThenRetirementInSameTransactionKeepsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        applyBlockWithCerts(2, epochStartSlot(11),
                poolRegistration(),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).contains(12L);
    }

    @Test
    void registrationThenRetirementInLaterBlockKeepsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        applyBlockWithCerts(2, epochStartSlot(11), poolRegistration());
        applyBlockWithCerts(3, epochStartSlot(11) + 1,
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).contains(12L);
    }

    @Test
    void repeatedRegistrationInSameBlockUsesFreshThenUpdateActivationCadence() {
        BigInteger firstPledge = BigInteger.valueOf(1_000_000_000L);
        BigInteger secondPledge = BigInteger.valueOf(2_000_000_000L);

        applyBlockWithCerts(1, epochStartSlot(10),
                poolRegistration(firstPledge), poolRegistration(secondPledge));

        assertThat(store.getPoolParams(POOL_HASH, 12).orElseThrow().pledge())
                .isEqualTo(firstPledge);
        assertThat(store.getPoolParams(POOL_HASH, 13).orElseThrow().pledge())
                .isEqualTo(secondPledge);
    }

    @Test
    void freshRegistrationUsesRegistrationEpochDepositAndReregistrationPreservesIt() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        BigInteger registrationDeposit = epochParams().getPoolDeposit(10);
        assertThat(store.getPoolDeposit(POOL_HASH)).contains(registrationDeposit);

        applyBlockWithCerts(2, epochStartSlot(11), poolRegistration());

        assertThat(store.getPoolDeposit(POOL_HASH)).contains(registrationDeposit);
    }

    @Test
    void rollbackRestoresPoolLifecycleStateByteForByte() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        Map<String, String> before = poolLifecycleState();

        applyBlock(2, epochStartSlot(11), List.of(
                transaction(PoolRetirement.builder()
                        .poolKeyHash(POOL_HASH).epoch(12).build()),
                transaction(poolRegistration(BigInteger.valueOf(2_000_000_000L)))));
        applyBlockWithCerts(3, epochStartSlot(11) + 1,
                poolRegistration(BigInteger.valueOf(3_000_000_000L)),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(13).build());

        store.rollbackToSlot(epochStartSlot(10));

        assertThat(poolLifecycleState()).isEqualTo(before);
    }

    @Test
    void effectivePoolReapRemovesLivePoolRetirementAndStakeDelegations() throws Exception {
        List<Certificate> setupCertificates = new ArrayList<>();
        setupCertificates.add(poolRegistration());
        for (String credentialHash : CREDENTIAL_HASHES) {
            StakeCredential credential = stakeCredential(credentialHash);
            setupCertificates.add(StakeRegistration.builder()
                    .stakeCredential(credential)
                    .build());
            setupCertificates.add(StakeDelegation.builder()
                    .stakeCredential(credential)
                    .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH).build())
                    .build());
        }
        setupCertificates.add(VoteDelegCert.builder()
                .stakeCredential(stakeCredential(CREDENTIAL_HASHES.getFirst()))
                .drep(Drep.abstain())
                .build());
        applyBlockWithCerts(1, epochStartSlot(10),
                setupCertificates.toArray(Certificate[]::new));
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        EpochRewardCalculator calculator = rewardCalculator();
        PoolReapProcessor.Result result = store.processPoolReap(
                12, epochStartSlot(12), calculator);

        assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
        assertThat(store.getPoolParams(POOL_HASH, 12)).isPresent();
        for (String credentialHash : CREDENTIAL_HASHES) {
            assertThat(store.getDelegatedPool(0, credentialHash)).isEmpty();
        }
        assertThat(store.getDRepDelegation(0, CREDENTIAL_HASHES.getFirst()))
                .isPresent();
        BigInteger expectedRefund = epochParams().getPoolDeposit(10);
        assertThat(result.retiringPools()).isEqualTo(1);
        assertThat(result.delegationRowsExamined()).isEqualTo(5);
        assertThat(result.delegationsRemoved()).isEqualTo(5);
        assertThat(result.poolRowsRemoved()).isEqualTo(3);
        assertThat(result.registeredRefundAmount()).isEqualTo(expectedRefund);
        assertThat(result.unclaimedDepositAmount()).isZero();
        assertThat(store.getRewardBalance(0, CREDENTIAL_HASHES.getFirst()))
                .contains(expectedRefund);
        assertThat(store.isPoolReapInProgress(epochStartSlot(12))).isFalse();
        assertThat(store.getCommittedBoundaryPhases(epochStartSlot(12)))
                .contains(DefaultAccountStateStore.PHASE_POOLREAP);

        store.processPoolReap(12, epochStartSlot(12), calculator);
        assertThat(store.getRewardBalance(0, CREDENTIAL_HASHES.getFirst()))
                .contains(expectedRefund);
    }

    @Test
    void unregisteredPoolRewardCredentialIsReportedWithoutSyntheticRefund() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        PoolReapProcessor.Result result = store.processPoolReap(
                12, epochStartSlot(12), rewardCalculator());

        BigInteger deposit = epochParams().getPoolDeposit(10);
        assertThat(result.registeredRefunds()).isZero();
        assertThat(result.registeredRefundAmount()).isZero();
        assertThat(result.unclaimedDepositAmount()).isEqualTo(deposit);
        assertThat(store.getRewardBalance(0, CREDENTIAL_HASHES.getFirst()))
                .isEmpty();
        assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
    }

    @Test
    void poolReapFailsClosedWhenRetirementHasNoLivePool() throws Exception {
        rocks.db().put(rocks.cfState(),
                DefaultAccountStateStore.poolRetireKey(POOL_HASH),
                AccountStateCborCodec.encodePoolRetirement(12));

        EpochRewardCalculator calculator = rewardCalculator();
        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), calculator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no live pool registration");
        assertThat(store.getCommittedBoundaryPhases(epochStartSlot(12)))
                .doesNotContain(DefaultAccountStateStore.PHASE_POOLREAP);
    }

    @Test
    void poolReapFailsClosedWhenMonetaryProcessorIsUnavailable() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the monetary reward/refund processor");
        assertThat(store.isPoolRegistered(POOL_HASH)).isTrue();
        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).contains(12L);
    }

    @Test
    void poolReapFailsClosedOnMalformedLivePool() throws Exception {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());
        rocks.db().put(rocks.cfState(),
                DefaultAccountStateStore.poolDepositKey(POOL_HASH), new byte[]{1});

        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), rewardCalculator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed live pool registration");
        assertThat(store.isPoolRegistered(POOL_HASH)).isTrue();
        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).contains(12L);
    }

    @Test
    void poolReapProgressMakesCommittedPhaseIncomplete() throws Exception {
        long boundarySlot = epochStartSlot(12);
        var progress = new PoolReapProcessor.Progress(
                12, boundarySlot, PoolReapProcessor.STAGE_DELEGATIONS,
                null, null, 1);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
            store.putStateWithDelta(PoolReapProcessor.META_POOL_REAP_PROGRESS,
                    PoolReapProcessor.encodeProgress(progress), batch, deltaOps);
            store.commitBoundaryDelta(boundarySlot,
                    DefaultAccountStateStore.PHASE_POOLREAP, 0, batch, deltaOps);
            rocks.db().write(options, batch);
        }

        assertThat(store.getCommittedBoundaryPhases(boundarySlot))
                .doesNotContain(DefaultAccountStateStore.PHASE_POOLREAP);

        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
            store.deleteStateWithDelta(PoolReapProcessor.META_POOL_REAP_PROGRESS,
                    batch, deltaOps);
            store.commitBoundaryDelta(boundarySlot,
                    DefaultAccountStateStore.PHASE_POOLREAP, 1, batch, deltaOps);
            rocks.db().write(options, batch);
        }

        assertThat(store.getCommittedBoundaryPhases(boundarySlot))
                .contains(DefaultAccountStateStore.PHASE_POOLREAP);
    }

    @Test
    void poolReapResumesBeforeInitialProgressCommit() {
        assertPoolReapResumes(0, PoolReapProcessor.CommitMoment.BEFORE);
    }

    @Test
    void poolReapResumesAfterInitialProgressCommit() {
        assertPoolReapResumes(0, PoolReapProcessor.CommitMoment.AFTER);
    }

    @Test
    void poolReapResumesBeforeDelegationChunkCommit() {
        assertPoolReapResumes(1, PoolReapProcessor.CommitMoment.BEFORE);
    }

    @Test
    void poolReapResumesAfterDelegationChunkCommit() {
        assertPoolReapResumes(1, PoolReapProcessor.CommitMoment.AFTER);
    }

    @Test
    void poolReapResumesBeforeMiddleDelegationChunkCommit() {
        assertPoolReapResumes(2, PoolReapProcessor.CommitMoment.BEFORE);
    }

    @Test
    void poolReapResumesAfterMiddleDelegationChunkCommit() {
        assertPoolReapResumes(2, PoolReapProcessor.CommitMoment.AFTER);
    }

    @Test
    void poolReapResumesBeforeFinalDelegationChunkCommit() {
        assertPoolReapResumes(3, PoolReapProcessor.CommitMoment.BEFORE);
    }

    @Test
    void poolReapResumesAfterFinalDelegationChunkCommit() {
        assertPoolReapResumes(3, PoolReapProcessor.CommitMoment.AFTER);
    }

    @Test
    void poolReapResumesBeforeFinalPoolCommit() {
        assertPoolReapResumes(4, PoolReapProcessor.CommitMoment.BEFORE);
    }

    @Test
    void poolReapResumesAfterFinalPoolCommit() {
        assertPoolReapResumes(4, PoolReapProcessor.CommitMoment.AFTER);
    }

    @Test
    void archiveWriterAbortsBeforePoolChunkCommitAndCommitsOnceOnResume() {
        assertArchiveStagingAcrossFinalPoolCommit(PoolReapProcessor.CommitMoment.BEFORE,
                1, 1);
    }

    @Test
    void archiveWriterRemainsCommittedWhenCrashFollowsPoolChunkCommit() {
        assertArchiveStagingAcrossFinalPoolCommit(PoolReapProcessor.CommitMoment.AFTER,
                0, 1);
    }

    private void assertPoolReapResumes(
            int interruptedSequence, PoolReapProcessor.CommitMoment moment) {
        store = boundedStore();
        prepareRetiringPool();
        AtomicBoolean interrupted = new AtomicBoolean();
        store.setPoolReapCommitHook(checkpoint -> {
            if (checkpoint.sequence() == interruptedSequence
                    && checkpoint.moment() == moment
                    && interrupted.compareAndSet(false, true)) {
                throw new SimulatedCrash();
            }
        });

        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), rewardCalculator()))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(interrupted).isTrue();

        store = restartedStore();
        store.processPoolReap(12, epochStartSlot(12), rewardCalculator());

        assertPoolReapedExactlyOnce();
    }

    private void assertArchiveStagingAcrossFinalPoolCommit(
            PoolReapProcessor.CommitMoment moment,
            int expectedAborts, int expectedCommits) {
        prepareRetiringPool();
        RecordingArchiveSink archive = new RecordingArchiveSink();
        EpochRewardCalculator interruptedCalculator = rewardCalculator();
        interruptedCalculator.setEpochArchiveStagingSink(archive);
        store.setPoolReapCommitHook(checkpoint -> {
            if (checkpoint.sequence() == 2 && checkpoint.moment() == moment) {
                throw new SimulatedCrash();
            }
        });

        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), interruptedCalculator))
                .isInstanceOf(SimulatedCrash.class);

        store = restartedStore();
        EpochRewardCalculator resumedCalculator = rewardCalculator();
        resumedCalculator.setEpochArchiveStagingSink(archive);
        store.processPoolReap(12, epochStartSlot(12), resumedCalculator);

        assertPoolReapedExactlyOnce();
        assertThat(archive.commits).isEqualTo(expectedCommits);
        assertThat(archive.aborts).isEqualTo(expectedAborts);
        assertThat(archive.facts).singleElement().satisfies(fact -> {
            assertThat(fact.credentialType()).isZero();
            assertThat(fact.credentialHash()).isEqualTo(CREDENTIAL_HASHES.getFirst());
            assertThat(fact.poolHash()).isEqualTo(POOL_HASH);
            assertThat(fact.rewardType()).isEqualTo("REFUND");
            assertThat(fact.earnedEpoch()).isEqualTo(12);
            assertThat(fact.amount()).isEqualTo(epochParams().getPoolDeposit(10));
        });
    }

    @Test
    void rollbackRestoresCompletePoolReapByteForByte() {
        prepareRetiringPool();
        Map<String, String> before = poolReapRelevantState();
        store.processPoolReap(12, epochStartSlot(12), rewardCalculator());

        store.rollbackToSlot(epochStartSlot(12) - 1);

        assertThat(poolReapRelevantState()).isEqualTo(before);
        assertThat(store.getCommittedBoundaryPhases(epochStartSlot(12))).isEmpty();
        assertThat(store.getPoolReapProgress()).isNull();
    }

    @Test
    void rollbackRestoresPartiallyCommittedPoolReapByteForByte() {
        prepareRetiringPool();
        Map<String, String> before = poolReapRelevantState();
        store.setPoolReapCommitHook(checkpoint -> {
            if (checkpoint.sequence() == 1
                    && checkpoint.moment() == PoolReapProcessor.CommitMoment.AFTER) {
                throw new SimulatedCrash();
            }
        });
        assertThatThrownBy(() -> store.processPoolReap(
                12, epochStartSlot(12), rewardCalculator()))
                .isInstanceOf(SimulatedCrash.class);

        store.setPoolReapCommitHook(null);
        store.rollbackToSlot(epochStartSlot(12) - 1);

        assertThat(poolReapRelevantState()).isEqualTo(before);
        assertThat(store.getPoolReapProgress()).isNull();
    }

    @Test
    void interruptedRollbackResumesFromRollbackV1Marker() {
        prepareRetiringPool();
        Map<String, String> before = poolReapRelevantState();
        store.processPoolReap(12, epochStartSlot(12), rewardCalculator());
        AtomicBoolean interrupted = new AtomicBoolean();
        store.setRollbackChunkCommitHook(() -> {
            if (interrupted.compareAndSet(false, true)) throw new SimulatedCrash();
        });

        assertThatThrownBy(() -> store.rollbackToSlot(epochStartSlot(12) - 1))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SimulatedCrash.class);

        store = restartedStore();
        assertThat(poolReapRelevantState()).isEqualTo(before);
        assertThat(store.getPoolReapProgress()).isNull();
    }

    @Test
    void startupRecoveryCompletesPoolReapBeforeReturningReady() {
        prepareRetiringPool();
        long boundarySlot = epochStartSlot(12);
        var boundary = new EpochArchiveStagingSink.Boundary(
                11, 12, boundarySlot, 3);
        store.setBoundaryStarted(boundary);
        store.setBoundaryStep(12, EpochBoundaryProcessor.STEP_SNAPSHOT);
        store.setPoolReapCommitHook(checkpoint -> {
            if (checkpoint.sequence() == 0
                    && checkpoint.moment() == PoolReapProcessor.CommitMoment.AFTER) {
                throw new SimulatedCrash();
            }
        });
        assertThatThrownBy(() -> store.processPoolReap(
                12, boundarySlot, rewardCalculator()))
                .isInstanceOf(SimulatedCrash.class);

        store = restartedStore();
        EpochBoundaryProcessor processor = boundaryProcessor(rewardCalculator());
        processor.recoverInterruptedBoundary();

        assertPoolReapedExactlyOnce();
        assertThat(store.getBoundaryStep(12))
                .isEqualTo(EpochBoundaryProcessor.STEP_COMPLETE);
    }

    @Test
    void startupRecoveryRejectsOrphanedPoolReapProgress() {
        prepareRetiringPool();
        long boundarySlot = epochStartSlot(12);
        store.setPoolReapCommitHook(checkpoint -> {
            if (checkpoint.sequence() == 0
                    && checkpoint.moment() == PoolReapProcessor.CommitMoment.AFTER) {
                throw new SimulatedCrash();
            }
        });
        assertThatThrownBy(() -> store.processPoolReap(
                12, boundarySlot, rewardCalculator()))
                .isInstanceOf(SimulatedCrash.class);

        store = restartedStore();
        EpochBoundaryProcessor processor = boundaryProcessor(rewardCalculator());
        assertThatThrownBy(processor::recoverInterruptedBoundary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no boundary-step state");
        assertThat(store.getPoolReapProgress()).isNotNull();
    }

    private static EpochParamProvider epochParams() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000L);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000L + epoch);
            }
        };
    }

    private EpochRewardCalculator rewardCalculator() {
        var calculator = new EpochRewardCalculator(
                rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
        calculator.setLedgerStateProvider(store);
        calculator.setAccountStateStore(store);
        return calculator;
    }

    private DefaultAccountStateStore restartedStore() {
        return new DefaultAccountStateStore(
                rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(DefaultAccountStateStorePoolLifecycleTest.class),
                true, epochParams());
    }

    private DefaultAccountStateStore boundedStore() {
        return new DefaultAccountStateStore(
                rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(DefaultAccountStateStorePoolLifecycleTest.class),
                true, epochParams(), Map.of(
                YanoPropertyKeys.AccountState.EPOCH_SNAPSHOT_MAX_BATCH_OPERATIONS, 2,
                YanoPropertyKeys.AccountState.EPOCH_SNAPSHOT_MAX_BATCH_BYTES, 512));
    }

    private EpochBoundaryProcessor boundaryProcessor(
            EpochRewardCalculator calculator) {
        var processor = new EpochBoundaryProcessor(
                null, calculator, null, epochParams(), 1L,
                EpochRewardCalculator.resolveNetworkConfig(1L));
        processor.setSnapshotCreator(store);
        return processor;
    }

    private void prepareRetiringPool() {
        List<Certificate> certificates = new ArrayList<>();
        certificates.add(poolRegistration());
        for (String credentialHash : CREDENTIAL_HASHES) {
            StakeCredential credential = stakeCredential(credentialHash);
            certificates.add(StakeRegistration.builder()
                    .stakeCredential(credential)
                    .build());
            certificates.add(StakeDelegation.builder()
                    .stakeCredential(credential)
                    .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH).build())
                    .build());
        }
        certificates.add(VoteDelegCert.builder()
                .stakeCredential(stakeCredential(CREDENTIAL_HASHES.getFirst()))
                .drep(Drep.abstain())
                .build());
        applyBlockWithCerts(1, epochStartSlot(10),
                certificates.toArray(Certificate[]::new));
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());
    }

    private void assertPoolReapedExactlyOnce() {
        assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
        for (String credentialHash : CREDENTIAL_HASHES) {
            assertThat(store.getDelegatedPool(0, credentialHash)).isEmpty();
        }
        assertThat(store.getDRepDelegation(0, CREDENTIAL_HASHES.getFirst()))
                .isPresent();
        assertThat(store.getRewardBalance(0, CREDENTIAL_HASHES.getFirst()))
                .contains(epochParams().getPoolDeposit(10));
        assertThat(store.getPoolReapProgress()).isNull();
    }

    private static StakeCredential stakeCredential(String hash) {
        return StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH)
                .hash(hash)
                .build();
    }

    private static PoolRegistration poolRegistration() {
        return poolRegistration(BigInteger.valueOf(1_000_000_000L));
    }

    private static PoolRegistration poolRegistration(BigInteger pledge) {
        return PoolRegistration.builder()
                .poolParams(PoolParams.builder()
                        .operator(POOL_HASH)
                        .vrfKeyHash(VRF_HASH)
                        .pledge(pledge)
                        .cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount(REWARD_ACCOUNT)
                        .poolOwners(Set.of(CREDENTIAL_HASHES.getFirst()))
                        .build())
                .build();
    }

    private void applyBlockWithCerts(long blockNumber, long slot,
                                     Certificate... certificates) {
        applyBlock(blockNumber, slot, List.of(transaction(certificates)));
    }

    private void applyBlock(long blockNumber, long slot,
                            List<TransactionBody> transactions) {
        Block block = Block.builder()
                .transactionBodies(new ArrayList<>(transactions))
                .build();
        store.applyBlock(new BlockAppliedEvent(
                Era.Conway, slot, blockNumber, "hash" + blockNumber, block));
    }

    private static TransactionBody transaction(Certificate... certificates) {
        return TransactionBody.builder()
                .certificates(new ArrayList<>(Arrays.asList(certificates)))
                .build();
    }

    private static long epochStartSlot(int epoch) {
        return epoch * EPOCH_LENGTH;
    }

    private Map<String, String> poolLifecycleState() {
        Map<String, String> state = new LinkedHashMap<>();
        try (RocksIterator iterator = rocks.db().newIterator(rocks.cfState())) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length > 0 && isPoolLifecyclePrefix(key[0])) {
                    state.put(HexFormat.of().formatHex(key),
                            HexFormat.of().formatHex(iterator.value()));
                }
                iterator.next();
            }
        }
        return state;
    }

    private Map<String, String> poolReapRelevantState() {
        Map<String, String> state = new LinkedHashMap<>();
        try (RocksIterator iterator = rocks.db().newIterator(rocks.cfState())) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length > 0 && isPoolReapRelevantPrefix(key[0])) {
                    state.put(HexFormat.of().formatHex(key),
                            HexFormat.of().formatHex(iterator.value()));
                }
                iterator.next();
            }
        }
        return state;
    }

    private static boolean isPoolLifecyclePrefix(byte prefix) {
        return prefix == DefaultAccountStateStore.PREFIX_POOL_DEPOSIT
                || prefix == DefaultAccountStateStore.PREFIX_POOL_RETIRE
                || prefix == DefaultAccountStateStore.PREFIX_POOL_PARAMS_HIST
                || prefix == DefaultAccountStateStore.PREFIX_POOL_REG_SLOT;
    }

    private static boolean isPoolReapRelevantPrefix(byte prefix) {
        return isPoolLifecyclePrefix(prefix)
                || prefix == DefaultAccountStateStore.PREFIX_ACCT
                || prefix == DefaultAccountStateStore.PREFIX_POOL_DELEG
                || prefix == DefaultAccountStateStore.PREFIX_DREP_DELEG
                || prefix == DefaultAccountStateStore.PREFIX_ACCUMULATED_REWARD;
    }

    private static final class SimulatedCrash extends RuntimeException {
    }

    private static final class RecordingArchiveSink implements EpochArchiveStagingSink {
        private final List<RewardFact> facts = new ArrayList<>();
        private int commits;
        private int aborts;

        @Override
        public boolean enabled(Dataset dataset) {
            return dataset == Dataset.REWARD;
        }

        @Override
        public FactWriter<StakeFact> openStake(int epoch) {
            return discardWriter();
        }

        @Override
        public FactWriter<DrepFact> openDrep(int epoch) {
            return discardWriter();
        }

        @Override
        public FactWriter<AdaPotFact> openAdaPot(int epoch) {
            return discardWriter();
        }

        @Override
        public FactWriter<GovernanceFact> openGovernance(int epoch, String part) {
            return discardWriter();
        }

        @Override
        public FactWriter<RewardFact> openRewards(int epoch, String part) {
            return new FactWriter<>() {
                private final List<RewardFact> staged = new ArrayList<>();
                private boolean completed;

                @Override
                public void append(RewardFact fact) {
                    staged.add(fact);
                }

                @Override
                public void commit() {
                    facts.addAll(staged);
                    commits++;
                    completed = true;
                }

                @Override
                public void abort() {
                    if (!completed) {
                        aborts++;
                        completed = true;
                    }
                }
            };
        }

        private static <T> FactWriter<T> discardWriter() {
            return new FactWriter<>() {
                @Override public void append(T fact) { }
                @Override public void commit() { }
            };
        }
    }
}
