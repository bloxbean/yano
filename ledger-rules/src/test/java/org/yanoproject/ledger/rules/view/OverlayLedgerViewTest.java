package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.effects.LedgerChange;
import org.yanoproject.ledger.rules.effects.LedgerChange.AccountRegistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.AccountUnregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.CommitteeHotAuthorized;
import org.yanoproject.ledger.rules.effects.LedgerChange.CommitteeResigned;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepRegistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepUnregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.DormantDRepExpiriesBumped;
import org.yanoproject.ledger.rules.effects.LedgerChange.PoolReregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.PoolRetirementScheduled;
import org.yanoproject.ledger.rules.effects.LedgerChange.ProposalSubmitted;
import org.yanoproject.ledger.rules.effects.LedgerChange.RewardWithdrawn;
import org.yanoproject.ledger.rules.effects.LedgerChange.StakeDelegated;
import org.yanoproject.ledger.rules.effects.LedgerChange.VoteCast;
import org.yanoproject.ledger.rules.effects.LedgerChange.VoteDelegated;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledger.rules.view.model.Voter;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.view.Fixtures.KEY_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.POOL_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.hash28;
import static org.yanoproject.ledger.rules.view.Fixtures.hash32;
import static org.yanoproject.ledger.rules.view.Fixtures.keyCred;

class OverlayLedgerViewTest {

    private static final String TX1 = hash32(0xa1);
    private static final String TX2 = hash32(0xa2);
    private static final String TX3 = hash32(0xa3);
    private static final Outpoint BASE_UTXO = new Outpoint(hash32(0xb0), 0);
    private static final PoolId POOL = new PoolId(hash28(0x70));

    private static TxEffects changes(String txId, LedgerChange... changes) {
        return TxEffects.ofChanges(txId, List.of(changes));
    }

    private static TxEffects utxoEffects(String txId, List<Outpoint> consumed, List<UtxoEntry> produced) {
        return new TxEffects(txId, true, consumed, produced, List.of());
    }

    private static InMemoryLedgerView.Builder base() {
        return InMemoryLedgerView.builder()
                .protocolParams(Fixtures.protocolParams())
                .utxo(BASE_UTXO.txHash(), BASE_UTXO.index(), Fixtures.output(10));
    }

    @Test
    void applyReturnsNewViewAndLeavesOldUnchanged() {
        OverlayLedgerView v0 = OverlayLedgerView.over(base().build());
        Outpoint produced = new Outpoint(TX1, 0);
        OverlayLedgerView v1 = v0.apply(utxoEffects(TX1, List.of(BASE_UTXO),
                List.of(new UtxoEntry(produced, Fixtures.output(9)))));

        assertThat(v0.layerCount()).isZero();
        assertThat(v1.layerCount()).isEqualTo(1);
        assertThat(v0.utxo(BASE_UTXO).isPresent()).isTrue();
        assertThat(v1.utxo(BASE_UTXO).isAbsent()).isTrue();
        assertThat(v0.utxo(produced).isAbsent()).isTrue();
        assertThat(v1.utxo(produced).isPresent()).isTrue();
        assertThat(v1.layerTxIds()).containsExactly(TX1);
    }

