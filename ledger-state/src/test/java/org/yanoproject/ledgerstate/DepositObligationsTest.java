package org.yanoproject.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.account.LedgerStateProvider.DepositObligations;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledgerstate.governance.GovernanceBlockProcessor;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-058: the deposit obligations the epoch boundary stores in the AdaPot, derived from the live records
 * (Haskell {@code allObligations}), and the stake-key deposit charged by a legacy registration.
 */
class DepositObligationsTest {

    private static final long EPOCH_LENGTH = 432_000L;
    private static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000L);
    private static final BigInteger CONWAY_KEY_DEPOSIT = BigInteger.valueOf(3_000_000L);
    private static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000L);
    private static final BigInteger DREP_DEPOSIT = BigInteger.valueOf(500_000_000L);
    private static final BigInteger PROPOSAL_DEPOSIT = BigInteger.valueOf(100_000_000_000L);
    private static final String KEY_A = "a1".repeat(28);
    private static final String KEY_B = "b2".repeat(28);
    private static final String DREP = "d3".repeat(28);
    private static final String POOL = "aa".repeat(28);

    @TempDir
    Path tempDir;

    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(DepositObligationsTest.class), true, params());
        store.setGovernanceBlockProcessor(new GovernanceBlockProcessor(rocks.governanceStore(), params()));
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    @Test
    void obligationsFollowEveryCategoryAndItsRollback() {
        applyBlock(1, epochStart(10), tx("11".repeat(32), List.of(
                reg(KEY_A),
                RegCert.builder().stakeCredential(stake(KEY_B)).coin(CONWAY_KEY_DEPOSIT).build(),
                RegDrepCert.builder().drepCredential(credential(DREP)).coin(DREP_DEPOSIT).build(),
                poolRegistration())));
        var registered = new DepositObligations(KEY_DEPOSIT.add(CONWAY_KEY_DEPOSIT), POOL_DEPOSIT, DREP_DEPOSIT,
                BigInteger.ZERO);
        assertThat(store.depositObligations()).isEqualTo(registered);

        // A proposal, a stake-key refund and a DRep refund in the next block
        TransactionBody proposal = tx("22".repeat(32), List.of(
                StakeDeregistration.builder().stakeCredential(stake(KEY_A)).build(),
                UnregDrepCert.builder().drepCredential(credential(DREP)).coin(DREP_DEPOSIT).build()))
                .toBuilder()
                .proposalProcedures(List.of(ProposalProcedure.builder()
                        .deposit(PROPOSAL_DEPOSIT).rewardAccount("e0" + KEY_B).govAction(new InfoAction()).build()))
                .build();
        applyBlock(2, epochStart(10) + 100, proposal);

        var afterRefunds = new DepositObligations(CONWAY_KEY_DEPOSIT, POOL_DEPOSIT, BigInteger.ZERO,
                PROPOSAL_DEPOSIT);
        assertThat(store.depositObligations()).isEqualTo(afterRefunds);
        assertThat(afterRefunds.total()).isEqualTo(CONWAY_KEY_DEPOSIT.add(POOL_DEPOSIT).add(PROPOSAL_DEPOSIT));

        store.rollbackToSlot(epochStart(10));

        assertThat(store.depositObligations()).isEqualTo(registered);
    }

    @Test
    void auditSumsEveryAccountUnderOneSnapshot() {
        applyBlock(1, epochStart(10), tx("11".repeat(32), List.of(
                reg(KEY_A),
                RegCert.builder().stakeCredential(stake(KEY_B)).coin(CONWAY_KEY_DEPOSIT).build(),
                RegDrepCert.builder().drepCredential(credential(DREP)).coin(DREP_DEPOSIT).build(),
                poolRegistration())));

        var audit = store.auditDeposits();

        assertThat(audit.totalDeposited()).isEqualTo(KEY_DEPOSIT.add(CONWAY_KEY_DEPOSIT).add(DREP_DEPOSIT));
        assertThat(audit.accountCount()).isEqualTo(2);
        assertThat(audit.accountDeposits()).isEqualTo(KEY_DEPOSIT.add(CONWAY_KEY_DEPOSIT));
        assertThat(audit.drepCount()).isEqualTo(1);
        assertThat(audit.drepDeposits()).isEqualTo(DREP_DEPOSIT);
        assertThat(audit.poolCount()).isEqualTo(1);
        assertThat(audit.poolDeposits()).isEqualTo(POOL_DEPOSIT);
        assertThat(audit.stakeKeysConsistent()).isTrue();
    }

    @Test
    void legacyRegistrationChargesTheEpochEffectiveKeyDeposit() {
        EpochParamTracker tracker = new EpochParamTracker(params(), true);
        tracker.applyEnactedParamChange(11, ProtocolParamUpdate.builder().keyDeposit(CONWAY_KEY_DEPOSIT).build());
        store.setParamTracker(tracker);

        applyBlock(1, epochStart(10), tx("11".repeat(32), List.of(reg(KEY_A))));
        applyBlock(2, epochStart(11), tx("22".repeat(32), List.of(reg(KEY_B))));

        assertThat(store.getStakeDeposit(0, KEY_A)).contains(KEY_DEPOSIT);
        assertThat(store.getStakeDeposit(0, KEY_B)).contains(CONWAY_KEY_DEPOSIT);
        assertThat(store.getTotalDeposited()).isEqualTo(KEY_DEPOSIT.add(CONWAY_KEY_DEPOSIT));
    }

    @Test
    void refundBeyondTheTotalDepositedFailsInsteadOfClamping() throws Exception {
        // A stake account whose deposit was never added to total_dep: corrupt state
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            batch.put(rocks.cfState(), DefaultAccountStateStore.accountKey(0, KEY_A),
                    AccountStateCborCodec.encodeStakeAccount(BigInteger.ZERO, KEY_DEPOSIT));
            rocks.db().write(options, batch);
        }

        assertThatThrownBy(() -> applyBlock(1, epochStart(10), tx("11".repeat(32),
                List.of(StakeDeregistration.builder().stakeCredential(stake(KEY_A)).build()))))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Total deposited would become negative at block 1: current=0, delta=-"
                        + KEY_DEPOSIT + "; the deposit state is corrupt, resync from genesis");
        assertThat(store.getTotalDeposited()).isZero();
    }

    private void applyBlock(long blockNumber, long slot, TransactionBody... transactions) {
        Block block = Block.builder().transactionBodies(new ArrayList<>(Arrays.asList(transactions))).build();
        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNumber, "block" + blockNumber, block));
    }

    private static TransactionBody tx(String txHash, List<Certificate> certificates) {
        return TransactionBody.builder().txHash(txHash).certificates(new ArrayList<>(certificates)).build();
    }

    private static StakeRegistration reg(String hash) {
        return StakeRegistration.builder().stakeCredential(stake(hash)).build();
    }

    private static StakeCredential stake(String hash) {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static Credential credential(String hash) {
        return Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static PoolRegistration poolRegistration() {
        return PoolRegistration.builder()
                .poolParams(PoolParams.builder()
                        .operator(POOL)
                        .vrfKeyHash("bb".repeat(32))
                        .pledge(BigInteger.valueOf(1_000_000_000L))
                        .cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount("e0" + KEY_A)
                        .poolOwners(Set.of(KEY_A))
                        .build())
                .build();
    }

    private static long epochStart(int epoch) {
        return epoch * EPOCH_LENGTH;
    }

    private static EpochParamProvider params() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return KEY_DEPOSIT;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return POOL_DEPOSIT;
            }
        };
    }
}
