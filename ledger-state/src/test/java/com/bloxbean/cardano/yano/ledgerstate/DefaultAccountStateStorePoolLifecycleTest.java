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
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Disabled("ADR-050: enabled at Phase 1 gate")
    void retirementThenRegistrationInSameTransactionCancelsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build(),
                poolRegistration());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
    }

    @Test
    @Disabled("ADR-050: enabled at Phase 1 gate")
    void retirementThenRegistrationInLaterTransactionOfSameBlockCancelsRetirement() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        applyBlock(2, epochStartSlot(11), List.of(
                transaction(PoolRetirement.builder()
                        .poolKeyHash(POOL_HASH).epoch(12).build()),
                transaction(poolRegistration())));

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
    @Disabled("ADR-050: enabled at Phase 1 gate")
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
    @Disabled("ADR-050: enabled at Phase 1 gate")
    void freshRegistrationUsesRegistrationEpochDepositAndReregistrationPreservesIt() {
        applyBlockWithCerts(1, epochStartSlot(10), poolRegistration());

        BigInteger registrationDeposit = epochParams().getPoolDeposit(10);
        assertThat(store.getPoolDeposit(POOL_HASH)).contains(registrationDeposit);

        applyBlockWithCerts(2, epochStartSlot(11), poolRegistration());

        assertThat(store.getPoolDeposit(POOL_HASH)).contains(registrationDeposit);
    }

    @Test
    @Disabled("ADR-050: enabled at Phase 2 gate")
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
        applyBlockWithCerts(1, epochStartSlot(10),
                setupCertificates.toArray(Certificate[]::new));
        applyBlockWithCerts(2, epochStartSlot(11),
                PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

        var calculator = new EpochRewardCalculator(
                rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
        calculator.setLedgerStateProvider(store);
        calculator.setAccountStateStore(store);
        calculator.beginRewardBatch(12, "pool-reap");
        calculator.processPoolDepositRefunds(12);
        calculator.commitRewardBatch(epochStartSlot(12),
                DefaultAccountStateStore.PHASE_POOLREAP);

        assertThat(store.isPoolRegistered(POOL_HASH)).isFalse();
        assertThat(store.getPoolRetirementEpoch(POOL_HASH)).isEmpty();
        for (String credentialHash : CREDENTIAL_HASHES) {
            assertThat(store.getDelegatedPool(0, credentialHash)).isEmpty();
        }
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
}