    @Test
    void newestLayerWinsAndTruncateRestoresEarlierState() {
        CredentialKey a = keyCred(1);
        OverlayLedgerView v0 = OverlayLedgerView.over(base().build());
        OverlayLedgerView v1 = v0.apply(changes(TX1, new AccountRegistered(a, KEY_DEPOSIT)));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new StakeDelegated(a, POOL)));
        OverlayLedgerView v3 = v2.apply(changes(TX3, new AccountUnregistered(a, KEY_DEPOSIT)));

        assertThat(v1.account(a).require("a").delegatedPool()).isNull();
        assertThat(v2.account(a).require("a").delegatedPool()).isEqualTo(POOL);
        assertThat(v3.account(a).isAbsent()).isTrue();

        OverlayLedgerView t1 = v3.truncateTo(1);
        assertThat(t1.layerCount()).isEqualTo(1);
        assertThat(t1.layerTxIds()).containsExactly(TX1);
        assertThat(t1.account(a).require("a").delegatedPool()).isNull();
        assertThat(v3.truncateTo(3)).isSameAs(v3);
        assertThat(v3.truncateTo(0).account(a).isAbsent()).isTrue();
        assertThat(v3.layerTxIds()).containsExactly(TX1, TX2, TX3);
        assertThatThrownBy(() -> v3.truncateTo(4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v3.truncateTo(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spendOfOverlayOutputAndTruncationBringsItBack() {
        Outpoint y = new Outpoint(TX1, 0);
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(utxoEffects(TX1, List.of(), List.of(new UtxoEntry(y, Fixtures.output(3)))));
        OverlayLedgerView v2 = v1.apply(utxoEffects(TX2, List.of(y), List.of()));

        assertThat(v2.utxo(y).isAbsent()).isTrue();
        assertThat(v2.truncateTo(1).utxo(y).isPresent()).isTrue();
    }

    @Test
    void consumedThenReproducedResolvesNewestFirst() {
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(utxoEffects(TX1, List.of(BASE_UTXO), List.of()));
        // Not possible for real transactions, but the overlay must still answer newest-first.
        OverlayLedgerView v2 = v1.apply(utxoEffects(TX2, List.of(),
                List.of(new UtxoEntry(BASE_UTXO, Fixtures.output(77)))));
        OverlayLedgerView v3 = v2.apply(utxoEffects(TX3, List.of(BASE_UTXO), List.of()));

        assertThat(v1.utxo(BASE_UTXO).isAbsent()).isTrue();
        assertThat(v2.utxo(BASE_UTXO).require("u").output().getValue().getCoin()).isEqualTo(BigInteger.valueOf(77));
        assertThat(v3.utxo(BASE_UTXO).isAbsent()).isTrue();
    }

    @Test
    void producedWinsOverConsumedInOneLayer() {
        // Haskell: (inputs ⋪ utxo) ∪ outputs.
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build()).apply(utxoEffects(TX1, List.of(BASE_UTXO),
                List.of(new UtxoEntry(BASE_UTXO, Fixtures.output(1)))));

        assertThat(v1.utxo(BASE_UTXO).isPresent()).isTrue();
    }

    @Test
    void outpointKeysAreCaseInsensitive() {
        Outpoint upper = new Outpoint(TX1.toUpperCase(), 0);
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(utxoEffects(TX1, List.of(), List.of(new UtxoEntry(upper, Fixtures.output(3)))));

        assertThat(v1.utxo(new Outpoint(TX1, 0)).isPresent()).isTrue();
        assertThat(v1.utxo(upper).isPresent()).isTrue();
    }

    @Test
    void poolReregistrationCancelsRetirementAndKeepsDeposit() {
        var params = Fixtures.poolRegistration(0x70, 0x11);
        LedgerView base = base().pool(params, POOL_DEPOSIT).build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base).apply(changes(TX1, new PoolRetirementScheduled(POOL, 105)));
        var reParams = Fixtures.poolRegistration(0x70, 0x12);
        OverlayLedgerView v2 = v1.apply(changes(TX2, new PoolReregistered(POOL, reParams)));

        assertThat(v1.pool(POOL).require("p").retiringEpoch()).isEqualTo(105L);
        PoolState after = v2.pool(POOL).require("p");
        assertThat(after.retiringEpoch()).isNull();
        assertThat(after.deposit()).isEqualTo(POOL_DEPOSIT);
        assertThat(after.params()).isSameAs(params);
        assertThat(after.futureParams()).isSameAs(reParams);
        assertThat(after.vrfKeyHashHex()).isEqualTo(hash32(0x11));
        assertThat(v2.poolByVrfKeyHash(hash32(0x11))).isEqualTo(Lookup.present(POOL));
        assertThat(v2.poolByVrfKeyHash(hash32(0x12))).isEqualTo(Lookup.present(POOL));

        // A second re-registration with a new VRF drops the previous future VRF (Pool.hs:289-297).
        OverlayLedgerView v3 = v2.apply(changes(TX3,
                new PoolReregistered(POOL, Fixtures.poolRegistration(0x70, 0x13))));
        assertThat(v3.poolByVrfKeyHash(hash32(0x12)).isAbsent()).isTrue();
        assertThat(v3.poolByVrfKeyHash(hash32(0x13))).isEqualTo(Lookup.present(POOL));
        assertThat(v3.poolByVrfKeyHash(hash32(0x11))).isEqualTo(Lookup.present(POOL));
    }

    @Test
    void newPoolRegistrationIndexesVrf() {
        PoolId fresh = new PoolId(hash28(0x71));
        var params = Fixtures.poolRegistration(0x71, 0x21);
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(changes(TX1, new LedgerChange.PoolRegistered(fresh, params, POOL_DEPOSIT)));

        PoolState pool = v1.pool(fresh).require("pool");
        assertThat(pool.deposit()).isEqualTo(POOL_DEPOSIT);
        assertThat(pool.params()).isSameAs(params);
        assertThat(pool.futureParams()).isNull();
        assertThat(v1.poolByVrfKeyHash(hash32(0x21))).isEqualTo(Lookup.present(fresh));
    }

    @Test
    void deregisterThenRegisterInTwoTransactions() {
        CredentialKey a = keyCred(1);
        LedgerView base = base()
                .account(new AccountState(a, BigInteger.valueOf(1_000_000), BigInteger.ZERO, POOL,
                        DRepTarget.ALWAYS_ABSTAIN))
                .build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base)
                .apply(changes(TX1, new AccountUnregistered(a, BigInteger.valueOf(1_000_000))));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new AccountRegistered(a, KEY_DEPOSIT)));

        assertThat(v1.account(a).isAbsent()).isTrue();
        AccountState reRegistered = v2.account(a).require("a");
        assertThat(reRegistered.deposit()).isEqualTo(KEY_DEPOSIT);
        assertThat(reRegistered.delegatedPool()).isNull();
        assertThat(reRegistered.drepDelegation()).isNull();
    }

    @Test
    void registerThenDelegateAcrossTwoTransactions() {
        CredentialKey b = keyCred(2);
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(changes(TX1, new AccountRegistered(b, KEY_DEPOSIT)));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new StakeDelegated(b, POOL),
                new VoteDelegated(b, DRepTarget.ALWAYS_NO_CONFIDENCE)));

        AccountState account = v2.account(b).require("b");
        assertThat(account.delegatedPool()).isEqualTo(POOL);
        assertThat(account.drepDelegation()).isEqualTo(DRepTarget.ALWAYS_NO_CONFIDENCE);
        assertThat(account.deposit()).isEqualTo(KEY_DEPOSIT);
    }

    @Test
    void withdrawalDrainsBalance() {
        CredentialKey a = keyCred(1);
        LedgerView base = base()
                .account(new AccountState(a, KEY_DEPOSIT, BigInteger.valueOf(55), null, null))
                .build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base)
                .apply(changes(TX1, new RewardWithdrawn(a, BigInteger.valueOf(55))));

        assertThat(v1.account(a).require("a").rewardBalance()).isZero();
    }

    @Test
    void proposalThenVoteVisibleAcrossTransactions() {
        GovActionId gaid = new GovActionId(TX1, 0);
        UpdateCommittee update = new UpdateCommittee(null, Set.of(),
                Map.of(Credential.fromKey(HexUtil.decodeHexString(hash28(0x33))), 150),
                new UnitInterval(BigInteger.ONE, BigInteger.TWO));
        ProposalState proposal = new ProposalState(gaid, GovActionType.UPDATE_COMMITTEE, update, null, 100, 106,
                Fixtures.GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(1));
        OverlayLedgerView v0 = OverlayLedgerView.over(base().build());
        OverlayLedgerView v1 = v0.apply(changes(TX1, new ProposalSubmitted(proposal)));
        OverlayLedgerView v2 = v1.apply(changes(TX2,
                new VoteCast(new Voter(Voter.Role.DREP, keyCred(9)), gaid, Vote.YES)));

        assertThat(v0.proposal(gaid).isAbsent()).isTrue();
        assertThat(v1.proposal(gaid)).isEqualTo(Lookup.present(proposal));
        assertThat(v2.proposal(gaid)).isEqualTo(Lookup.present(proposal));
        assertThat(v0.committeeCandidates().require("c")).isEmpty();
        assertThat(v2.committeeCandidates().require("c")).containsExactly(keyCred(0x33));
    }

    @Test
    void drepUnregistrationClearsOlderVoteDelegationsLazily() {
        CredentialKey a = keyCred(1);
        CredentialKey d = keyCred(0x40);
        LedgerView base = base()
                .account(new AccountState(a, KEY_DEPOSIT, BigInteger.ZERO, null, DRepTarget.credential(d)))
                .drep(new DRepState(d, Fixtures.DREP_DEPOSIT, 120L))
                .build();
        OverlayLedgerView v0 = OverlayLedgerView.over(base);
        OverlayLedgerView v1 = v0.apply(changes(TX1, new DRepUnregistered(d, Fixtures.DREP_DEPOSIT)));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new DRepRegistered(d, Fixtures.DREP_DEPOSIT, 130)));
        OverlayLedgerView v3 = v2.apply(changes(TX3, new VoteDelegated(a, DRepTarget.credential(d))));

        assertThat(v0.account(a).require("a").drepDelegation()).isEqualTo(DRepTarget.credential(d));
        assertThat(v1.drep(d).isAbsent()).isTrue();
        assertThat(v1.account(a).require("a").drepDelegation()).isNull();
        assertThat(v2.account(a).require("a").drepDelegation()).isNull();
        assertThat(v3.account(a).require("a").drepDelegation()).isEqualTo(DRepTarget.credential(d));
    }

    @Test
    void drepUnregistrationInSameLayerClearsDelegationReadLater() {
        CredentialKey a = keyCred(1);
        CredentialKey d = keyCred(0x40);
        LedgerView base = base()
                .account(new AccountState(a, KEY_DEPOSIT, BigInteger.ZERO, null, DRepTarget.credential(d)))
                .drep(new DRepState(d, Fixtures.DREP_DEPOSIT, 120L))
                .build();
        // Unregister the DRep, then touch the account in the same transaction.
        OverlayLedgerView v1 = OverlayLedgerView.over(base).apply(changes(TX1,
                new DRepUnregistered(d, Fixtures.DREP_DEPOSIT), new StakeDelegated(a, POOL)));

        AccountState account = v1.account(a).require("a");
        assertThat(account.delegatedPool()).isEqualTo(POOL);
        assertThat(account.drepDelegation()).isNull();
    }

    @Test
    void dormantBumpShiftsOlderDRepExpiriesAndResetsCounter() {
        CredentialKey active = keyCred(0x41);
        CredentialKey lapsed = keyCred(0x42);
        LedgerView base = base()
                .dormantEpochs(3)
                .drep(new DRepState(active, Fixtures.DREP_DEPOSIT, 105L))
                .drep(new DRepState(lapsed, Fixtures.DREP_DEPOSIT, 90L))
                .build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base)
                .apply(changes(TX1, new DormantDRepExpiriesBumped(3, 100)));

        assertThat(v1.dormantEpochs()).isEqualTo(Lookup.present(0L));
        assertThat(v1.drep(active).require("d").expiryEpoch()).isEqualTo(108L);
        // 90 + 3 < 100: unchanged (Certs.hs:324-328).
        assertThat(v1.drep(lapsed).require("d").expiryEpoch()).isEqualTo(90L);
        OverlayLedgerView v2 = v1.apply(changes(TX2, new DRepRegistered(keyCred(0x43), Fixtures.DREP_DEPOSIT, 120)));
        assertThat(v2.drep(keyCred(0x43)).require("d").expiryEpoch()).isEqualTo(120L);
        assertThat(v2.drep(active).require("d").expiryEpoch()).isEqualTo(108L);
    }

    @Test
    void committeeHotKeysAcrossLayers() {
        CredentialKey cold1 = keyCred(1);
        CredentialKey cold2 = keyCred(2);
        CredentialKey hot = keyCred(0x51);
        CredentialKey hot2 = keyCred(0x52);
        LedgerView base = base().committeeMember(new CommitteeMemberState(cold1, hot, false, 200L)).build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base)
                .apply(changes(TX1, new CommitteeHotAuthorized(cold1, hot2)));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new CommitteeHotAuthorized(cold2, hot2)));
        OverlayLedgerView v3 = v2.apply(changes(TX3, new CommitteeResigned(cold1)));

        assertThat(v1.committeeMembersByHot(hot).require("h")).isEmpty();
        assertThat(v1.committeeMemberByCold(cold1).require("c").expiryEpoch()).isEqualTo(200L);
        assertThat(v2.committeeMembersByHot(hot2).require("h"))
                .extracting(CommitteeMemberState::cold).containsExactly(cold1, cold2);
        assertThat(v2.committeeMemberByCold(cold2).require("c").isElected()).isFalse();
        assertThat(v3.committeeMembersByHot(hot2).require("h"))
                .extracting(CommitteeMemberState::cold).containsExactly(cold2);
        CommitteeMemberState resigned = v3.committeeMemberByCold(cold1).require("c");
        assertThat(resigned.resigned()).isTrue();
        assertThat(resigned.hot()).isNull();
        assertThat(OverlayLedgerView.over(base).committeeMembersByHot(hot).require("h")).hasSize(1);
    }

    @Test
    void freezeIsImmutableSnapshotOfLayers() {
        CredentialKey a = keyCred(1);
        OverlayLedgerView v1 = OverlayLedgerView.over(base().build())
                .apply(changes(TX1, new AccountRegistered(a, KEY_DEPOSIT)));
        OverlayLedgerView frozen = v1.freeze();
        v1.apply(changes(TX2, new AccountUnregistered(a, KEY_DEPOSIT)));

        assertThat(frozen).isSameAs(v1);
        assertThat(frozen.layerCount()).isEqualTo(1);
        assertThat(frozen.account(a).isPresent()).isTrue();
        assertThat(frozen.base()).isSameAs(v1.base());
    }

    @Test
    void unavailableBelowFailsApplyAndLeavesViewUnchanged() {
        CredentialKey a = keyCred(1);
        LedgerView base = base()
                .account(AccountState.registered(a, KEY_DEPOSIT))
                .unavailableKey(a)
                .build();
        OverlayLedgerView v0 = OverlayLedgerView.over(base);

        assertThatThrownBy(() -> v0.apply(changes(TX1, new StakeDelegated(a, POOL))))
                .isInstanceOf(LedgerStateUnavailableException.class);
        assertThat(v0.layerCount()).isZero();
        assertThat(v0.account(a).isUnavailable()).isTrue();
    }

    @Test
    void effectsForMissingStateAreRejected() {
        OverlayLedgerView v0 = OverlayLedgerView.over(base().build());

        assertThatThrownBy(() -> v0.apply(changes(TX1, new StakeDelegated(keyCred(9), POOL))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passThroughReadsComeFromBase() {
        LedgerView base = base()
                .treasury(BigInteger.valueOf(123))
                .guardrailScriptHash(hash28(0xcc))
                .build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base).apply(changes(TX1, new AccountRegistered(keyCred(1),
                KEY_DEPOSIT)));

        assertThat(v1.treasury()).isEqualTo(Lookup.present(BigInteger.valueOf(123)));
        assertThat(v1.guardrailScriptHash()).isEqualTo(Lookup.present(hash28(0xcc)));
        assertThat(v1.protocolParams().isPresent()).isTrue();
        assertThat(v1.enactedRoots().isPresent()).isTrue();
    }

    @Test
    void secondReregistrationDropsFutureVrfEvenWhenItIsTheActiveOne() {
        // Pool.hs:288-294: Map.delete of the previous future VRF, even if the active params use it.
        var params = Fixtures.poolRegistration(0x70, 0x11);
        LedgerView base = base().pool(params, POOL_DEPOSIT).build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base)
                .apply(changes(TX1, new PoolReregistered(POOL, Fixtures.poolRegistration(0x70, 0x11))));
        OverlayLedgerView v2 = v1.apply(changes(TX2,
                new PoolReregistered(POOL, Fixtures.poolRegistration(0x70, 0x12))));

        assertThat(v1.poolByVrfKeyHash(hash32(0x11))).isEqualTo(Lookup.present(POOL));
        assertThat(v2.pool(POOL).require("p").vrfKeyHashHex()).isEqualTo(hash32(0x11));
        assertThat(v2.poolByVrfKeyHash(hash32(0x11)).isAbsent()).isTrue();
        assertThat(v2.poolByVrfKeyHash(hash32(0x12))).isEqualTo(Lookup.present(POOL));
    }

    @Test
    void truncatingTheUnregisteringLayerRestoresTheDelegation() {
        CredentialKey a = keyCred(1);
        CredentialKey d = keyCred(0x40);
        LedgerView base = base()
                .account(new AccountState(a, KEY_DEPOSIT, BigInteger.ZERO, POOL, DRepTarget.credential(d)))
                .drep(new DRepState(d, Fixtures.DREP_DEPOSIT, 120L))
                .build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base).apply(changes(TX1, new RewardWithdrawn(a,
                BigInteger.ZERO)));
        OverlayLedgerView v2 = v1.apply(changes(TX2, new DRepUnregistered(d, Fixtures.DREP_DEPOSIT)));

        assertThat(v2.account(a).require("a").drepDelegation()).isNull();
        OverlayLedgerView evicted = v2.truncateTo(1);
        assertThat(evicted.account(a).require("a").drepDelegation()).isEqualTo(DRepTarget.credential(d));
        assertThat(evicted.drep(d).isPresent()).isTrue();
    }

    @Test
    void expiredProposalStaysReadableUntilABoundaryRemovesIt() {
        // Gov.hs:360-362, 605-607: an expired proposal is still in Proposals (VotingOnExpiredGovAction).
        GovActionId expired = new GovActionId(hash32(0x55), 0);
        ProposalState proposal = new ProposalState(expired, GovActionType.INFO_ACTION, null, null, 90, 96,
                Fixtures.GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(1));
        LedgerView base = base().proposal(proposal).build();
        OverlayLedgerView v1 = OverlayLedgerView.over(base).apply(changes(TX1, new AccountRegistered(keyCred(1),
                KEY_DEPOSIT)));

        assertThat(proposal.expiresAfterEpoch()).isLessThan(Fixtures.EPOCH);
        assertThat(base.proposal(expired)).isEqualTo(Lookup.present(proposal));
        assertThat(v1.proposal(expired)).isEqualTo(Lookup.present(proposal));
    }
}
