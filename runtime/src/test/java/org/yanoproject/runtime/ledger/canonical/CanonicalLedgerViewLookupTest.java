package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.ResignCommitteeColdCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.EpochTransitionEvent;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.EpochBoundaryProcessor;
import org.yanoproject.ledgerstate.LedgerStateTestRecords;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.runtime.blockproducer.BlockProducerHelper;
import org.yanoproject.runtime.utxo.UtxoTestRecords;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 lookup gate over real stores: first-time records are confirmed absent, broken or missing
 * state is unavailable, and values map faithfully from Yano's storage.
 */
class CanonicalLedgerViewLookupTest {

    private static final String CRED = "11".repeat(28);
    private static final String POOL = "aa".repeat(28);
    private static final String VRF_1 = "b1".repeat(32);
    private static final String VRF_2 = "b2".repeat(32);
    private static final String DREP = "dd".repeat(28);
    private static final String COLD = "c1".repeat(28);
    private static final String HOT = "c2".repeat(28);
    private static final String CANDIDATE = "c3".repeat(28);
    private static final String TX = "ee".repeat(32);

    @TempDir
    Path tempDir;

    private CanonicalTestStores stores;
    private long blockNumber;
    private int opened;

    @AfterEach
    void tearDown() {
        if (stores != null) {
            stores.close();
        }
    }

