package org.yanoproject.ledgerstate;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.AuthCommitteeHotCert;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.ResignCommitteeColdCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.UpdateDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.Vote;
import com.bloxbean.cardano.yaci.core.model.governance.Voter;
import com.bloxbean.cardano.yaci.core.model.governance.VoterType;
import com.bloxbean.cardano.yaci.core.model.governance.VotingProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.VotingProcedures;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionBodySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledgerstate.governance.GovernanceBlockProcessor;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.epoch.DRepDistributionCalculator;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.DRepStateRecord;
import org.yanoproject.ledgerstate.governance.ratification.EnactmentProcessor;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State that a certificate, withdrawal or vote writes and a later one of the same block reads back.
 * The first tests replay the real preprod and preview transactions behind the ADR-056 Phase 7c
 * shadow-sync findings; the synthetic ones cover the other same-block sequences.
 * <p>
 * The account-state store applies a block through one uncommitted {@code WriteBatch}. Before the
 * fix, a deregistration read the account with {@code db.get()}, which does not see a registration
 * earlier in the same block (the same transaction's {@code RegCert}, or an earlier transaction), so
 * the account stayed registered, with its deposit counted and never refunded. The chain's next
 * registration of the credential then failed the Java engine's {@code DELEG.StakeKeyRegisteredDELEG}
 * (cardano-ledger f649f975 {@code Conway/Rules/Deleg.hs:212-214, 233-235}) against Yano's state,
 * though the chain accepted it. The DRep family is the same bug on {@code ConwayRegDRep} /
 * {@code ConwayUnRegDRep} ({@code GovCert.hs:210-212}).
 */
class SameBlockStateTest {

    private static final String FIXTURE = "/shadow-sync/same-block-registration-txs.txt";
    private static final BigInteger KEY_DEPOSIT = BigInteger.valueOf(2_000_000L);
    /** The last-applied block and slot, which a rollback rewrites rather than restores. */
    private static final Set<String> LAST_APPLIED_HEX = Set.of(hex("meta.last_block"), hex("meta.last_applied_slot"));
    /**
     * {@code "total_dep"}: an absent total is journalled as an explicit zero, so a rollback may leave
     * a zero where there was none; compared through {@code getTotalDeposited()} instead.
     */
    private static final String TOTAL_DEPOSITED_HEX = hex("total_dep");

    /** preprod, 162 findings. */
    private static final String PREPROD_KEY_5064 = "5064b671634d14cb8d543e71dd8eb437a47efb47b0b22882866c420d";
    /** preprod, 4 findings; the registration and the deregistration are in different transactions. */
    private static final String PREPROD_KEY_94DF = "94dfe10474e72a5447261a512da8c226f32326a203c4a2801337bb7f";
    /** preview, PV 10, Conway {@code RegCert}/{@code UnRegCert} with deposits. */
    private static final String PREVIEW_KEY_75AE = "75aedc755a6f1a962b85cb3595c3ce15c63adc6f66b288bbc3002b33";
    /** preprod, {@code GOVCERT.ConwayDRepAlreadyRegistered}. */
    private static final String PREPROD_DREP_7397 = "739701e411d342e6a385dcbec1f78edc31434ad1ad166d20954912d7";

    private static final String KEY_A = "a1".repeat(28);
    private static final String KEY_B = "b2".repeat(28);
    private static final String DREP = "d3".repeat(28);
    private static final String COLD = "c4".repeat(28);
    private static final String HOT_OLD = "e5".repeat(28);
    private static final String HOT_NEW = "f6".repeat(28);
    private static final BigInteger DREP_DEPOSIT = new BigInteger("500000000");
    /** The default {@code EpochParamProvider.getDRepActivity}. */
    private static final int DREP_ACTIVITY = 20;

    @TempDir
    Path tempDir;

    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;
    private Map<String, FixtureTx> txs;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(SameBlockStateTest.class), true, params());
        store.setGovernanceBlockProcessor(new GovernanceBlockProcessor(rocks.governanceStore(), params()));
        txs = loadFixture();
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    @Test
    void registrationThenDeregistrationInOneTransactionLeavesTheKeyUnregistered() {
        // preprod block 2620906, tx a13523c8…: [StakeRegistration, UnRegCert(refund 2 ADA)]
        applyReal(2620906, "a13523c88cfe849c5dae9f4bc0a8367d1c3e53dfb083526f3c8a005208c6e6c5");

        assertThat(store.isStakeCredentialRegistered(0, PREPROD_KEY_5064)).isFalse();
        assertThat(store.getStakeDeposit(0, PREPROD_KEY_5064)).isEmpty();
        assertThat(store.getTotalDeposited()).isZero();

        // preprod block 2620909, tx 00586b27…: the chain's next StakeRegistration, which shadow
        // sync reported as DELEG.StakeKeyRegisteredDELEG.
        applyReal(2620909, "00586b270e07e5077ee8962576899c055e195f6ad4d7a2ddd3042f2d1903cd2a");

        assertThat(store.isStakeCredentialRegistered(0, PREPROD_KEY_5064)).isTrue();
        assertThat(store.getStakeDeposit(0, PREPROD_KEY_5064)).contains(KEY_DEPOSIT);
        assertThat(store.getTotalDeposited()).isEqualTo(KEY_DEPOSIT);
    }

    @Test
    void conwayRegCertThenUnRegCertInOneTransactionLeavesTheKeyUnregistered() {
        // preview block 3086527, tx 85384095…: [RegCert(2 ADA), UnRegCert(2 ADA)] at PV 10
        applyReal(3086527, "853840957c33f796e67d156f994574b4bfd6a1344eb24e0b1f600d334d120772");

        assertThat(store.isStakeCredentialRegistered(0, PREVIEW_KEY_75AE)).isFalse();
        assertThat(store.getTotalDeposited()).isZero();

        // preview block 3087382, tx 996ef51d…: RegCert + VoteDelegCert (reported), then the
        // deregistration in block 3087385.
        applyReal(3087382, "996ef51d0488bc1e61397dc03798f840370fd5231d2c1b38929984829dc3b44b");
        assertThat(store.isStakeCredentialRegistered(0, PREVIEW_KEY_75AE)).isTrue();
        assertThat(store.getDRepDelegation(0, PREVIEW_KEY_75AE)).isPresent();

        applyReal(3087385, "fe4a5eebd5cacb871a4de9bc4f710e2cd5e8743434ae00b6f6fd4aea784926d6");
        assertThat(store.isStakeCredentialRegistered(0, PREVIEW_KEY_75AE)).isFalse();
        assertThat(store.getDRepDelegation(0, PREVIEW_KEY_75AE)).isEmpty();
        assertThat(store.getTotalDeposited()).isZero();
    }

    @Test
    void registrationAndDeregistrationInDifferentTransactionsOfOneBlockLeaveTheKeyUnregistered() {
        // preprod block 3105726: tx 2 5841d782… registers and delegates (pool and DRep), tx 3
        // 6d583a34… deregisters.
        applyReal(3105726,
                "5841d78219640438a671e07f00afe5ff0c2722e2496dbf7bba83f3c55fc904b7",
                "6d583a341169865bbe7cbd77c1ff30c4991c0328c5aacfafaf1d2ca78c69a0d0");

        assertThat(store.isStakeCredentialRegistered(0, PREPROD_KEY_94DF)).isFalse();
        assertThat(store.getDelegatedPool(0, PREPROD_KEY_94DF)).isEmpty();
        assertThat(store.getDRepDelegation(0, PREPROD_KEY_94DF)).isEmpty();
        assertThat(store.getTotalDeposited()).isZero();

        // preprod block 3105745, tx dbaa50a1…: the reported re-registration.
        applyReal(3105745, "dbaa50a1677e67bbdf9fd07672193e7eb6499fa8d10190bcaa1fa2a286fd2701");
        assertThat(store.isStakeCredentialRegistered(0, PREPROD_KEY_94DF)).isTrue();
        assertThat(store.getTotalDeposited()).isEqualTo(KEY_DEPOSIT);
    }

    @Test
    void drepRegistrationAndRetirementInOneBlockLeaveTheDRepUnregistered() throws Exception {
        // preprod block 2659850: tx 1 54825923… ConwayRegDRep, tx 2 fec4cefc… ConwayUnRegDRep.
        applyReal(2659850,
                "548259231e188e3a275c7c7457cf8edadb863ea6ce7ec61dd87ae054265f65cc",
                "fec4cefcd1be3037c40d66c00297c5452976419c1f91923aa27583b4b94cd28a");

        assertThat(store.isDRepRegistered(0, PREPROD_DREP_7397)).isFalse();
        assertThat(store.getDRepDeposit(0, PREPROD_DREP_7397)).isEmpty();
        assertThat(store.getTotalDeposited()).isZero();
        // The governance record is the retired tombstone, not an active registration.
        DRepStateRecord tombstone = rocks.governanceStore().getDRepState(0, PREPROD_DREP_7397).orElseThrow();
        assertThat(tombstone.previousDeregistrationSlot()).isEqualTo(69866635L);
        assertThat(tombstone.deregistered()).isTrue();

        // preprod block 2659855, tx 478e03ed…: the reported ConwayRegDRep.
        applyReal(2659855, "478e03ed54d05fc1307b1fe25df06bfa4e730498f600f3925977fdaea6879ae6");
        assertThat(store.isDRepRegistered(0, PREPROD_DREP_7397)).isTrue();
        DRepStateRecord registered = rocks.governanceStore().getDRepState(0, PREPROD_DREP_7397).orElseThrow();
        assertThat(registered.registeredAtSlot()).isEqualTo(69866725L);
        assertThat(registered.previousDeregistrationSlot()).isEqualTo(69866635L);
        assertThat(store.getTotalDeposited()).isEqualTo(new BigInteger("500000000"));
    }

    @Test
    void rollbackRestoresTheStateBeforeTheBlocksByteForByte() {
        applyReal(1, "a13523c88cfe849c5dae9f4bc0a8367d1c3e53dfb083526f3c8a005208c6e6c5");
        Map<String, String> before = stateColumn();
        BigInteger depositedBefore = store.getTotalDeposited();
        long beforeSlot = txs.get("a13523c88cfe849c5dae9f4bc0a8367d1c3e53dfb083526f3c8a005208c6e6c5").slot();

        applyReal(2659850,
                "548259231e188e3a275c7c7457cf8edadb863ea6ce7ec61dd87ae054265f65cc",
                "fec4cefcd1be3037c40d66c00297c5452976419c1f91923aa27583b4b94cd28a");
        applyReal(3105726,
                "5841d78219640438a671e07f00afe5ff0c2722e2496dbf7bba83f3c55fc904b7",
                "6d583a341169865bbe7cbd77c1ff30c4991c0328c5aacfafaf1d2ca78c69a0d0");
        applyReal(3105745, "dbaa50a1677e67bbdf9fd07672193e7eb6499fa8d10190bcaa1fa2a286fd2701");

        store.rollbackToSlot(beforeSlot);

        assertThat(stateColumn()).isEqualTo(before);
        assertThat(store.getTotalDeposited()).isEqualTo(depositedBefore);
    }

    // ------------------------------------------------------------------ synthetic same-block sequences

    @Test
    void withdrawalThenDeregistrationInOneTransaction() throws Exception {
        applyBlock(1, 1_000, tx(reg(KEY_A)));
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), DefaultAccountStateStore.accountKey(0, KEY_A),
                    AccountStateCborCodec.encodeStakeAccount(BigInteger.valueOf(5_000_000L), KEY_DEPOSIT));
            commit(batch);
        }

        // Haskell drains the withdrawals before the certificates (Conway/Rules/Certs.hs:241).
        // KEY_B is registered earlier in the same block, so only the overlay knows it.
        applyBlock(2, 2_000,
                withdrawalTx(KEY_A, BigInteger.valueOf(5_000_000L), dereg(KEY_A)),
                tx(reg(KEY_B)),
                withdrawalTx(KEY_B, BigInteger.ZERO, dereg(KEY_B)));

        assertThat(store.isStakeCredentialRegistered(0, KEY_A)).isFalse();
        assertThat(store.isStakeCredentialRegistered(0, KEY_B)).isFalse();
        assertThat(store.getTotalDeposited()).isZero();
    }

    @Test
    void registrationDeregistrationRegistrationInOneBlockCountsOneDeposit() {
        applyBlock(1, 1_000, tx(reg(KEY_A), dereg(KEY_A)), tx(reg(KEY_A)));

        assertThat(store.getStakeDeposit(0, KEY_A)).contains(KEY_DEPOSIT);
        assertThat(store.getTotalDeposited()).isEqualTo(KEY_DEPOSIT);
    }

    @Test
    void drepRegistrationUpdateRetirementInOneBlockLeaveTheDRepRetired() throws Exception {
        applyBlock(1, 1_000,
                tx(regDRep(DREP)),
                tx(UpdateDrepCert.builder().drepCredential(credential(DREP)).build()),
                tx(unregDRep(DREP)));

        assertThat(store.isDRepRegistered(0, DREP)).isFalse();
        assertThat(store.getTotalDeposited()).isZero();
        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.active()).isFalse();
        assertThat(record.previousDeregistrationSlot()).isEqualTo(1_000L);
    }

    @Test
    void drepRetirementThenReRegistrationInOneBlockRecordsTheRetirement() throws Exception {
        applyBlock(1, 1_000, tx(regDRep(DREP)));

        applyBlock(2, 2_000, tx(unregDRep(DREP)), tx(regDRep(DREP)));

        assertThat(store.isDRepRegistered(0, DREP)).isTrue();
        assertThat(store.getTotalDeposited()).isEqualTo(DREP_DEPOSIT);
        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.registeredAtSlot()).isEqualTo(2_000L);
        assertThat(record.previousDeregistrationSlot()).isEqualTo(2_000L);
        // Equal slots cannot say which came last; the flag does (Haskell deletes, then inserts).
        assertThat(record.deregistered()).isFalse();
    }

    @Test
    void voteInALaterBlockRefreshesADRepReRegisteredInOneBlock() throws Exception {
        applyBlock(1, 1_000, tx(regDRep(DREP)));
        applyBlock(2, 2_000, tx(unregDRep(DREP)), tx(regDRep(DREP)));

        applyBlock(3, 3_000, drepVoteTx(DREP));

        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.lastInteractionEpoch()).isEqualTo(record.registeredAtEpoch());
    }

    @Test
    void delegationsToADRepReRegisteredInOneBlockCountInTheDistribution() throws Exception {
        applyBlock(1, 1_000, tx(regDRep(DREP)));
        applyBlock(2, 2_000, tx(unregDRep(DREP)), tx(regDRep(DREP)));
        applyBlock(3, 3_000, tx(reg(KEY_A),
                VoteDelegCert.builder().stakeCredential(stake(KEY_A)).drep(Drep.addrKeyHash(DREP)).build()));

        assertThat(drepStake(DREP, KEY_A)).isEqualTo(BigInteger.valueOf(100));
    }

    @Test
    void delegationInTheBlockThatRetiresAndReRegistersTheDRepCounts() throws Exception {
        applyBlock(1, 1_000, tx(regDRep(DREP)));

        // The delegation shares the retirement's slot but follows the re-registration.
        applyBlock(2, 2_000, tx(unregDRep(DREP)), tx(regDRep(DREP)), tx(reg(KEY_A),
                VoteDelegCert.builder().stakeCredential(stake(KEY_A)).drep(Drep.addrKeyHash(DREP)).build()));

        assertThat(drepStake(DREP, KEY_A)).isEqualTo(BigInteger.valueOf(100));
    }

    /** The DRep's stake in the distribution when {@code delegator} holds 100 lovelace. */
    private BigInteger drepStake(String drep, String delegator) throws RocksDBException {
        var distribution = new DRepDistributionCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(),
                rocks.governanceStore())
                .calculate(0, Map.of(new UtxoBalanceAggregator.CredentialKey(0, delegator), BigInteger.valueOf(100)),
                        Map.of());
        return distribution.get(new DRepDistributionCalculator.DRepDistKey(0, drep));
    }

    @Test
    void voteByADRepRetiredEarlierInTheBlockKeepsItRetired() throws Exception {
        applyBlock(1, 1_000, tx(regDRep(DREP)));

        applyBlock(2, 2_000, tx(unregDRep(DREP)), drepVoteTx(DREP));

        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.active()).isFalse();
        assertThat(record.previousDeregistrationSlot()).isEqualTo(2_000L);
    }

    @Test
    void voteByADRepRegisteredEarlierInTheBlockRefreshesIt() throws Exception {
        storeNumDormantEpochs(3);

        applyBlock(1, 1_000, tx(regDRep(DREP)), drepVoteTx(DREP));

        // The vote's refresh gives currentEpoch + drepActivity - numDormant (Certs.hs:278-292),
        // lower than the PV 9 registration's currentEpoch + drepActivity.
        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.active()).isTrue();
        assertThat(record.lastInteractionEpoch()).isEqualTo(record.registeredAtEpoch());
        assertThat(record.expiryEpoch()).isEqualTo(record.registeredAtEpoch() + DREP_ACTIVITY - 3);
    }

    @Test
    void voteInTheRegisteringTransactionDoesNotRefreshTheNewDRepAtProtocolVersion9() throws Exception {
        storeNumDormantEpochs(3);

        // Haskell refreshes the voting DReps before the transaction's certificates (Certs.hs:240 at
        // PV 9), when this DRep does not exist yet, so the registration's expiry stands: at PV 9
        // currentEpoch + drepActivity, without the dormant epochs (GovCert.hs:286-292).
        applyBlock(1, 1_000, drepVoteTx(DREP, regDRep(DREP)));

        DRepStateRecord record = drepRecord(DREP);
        assertThat(record.expiryEpoch()).isEqualTo(record.registeredAtEpoch() + DREP_ACTIVITY);
        assertThat(record.lastInteractionEpoch()).isNull();
    }

    @Test
    void futureMemberResignationWithoutARecordSurvivesEnrollment() throws Exception {
        // A potential future member may resign before any hot-key authorisation (GovCert.hs:197-208).
        applyBlock(1, 1_000,
                tx(ResignCommitteeColdCert.builder().committeeColdCredential(credential(COLD)).build()));

        // UpdateCommittee enrollment keeps the resignation (Epoch.hs:419-423), so a later
        // AuthCommitteeHot fails ConwayCommitteeHasPreviouslyResigned.
        GovernanceStateStore governance = rocks.governanceStore();
        CommitteeMemberRecord enrolled = EnactmentProcessor.enactedMemberRecord(
                governance.getCommitteeMember(0, COLD).orElse(null), 300);
        assertThat(enrolled.resigned()).isTrue();
        assertThat(enrolled.hasHotKey()).isFalse();
        assertThat(enrolled.expiryEpoch()).isEqualTo(300);
    }

    @Test
    void committeeAuthorizationThenResignationInOneBlockResignsAFutureMember() throws Exception {
        applyBlock(1, 1_000,
                tx(AuthCommitteeHotCert.builder()
                        .committeeColdCredential(credential(COLD)).committeeHotCredential(credential(HOT_NEW)).build()),
                tx(ResignCommitteeColdCert.builder().committeeColdCredential(credential(COLD)).build()));

        CommitteeMemberRecord member = rocks.governanceStore().getCommitteeMember(0, COLD).orElseThrow();
        assertThat(member.resigned()).isTrue();
    }

    @Test
    void committeeAuthorizationThenResignationInOneBlockKeepsTheBlocksHotKey() throws Exception {
        GovernanceStateStore governance = rocks.governanceStore();
        try (WriteBatch batch = new WriteBatch()) {
            governance.storeCommitteeMember(0, COLD, new CommitteeMemberRecord(0, HOT_OLD, 300, false),
                    batch, new ArrayList<>());
            commit(batch);
        }

        applyBlock(1, 1_000,
                tx(AuthCommitteeHotCert.builder()
                        .committeeColdCredential(credential(COLD)).committeeHotCredential(credential(HOT_NEW)).build()),
                tx(ResignCommitteeColdCert.builder().committeeColdCredential(credential(COLD)).build()));

        CommitteeMemberRecord member = governance.getCommitteeMember(0, COLD).orElseThrow();
        assertThat(member.resigned()).isTrue();
        assertThat(member.hotHash()).isEqualTo(HOT_NEW);
        assertThat(member.expiryEpoch()).isEqualTo(300);
    }

    @Test
    void twoMirCertificatesInOneBlockAccumulate() throws Exception {
        applyBlock(1, 1_000, Era.Babbage,
                tx(mirToCredential(KEY_A, 1_000_000L)), tx(mirToCredential(KEY_A, 2_000_000L)),
                tx(mirReservesToTreasury(5_000_000L)), tx(mirReservesToTreasury(7_000_000L)));

        byte[] mir = rocks.db().get(rocks.cfState(), DefaultAccountStateStore.mirRewardKey(0, KEY_A));
        assertThat(AccountStateCborCodec.decodeMirReward(mir)).isEqualTo(BigInteger.valueOf(3_000_000L));
        assertThat(store.getMirPotTransfer(false)).isEqualTo(BigInteger.valueOf(12_000_000L));
    }

    @Test
    void rollbackRestoresAKeyWrittenSeveralTimesInOneBlock() throws Exception {
        applyBlock(1, 1_000, tx(reg(KEY_A)));
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), DefaultAccountStateStore.accountKey(0, KEY_A),
                    AccountStateCborCodec.encodeStakeAccount(BigInteger.valueOf(5_000_000L), KEY_DEPOSIT));
            commit(batch);
        }
        Map<String, String> before = stateColumn();
        BigInteger depositedBefore = store.getTotalDeposited();

        // The committed account (5 ADA reward) is drained, deleted and re-created in one block: three
        // journal entries for one key, whose undo must run in reverse to restore the committed bytes.
        applyBlock(2, 2_000, withdrawalTx(KEY_A, BigInteger.valueOf(5_000_000L), dereg(KEY_A), reg(KEY_A)));
        assertThat(store.getRewardBalance(0, KEY_A)).contains(BigInteger.ZERO);

        store.rollbackToSlot(1_000);

        assertThat(stateColumn()).isEqualTo(before);
        assertThat(store.getTotalDeposited()).isEqualTo(depositedBefore);
    }

    // ------------------------------------------------------------------ helpers

    private void applyReal(long blockNo, String... txHashes) {
        List<TransactionBody> bodies = new ArrayList<>();
        long slot = -1;
        for (String txHash : txHashes) {
            FixtureTx tx = txs.get(txHash);
            if (slot >= 0 && slot != tx.slot()) {
                throw new IllegalArgumentException("transactions of one block must share its slot");
            }
            slot = tx.slot();
            bodies.add(tx.body());
        }
        applyBlock(blockNo, slot, Era.Conway, bodies.toArray(TransactionBody[]::new));
    }

    private void applyBlock(long blockNo, long slot, Era era, TransactionBody... transactions) {
        Block block = Block.builder().transactionBodies(new ArrayList<>(Arrays.asList(transactions))).build();
        store.applyBlock(new BlockAppliedEvent(era, slot, blockNo, "block" + blockNo, block));
    }

    private void applyBlock(long blockNo, long slot, TransactionBody... transactions) {
        applyBlock(blockNo, slot, Era.Conway, transactions);
    }

    private static TransactionBody tx(Certificate... certificates) {
        return TransactionBody.builder().certificates(new ArrayList<>(Arrays.asList(certificates))).build();
    }

    private static TransactionBody withdrawalTx(String keyHash, BigInteger amount, Certificate... certificates) {
        return tx(certificates).toBuilder().withdrawals(Map.of("e0" + keyHash, amount)).build();
    }

    private static TransactionBody drepVoteTx(String drepHash, Certificate... certificates) {
        GovActionId action = GovActionId.builder().transactionId("99".repeat(32)).gov_action_index(0).build();
        Voter voter = Voter.builder().type(VoterType.DREP_KEY_HASH).hash(drepHash).build();
        VotingProcedures votes = VotingProcedures.builder()
                .voting(Map.of(voter, Map.of(action, VotingProcedure.builder().vote(Vote.YES).build())))
                .build();
        return tx(certificates).toBuilder().votingProcedures(votes).build();
    }

    private static StakeCredential stake(String hash) {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static Credential credential(String hash) {
        return Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(hash).build();
    }

    private static StakeRegistration reg(String hash) {
        return StakeRegistration.builder().stakeCredential(stake(hash)).build();
    }

    private static StakeDeregistration dereg(String hash) {
        return StakeDeregistration.builder().stakeCredential(stake(hash)).build();
    }

    private static RegDrepCert regDRep(String hash) {
        return RegDrepCert.builder().drepCredential(credential(hash)).coin(DREP_DEPOSIT).build();
    }

    private static UnregDrepCert unregDRep(String hash) {
        return UnregDrepCert.builder().drepCredential(credential(hash)).coin(DREP_DEPOSIT).build();
    }

    private static MoveInstataneous mirToCredential(String hash, long lovelace) {
        return MoveInstataneous.builder().reserves(true)
                .stakeCredentialCoinMap(Map.of(stake(hash), BigInteger.valueOf(lovelace))).build();
    }

    private static MoveInstataneous mirReservesToTreasury(long lovelace) {
        return MoveInstataneous.builder().reserves(true).accountingPotCoin(BigInteger.valueOf(lovelace)).build();
    }

    private DRepStateRecord drepRecord(String hash) throws RocksDBException {
        return rocks.governanceStore().getDRepState(0, hash).orElseThrow();
    }

    private void storeNumDormantEpochs(int count) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch()) {
            rocks.governanceStore().storeNumDormantEpochs(count, batch, new ArrayList<>());
            commit(batch);
        }
    }

    private void commit(WriteBatch batch) throws RocksDBException {
        try (WriteOptions options = new WriteOptions()) {
            rocks.db().write(options, batch);
        }
    }

    /** Everything in the account-state column family except the last-applied markers and the deposit total. */
    private Map<String, String> stateColumn() {
        Map<String, String> state = new LinkedHashMap<>();
        try (RocksIterator iterator = rocks.db().newIterator(rocks.cfState())) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = HexFormat.of().formatHex(iterator.key());
                if (!LAST_APPLIED_HEX.contains(key) && !key.equals(TOTAL_DEPOSITED_HEX)) {
                    state.put(key, HexFormat.of().formatHex(iterator.value()));
                }
                iterator.next();
            }
        }
        return state;
    }

    private static String hex(String ascii) {
        return HexFormat.of().formatHex(ascii.getBytes(StandardCharsets.UTF_8));
    }

    private static EpochParamProvider params() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return KEY_DEPOSIT;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000L);
            }
        };
    }

    private record FixtureTx(String network, long blockNo, long slot, int txIndex, TransactionBody body) {
    }

    private static Map<String, FixtureTx> loadFixture() throws IOException {
        Map<String, FixtureTx> result = new LinkedHashMap<>();
        try (InputStream in = SameBlockStateTest.class.getResourceAsStream(FIXTURE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.trim().split(" ");
                result.put(f[4], new FixtureTx(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]),
                        Integer.parseInt(f[3]), decodeBody(f[4], f[5])));
            }
        }
        return result;
    }

    private static TransactionBody decodeBody(String txHash, String txCborHex) {
        try {
            byte[] txBytes = HexFormat.of().parseHex(txCborHex);
            Array tx = (Array) CborDecoder.decode(txBytes).getFirst();
            DataItem body = tx.getDataItems().getFirst();
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            new CborEncoder(bodyBytes).nonCanonical().encode(body);
            TransactionBody decoded = TransactionBodySerializer.INSTANCE.deserializeDI(body, bodyBytes.toByteArray());
            assertThat(decoded.getCertificates()).as("certificates of %s", txHash).isNotEmpty();
            return decoded;
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode fixture transaction " + txHash, e);
        }
    }
}