    @Test
    void firstPoolRegistrationIsAbsentThenPresentWithActiveVrf() {
        open(true);
        setTip(11);
        assertThat(read(v -> v.pool(new PoolId(POOL)))).isInstanceOf(Lookup.Absent.class);

        apply(slot(10), poolRegistration(VRF_1));
        apply(slot(11), poolRegistration(VRF_2));  // re-registration in the tip epoch: future params

        PoolState pool = read(v -> v.pool(new PoolId(POOL))).require("pool");
        assertThat(pool.vrfKeyHashHex()).isEqualTo(VRF_1);
        assertThat(pool.deposit()).isEqualTo(BigInteger.valueOf(500_000_000L));
        assertThat(pool.retiringEpoch()).isNull();
        assertThat(pool.params()).isNull();
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_1))).isEqualTo(Lookup.present(new PoolId(POOL)));
    }

    @Test
    void deregisteredThenRegisteredCredentialIsAbsentThenPresent() {
        open(true);
        setTip(10);
        CredentialKey key = CredentialKey.key(CRED);
        assertThat(read(v -> v.account(key))).isInstanceOf(Lookup.Absent.class);

        apply(slot(10), RegCert.builder().stakeCredential(stake()).coin(BigInteger.TWO).build(),
                VoteDelegCert.builder().stakeCredential(stake()).drep(Drep.abstain()).build());
        AccountState registered = read(v -> v.account(key)).require("account");
        assertThat(registered.deposit()).isEqualTo(BigInteger.TWO);
        assertThat(registered.drepDelegation()).isEqualTo(DRepTarget.ALWAYS_ABSTAIN);

        apply(slot(10) + 1, UnregCert.builder().stakeCredential(stake()).coin(BigInteger.TWO).build());
        assertThat(read(v -> v.account(key))).isInstanceOf(Lookup.Absent.class);

        apply(slot(10) + 2, RegCert.builder().stakeCredential(stake()).coin(BigInteger.valueOf(3)).build());
        AccountState again = read(v -> v.account(key)).require("account");
        assertThat(again.deposit()).isEqualTo(BigInteger.valueOf(3));
        assertThat(again.rewardBalance()).isZero();
        assertThat(again.drepDelegation()).isNull();
    }

    @Test
    void firstTimeDRepIsAbsentThenPresentWithStoredExpiry() {
        open(true);
        setTip(10);
        CredentialKey drep = CredentialKey.key(DREP);
        assertThat(read(v -> v.drep(drep))).isInstanceOf(Lookup.Absent.class);

        apply(slot(10), RegDrepCert.builder()
                .drepCredential(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(DREP).build())
                .coin(BigInteger.valueOf(500)).build());

        DRepState state = read(v -> v.drep(drep)).require("drep");
        assertThat(state.deposit()).isEqualTo(BigInteger.valueOf(500));
        assertThat(state.expiryEpoch()).isEqualTo(10 + 20);  // epoch + drepActivity - dormant(0)
        assertThat(read(CanonicalLedgerView::dormantEpochs)).isEqualTo(Lookup.present(0L));
    }

    @Test
    void inactiveRegisteredDRepIsPresent() throws Exception {
        open(true);
        setTip(10);
        apply(slot(10), RegDrepCert.builder()
                .drepCredential(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(DREP).build())
                .coin(BigInteger.valueOf(500)).build());
        // A boundary marks the DRep inactive (ratification only); it stays registered: Haskell GOV and
        // DELEG check vsDReps membership, not activity (Gov.hs:472/595, Deleg.hs:224-226).
        GovernanceStateStore gov = new GovernanceStateStore(stores.db(), stores.cfState());
        var record = gov.getDRepState(0, DREP).orElseThrow();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            gov.storeDRepState(0, DREP, record.withExpiry(9, false), batch, new ArrayList<>());
            stores.db().write(options, batch);
        }

        DRepState state = read(v -> v.drep(CredentialKey.key(DREP))).require("drep");
        assertThat(state.deposit()).isEqualTo(BigInteger.valueOf(500));
        assertThat(state.expiryEpoch()).isEqualTo(9);
    }

    @Test
    void governanceReadsMapProposalsCommitteeAndRoots() throws Exception {
        open(true);
        setTip(10);
        GovernanceStateStore gov = new GovernanceStateStore(stores.db(), stores.cfState());
        Map<Credential, Integer> newMembers = new LinkedHashMap<>();
        newMembers.put(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(CANDIDATE).build(), 90);
        UpdateCommittee update = UpdateCommittee.builder()
                .newMembersAndTerms(newMembers)
                .threshold(new UnitInterval(BigInteger.ONE, BigInteger.TWO))
                .build();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> ops = new ArrayList<>();
            gov.storeProposal(new com.bloxbean.cardano.yaci.core.model.governance.GovActionId(TX, 1),
                    new GovActionRecord(BigInteger.TEN, "e0" + CRED, 9, 15, GovActionType.UPDATE_COMMITTEE,
                            TX, 0, update, slot(9)), batch, ops);
            gov.storeCommitteeMember(0, COLD, new CommitteeMemberRecord(0, HOT, 80, false), batch, ops);
            gov.storeCommitteeMember(0, CANDIDATE, new CommitteeMemberRecord(0, HOT, 0, false), batch, ops);
            gov.storeLastEnactedAction(GovActionType.UPDATE_COMMITTEE, TX, 0, batch, ops);
            stores.db().write(options, batch);
        }

        ProposalState proposal = read(v -> v.proposal(new GovActionId(TX, 1))).require("proposal");
        assertThat(proposal.type())
                .isEqualTo(com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType
                        .UPDATE_COMMITTEE);
        assertThat(proposal.prevActionId()).isEqualTo(new GovActionId(TX, 0));
        assertThat(proposal.expiresAfterEpoch()).isEqualTo(15);
        assertThat(proposal.action()).isNotNull();
        assertThat(read(v -> v.proposal(new GovActionId(TX, 2)))).isInstanceOf(Lookup.Absent.class);

        assertThat(read(CanonicalLedgerView::committeeCandidates))
                .isEqualTo(Lookup.present(Set.of(CredentialKey.key(CANDIDATE))));
        assertThat(read(v -> v.committeeMemberByCold(CredentialKey.key(COLD))))
                .isEqualTo(Lookup.present(new CommitteeMemberState(CredentialKey.key(COLD), CredentialKey.key(HOT),
                        false, 80L)));
        assertThat(read(v -> v.committeeMembersByHot(CredentialKey.key(HOT))).require("members"))
                .extracting(CommitteeMemberState::cold)
                .containsExactlyInAnyOrder(CredentialKey.key(COLD), CredentialKey.key(CANDIDATE));
        assertThat(read(v -> v.committeeMemberByCold(CredentialKey.key(CRED)))).isInstanceOf(Lookup.Absent.class);
        assertThat(read(CanonicalLedgerView::enactedRoots))
                .isEqualTo(Lookup.present(new EnactedRoots(null, null, new GovActionId(TX, 0), null)));
        // No constitution was bootstrapped: unavailable, not "no guardrail".
        assertThat(read(CanonicalLedgerView::guardrailScriptHash)).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void governanceDisabledIsUnavailableNeverAbsent() {
        open(false);
        setTip(10);
        assertThat(read(v -> v.drep(CredentialKey.key(DREP)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(v -> v.proposal(new GovActionId(TX, 0)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(v -> v.committeeMemberByCold(CredentialKey.key(COLD)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(v -> v.committeeMembersByHot(CredentialKey.key(HOT)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(CanonicalLedgerView::committeeCandidates)).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(CanonicalLedgerView::enactedRoots)).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(CanonicalLedgerView::guardrailScriptHash)).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(CanonicalLedgerView::dormantEpochs)).isInstanceOf(Lookup.Unavailable.class);
        // Certificate state does not depend on governance tracking.
        assertThat(read(v -> v.account(CredentialKey.key(CRED)))).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void injectedStoreFailureIsUnavailable() throws Exception {
        open(true);
        setTip(10);
        LedgerStateTestRecords.putCorrupt(stores.db(), stores.cfState(), 0, CRED);
        assertThat(read(v -> v.account(CredentialKey.key(CRED)))).isInstanceOf(Lookup.Unavailable.class);

        // A reference-script hash whose script bytes are missing is a broken store, not absence.
        UtxoTestRecords.putUnspent(stores.chain, TX, 3, "addr_test1vqxyz", BigInteger.ONE, List.of(), null, null,
                "5c".repeat(28), null);
        assertThat(read(v -> v.utxo(new Outpoint(TX, 3)))).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void utxoCarriesValueInlineDatumAndReferenceScriptExactlyAsStored() throws Exception {
        open(true);
        setTip(10);
        byte[] inlineDatum = HexUtil.decodeHexString("d8799f0102ff");  // indefinite list: CCL re-encodes it
        byte[] script = HexUtil.decodeHexString("82024746010000222601");
        String policy = "5a".repeat(28);
        UtxoTestRecords.putUnspent(stores.chain, TX, 0, "addr_test1vqxyz", BigInteger.valueOf(7),
                List.of(new Amount(null, policy, null, new byte[]{0x41}, BigInteger.valueOf(9))),
                null, inlineDatum, "5b".repeat(28), script);

        UtxoEntry entry = read(v -> v.utxo(new Outpoint(TX.toUpperCase(Locale.ROOT), 0))).require("utxo");
        assertThat(entry.outpoint()).isEqualTo(new Outpoint(TX, 0));
        assertThat(entry.inlineDatumCbor()).isEqualTo(inlineDatum);
        assertThat(entry.output().getScriptRef()).isEqualTo(script);
        assertThat(entry.output().getInlineDatum()).isNotNull();
        assertThat(entry.output().getAddress()).isEqualTo("addr_test1vqxyz");
        assertThat(entry.output().getValue().getCoin()).isEqualTo(BigInteger.valueOf(7));
        assertThat(entry.output().getValue().getMultiAssets()).singleElement().satisfies(ma -> {
            assertThat(ma.getPolicyId()).isEqualTo(policy);
            assertThat(ma.getAssets()).singleElement().satisfies(a -> {
                assertThat(a.getNameAsBytes()).isEqualTo(new byte[]{0x41});
                assertThat(a.getValue()).isEqualTo(BigInteger.valueOf(9));
            });
        });
        assertThat(read(v -> v.utxo(new Outpoint(TX, 1)))).isInstanceOf(Lookup.Absent.class);
    }

    @Test
    void treasuryAndParametersComeFromTheTipEpoch() throws Exception {
        open(true);
        setTip(11);
        assertThat(read(CanonicalLedgerView::treasury)).isInstanceOf(Lookup.Unavailable.class);
        LedgerStateTestRecords.putTreasury(stores.db(), stores.cfState(), 11, BigInteger.valueOf(123));
        assertThat(read(CanonicalLedgerView::treasury)).isEqualTo(Lookup.present(BigInteger.valueOf(123)));

        Lookup<ProtocolParams> params = read(CanonicalLedgerView::protocolParams);
        assertThat(params).isInstanceOf(Lookup.Present.class);
        // Each read returns a fresh copy of the mutable CCL bean.
        assertThat(read(CanonicalLedgerView::protocolParams).require("params"))
                .isNotSameAs(params.require("params"));
    }

    @Test
    void parametersAreUnavailableWhenTheTrackerHasNone() {
        stores = new CanonicalTestStores(tempDir, true, epoch -> Optional.empty());
        setTip(10);
        assertThat(read(CanonicalLedgerView::protocolParams)).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void producerBoundarySectionPublishesTheNextLedgerEpochBeforeTheBlock() throws Exception {
        open(true);
        setTip(10);
        List<Integer> requestedParamEpochs = new CopyOnWriteArrayList<>();
        CanonicalTestStores s = stores;
        stores.gate.installSnapshotSource(new RocksCanonicalSnapshotSource(s::db, () -> s.accounts, () -> s.utxos,
                epoch -> {
                    requestedParamEpochs.add(epoch);
                    return s.accounts.getProtocolParameters(epoch);
                }, () -> false));
        LedgerStateTestRecords.putTreasury(stores.db(), stores.cfState(), 10, BigInteger.valueOf(100));
        SimpleEventBus bus = new SimpleEventBus();
        List<Boolean> boundaryHeld = new CopyOnWriteArrayList<>();
        bus.subscribe(EpochTransitionEvent.class, ctx -> {
            // Stand-in for the account store's boundary processing.
            boundaryHeld.add(stores.gate.isWriteHeldByCurrentThread());
            try {
                LedgerStateTestRecords.putTreasury(stores.db(), stores.cfState(), ctx.event().newEpoch(),
                        BigInteger.valueOf(111));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            stores.accounts.setBoundaryStep(ctx.event().newEpoch(), EpochBoundaryProcessor.STEP_COMPLETE);
        }, SubscriptionOptions.builder().build());
        BlockProducerHelper.setEpochParamProvider(CanonicalTestStores.epochParams());
        try {
            BlockProducerHelper.resetEpochTrackingToSlot(slot(10) + 100);
            long generation = stores.gate.generation();

            // No transition: the boundary section is unchanged and publishes nothing.
            BlockProducerHelper.prepareEpochTransitionInWriteSection(stores.chain, bus, slot(10) + 200, 2, "test");
            assertThat(stores.gate.generation()).isEqualTo(generation);
            assertThat(boundaryHeld).isEmpty();

            // Transition 10 -> 11 before the block is selected: ledger epoch 11, tip slot still in 10.
            BlockProducerHelper.prepareEpochTransitionInWriteSection(stores.chain, bus, slot(11) + 5, 2, "test");
            assertThat(boundaryHeld).containsExactly(true);
            assertThat(stores.gate.generation()).isEqualTo(generation + 1);
            assertThat(stores.gate.tip().tipSlotEpoch()).isEqualTo(10);
            assertThat(stores.gate.tip().ledgerEpoch()).isEqualTo(11);

            requestedParamEpochs.clear();
            assertThat(read(CanonicalLedgerView::treasury)).isEqualTo(Lookup.present(BigInteger.valueOf(111)));
            assertThat(requestedParamEpochs).containsOnly(11);
        } finally {
            BlockProducerHelper.setEpochParamProvider(null);
            BlockProducerHelper.resetEpochTrackingToSlot(-1);
            bus.close();
        }
    }

    @Test
    void vrfIndexHoldsActiveAndFutureVrfsOfRegisteredPools() {
        open(true);
        setTip(11);
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_1))).isInstanceOf(Lookup.Absent.class);

        apply(slot(10), poolRegistration(VRF_1));
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_1))).isEqualTo(Lookup.present(new PoolId(POOL)));
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_2))).isInstanceOf(Lookup.Absent.class);

        // Re-registration in the ledger epoch: VRF_1 stays active, VRF_2 is the future VRF.
        apply(slot(11), poolRegistration(VRF_2));
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_1))).isEqualTo(Lookup.present(new PoolId(POOL)));
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_2.toUpperCase(Locale.ROOT))))
                .isEqualTo(Lookup.present(new PoolId(POOL)));
    }

    @Test
    void poolWithOnlyFutureHistoryIsUnavailable() throws Exception {
        open(true);
        setTip(11);
        LedgerStateTestRecords.putPool(stores.db(), stores.cfState(), POOL, VRF_2, 11 + 3);
        assertThat(read(v -> v.pool(new PoolId(POOL)))).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(v -> v.poolByVrfKeyHash(VRF_2))).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void enumeratesCommitteeAndProposals() throws Exception {
        open(true);
        setTip(10);
        String resignedOnly = "c4".repeat(28);
        apply(slot(10), ResignCommitteeColdCert.builder()
                .committeeColdCredential(Credential.builder().type(StakeCredType.ADDR_KEYHASH).hash(resignedOnly)
                        .build())
                .build());
        GovernanceStateStore gov = new GovernanceStateStore(stores.db(), stores.cfState());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> ops = new ArrayList<>();
            gov.storeCommitteeMember(0, COLD, new CommitteeMemberRecord(0, HOT, 80, false), batch, ops);
            gov.storeCommitteeMember(1, CANDIDATE, new CommitteeMemberRecord(-1, null, 90, true), batch, ops);
            gov.storeProposal(new com.bloxbean.cardano.yaci.core.model.governance.GovActionId("ff".repeat(32), 0),
                    new GovActionRecord(BigInteger.TEN, "e0" + CRED, 9, 15, GovActionType.INFO_ACTION,
                            null, null, new InfoAction(), slot(9) + 7), batch, ops);
            gov.storeProposal(new com.bloxbean.cardano.yaci.core.model.governance.GovActionId(TX, 1),
                    new GovActionRecord(BigInteger.TEN, "e0" + CRED, 9, 15, GovActionType.INFO_ACTION,
                            null, null, new InfoAction(), slot(9) + 3), batch, ops);
            stores.db().write(options, batch);
        }

        assertThat(read(CanonicalLedgerView::committeeMembers).require("members")).containsExactly(
                new CommitteeMemberState(CredentialKey.key(COLD), CredentialKey.key(HOT), false, 80L),
                new CommitteeMemberState(CredentialKey.key(resignedOnly), null, true, null),
                new CommitteeMemberState(CredentialKey.script(CANDIDATE), null, true, 90L));
        assertThat(read(CanonicalLedgerView::activeProposals).require("proposals"))
                .extracting(ProposalState::id)
                .containsExactly(new GovActionId(TX, 1), new GovActionId("ff".repeat(32), 0));

        open(false);
        assertThat(read(CanonicalLedgerView::committeeMembers)).isInstanceOf(Lookup.Unavailable.class);
        assertThat(read(CanonicalLedgerView::activeProposals)).isInstanceOf(Lookup.Unavailable.class);
    }

    // ------------------------------------------------------------------ helpers

    private void open(boolean governance) {
        if (stores != null) {
            stores.close();
        }
        stores = new CanonicalTestStores(tempDir.resolve("db" + (++opened)), governance, epoch -> Optional.empty());
        CanonicalTestStores withParams = stores;
        stores.gate.installSnapshotSource(new RocksCanonicalSnapshotSource(
                withParams::db, () -> withParams.accounts, () -> withParams.utxos,
                epoch -> withParams.accounts.getProtocolParameters(epoch), () -> false));
    }

    /** Stores the chain tip (one block) at the start of {@code epoch}, as a canonical write. */
    private void setTip(int epoch) {
        stores.gate.runWrite(() -> stores.chain.storeBlock(new byte[32], 1L, slot(epoch) + 100, new byte[]{0}));
        assertThat(stores.gate.tip().tipSlotEpoch()).isEqualTo(epoch);
        assertThat(stores.gate.tip().ledgerEpoch()).isEqualTo(epoch);
    }

    private void apply(long slot, Certificate... certificates) {
        long number = ++blockNumber;
        TransactionBody tx = TransactionBody.builder()
                .txHash(String.format("%064x", number))
                .certificates(new ArrayList<>(Arrays.asList(certificates)))
                .build();
        Block block = Block.builder().transactionBodies(new ArrayList<>(List.of(tx))).build();
        stores.gate.runWrite(() -> stores.accounts.applyBlock(
                new BlockAppliedEvent(Era.Conway, slot, number, "hash" + number, block)));
    }

    private <T> Lookup<T> read(Function<CanonicalLedgerView, Lookup<T>> read) {
        Lookup<CanonicalSnapshot> acquired = stores.gate.acquireSnapshot(SnapshotPurpose.ADMISSION);
        assertThat(acquired).isInstanceOf(Lookup.Present.class);
        CanonicalSnapshot snapshot = ((Lookup.Present<CanonicalSnapshot>) acquired).value();
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            snapshot.release();
            return read.apply(view);
        }
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

    private static StakeCredential stake() {
        return StakeCredential.builder().type(StakeCredType.ADDR_KEYHASH).hash(CRED).build();
    }

    private static long slot(int epoch) {
        return epoch * CanonicalTestStores.EPOCH_LENGTH;
    }
}
