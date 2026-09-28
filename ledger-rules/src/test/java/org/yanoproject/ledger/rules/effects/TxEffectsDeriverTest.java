package org.yanoproject.ledger.rules.effects;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.MoveInstataneous;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.effects.LedgerChange.AccountRegistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.AccountUnregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.CommitteeHotAuthorized;
import org.yanoproject.ledger.rules.effects.LedgerChange.CommitteeResigned;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepActivityUpdated;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepRegistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepUnregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.DRepUpdated;
import org.yanoproject.ledger.rules.effects.LedgerChange.DormantDRepExpiriesBumped;
import org.yanoproject.ledger.rules.effects.LedgerChange.PoolRegistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.PoolReregistered;
import org.yanoproject.ledger.rules.effects.LedgerChange.PoolRetirementScheduled;
import org.yanoproject.ledger.rules.effects.LedgerChange.ProposalSubmitted;
import org.yanoproject.ledger.rules.effects.LedgerChange.RewardWithdrawn;
import org.yanoproject.ledger.rules.effects.LedgerChange.StakeDelegated;
import org.yanoproject.ledger.rules.effects.LedgerChange.VoteCast;
import org.yanoproject.ledger.rules.effects.LedgerChange.VoteDelegated;
import org.yanoproject.ledger.rules.view.Fixtures;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.view.Fixtures.DREP_ACTIVITY;
import static org.yanoproject.ledger.rules.view.Fixtures.DREP_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.EPOCH;
import static org.yanoproject.ledger.rules.view.Fixtures.GOV_ACTION_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.GOV_ACTION_LIFETIME;
import static org.yanoproject.ledger.rules.view.Fixtures.KEY_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.POOL_DEPOSIT;
import static org.yanoproject.ledger.rules.view.Fixtures.hash28;
import static org.yanoproject.ledger.rules.view.Fixtures.hash32;
import static org.yanoproject.ledger.rules.view.Fixtures.keyCred;

class TxEffectsDeriverTest {

    private static final String TX_ID = hash32(0xee);
    private static final String IN_TX = hash32(0x01);
    private static final PoolId POOL = new PoolId(hash28(0x70));
    private static final ProtocolParams PP = Fixtures.protocolParams();

    private final TxEffectsDeriver deriver = new TxEffectsDeriver();

    // ------------------------------------------------------------------ helpers

    private static StakeCredential stakeCred(int b) {
        return StakeCredential.fromKeyHash(HexUtil.decodeHexString(hash28(b)));
    }

    private static Credential cred(int b) {
        return Credential.fromKey(HexUtil.decodeHexString(hash28(b)));
    }

    private static TransactionBody.TransactionBodyBuilder body() {
        return TransactionBody.builder()
                .inputs(List.of(new TransactionInput(IN_TX, 0)))
                .outputs(List.of(Fixtures.output(1_000_000)))
                .fee(BigInteger.valueOf(200_000));
    }

    private static Transaction tx(TransactionBody body) {
        Transaction tx = new Transaction();
        tx.setBody(body);
        tx.setWitnessSet(new TransactionWitnessSet());
        tx.setValid(true);
        return tx;
    }

    private static Transaction txWithCerts(Certificate... certs) {
        return tx(body().certs(List.of(certs)).build());
    }

    private static InMemoryLedgerView.Builder base() {
        return InMemoryLedgerView.builder().protocolParams(PP);
    }

    private TxEffects derive(Transaction tx, LedgerView pre) {
        return deriver.derive(null, tx, TX_ID, pre, Fixtures.env(), true);
    }

    // ------------------------------------------------------------------ UTxO

    @Test
    void simpleSpendConsumesInputsAndProducesIndexedOutputs() {
        TransactionOutput first = Fixtures.output(1);
        TransactionOutput second = Fixtures.output(2);
        Transaction tx = tx(body()
                .inputs(List.of(new TransactionInput(IN_TX, 3), new TransactionInput(hash32(2).toUpperCase(), 0)))
                .outputs(List.of(first, second))
                .build());

        TxEffects effects = derive(tx, base().build());

        assertThat(effects.txId()).isEqualTo(TX_ID);
        assertThat(effects.phase2Valid()).isTrue();
        assertThat(effects.consumed()).containsExactly(new Outpoint(IN_TX, 3), new Outpoint(hash32(2), 0));
        assertThat(effects.produced()).containsExactly(
                new UtxoEntry(new Outpoint(TX_ID, 0), first), new UtxoEntry(new Outpoint(TX_ID, 1), second));
        assertThat(effects.changes()).isEmpty();
    }

    @Test
    void phase2InvalidConsumesCollateralAndProducesReturnAfterOutputs() {
        TransactionOutput collateralReturn = Fixtures.output(4_000_000);
        Transaction tx = tx(body()
                .outputs(List.of(Fixtures.output(1), Fixtures.output(2), Fixtures.output(3)))
                .collateral(List.of(new TransactionInput(hash32(0xc0), 1)))
                .collateralReturn(collateralReturn)
                .certs(List.of(new StakeRegistration(stakeCred(1))))
                .withdrawals(List.of(new Withdrawal(Fixtures.rewardAddressHex(1), BigInteger.ONE)))
                .build());
        tx.setValid(false);

        TxEffects effects = deriver.derive(null, tx, TX_ID, base().build(), Fixtures.env(), false);

        assertThat(effects.phase2Valid()).isFalse();
        assertThat(effects.consumed()).containsExactly(new Outpoint(hash32(0xc0), 1));
        // Babbage/Collateral.hs:52-60: TxIx = length outputs.
        assertThat(effects.produced()).containsExactly(new UtxoEntry(new Outpoint(TX_ID, 3), collateralReturn));
        assertThat(effects.changes()).isEmpty();
    }

    @Test
    void phase2InvalidWithoutCollateralReturnProducesNothing() {
        Transaction tx = tx(body().collateral(List.of(new TransactionInput(hash32(0xc0), 0))).build());

        TxEffects effects = deriver.derive(null, tx, TX_ID, base().build(), Fixtures.env(), false);

        assertThat(effects.consumed()).containsExactly(new Outpoint(hash32(0xc0), 0));
        assertThat(effects.produced()).isEmpty();
    }

    @Test
    void txIdIsComputedFromOriginalBytesWhenNotGiven() throws Exception {
        Transaction tx = tx(body().build());
        byte[] cbor = tx.serialize();

        TxEffects effects = deriver.derive(cbor, tx, null, base().build(), Fixtures.env(), true);

        assertThat(effects.txId()).isEqualTo(TransactionUtil.getTxHash(cbor)).isEqualTo(TxIdentity.txIdHex(cbor));
        assertThat(effects.produced().getFirst().outpoint().txHash()).isEqualTo(effects.txId());
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void registerThenDelegateInOneTransaction() {
        CredentialKey a = keyCred(1);
        Transaction tx = txWithCerts(new RegCert(stakeCred(1), KEY_DEPOSIT),
                new StakeDelegation(stakeCred(1), new StakePoolId(HexUtil.decodeHexString(POOL.hashHex()))));

        TxEffects effects = derive(tx, base().build());

        assertThat(effects.changes()).containsExactly(new AccountRegistered(a, KEY_DEPOSIT),
                new StakeDelegated(a, POOL));
        AccountState after = OverlayLedgerView.over(base().build()).apply(effects).account(a).require("a");
        assertThat(after.delegatedPool()).isEqualTo(POOL);
        assertThat(after.deposit()).isEqualTo(KEY_DEPOSIT);
    }

    @Test
    void registerThenDeregisterRefundsTheDepositJustPaid() {
        CredentialKey a = keyCred(1);
        Transaction tx = txWithCerts(new StakeRegistration(stakeCred(1)), new StakeDeregistration(stakeCred(1)));

        TxEffects effects = derive(tx, base().build());

        assertThat(effects.changes()).containsExactly(new AccountRegistered(a, KEY_DEPOSIT),
                new AccountUnregistered(a, KEY_DEPOSIT));
        assertThat(OverlayLedgerView.over(base().build()).apply(effects).account(a).isAbsent()).isTrue();
    }

    @Test
    void refundComesFromRecordedDepositNotCurrentParameter() {
        CredentialKey a = keyCred(1);
        BigInteger oldDeposit = BigInteger.valueOf(1_000_000);
        LedgerView pre = base().account(AccountState.registered(a, oldDeposit)).build();
        Transaction tx = txWithCerts(new UnregCert(stakeCred(1), oldDeposit), new RegCert(stakeCred(1), KEY_DEPOSIT),
                new UnregCert(stakeCred(1), KEY_DEPOSIT));

        TxEffects effects = derive(tx, pre);

        assertThat(effects.changes()).containsExactly(new AccountUnregistered(a, oldDeposit),
                new AccountRegistered(a, KEY_DEPOSIT), new AccountUnregistered(a, KEY_DEPOSIT));
    }

    @Test
    void legacyStakeRegistrationTakesDepositFromProtocolParameters() {
        TxEffects effects = derive(txWithCerts(new StakeRegistration(stakeCred(5))), base().build());

        assertThat(effects.changes()).containsExactly(new AccountRegistered(keyCred(5), KEY_DEPOSIT));
    }

    @Test
    void combinedRegistrationDelegationCertificate() {
        CredentialKey a = keyCred(1);
        TxEffects effects = derive(txWithCerts(new StakeVoteRegDelegCert(stakeCred(1), POOL.hashHex(),
                DRep.abstain(), KEY_DEPOSIT)), base().build());

        assertThat(effects.changes()).containsExactly(new AccountRegistered(a, KEY_DEPOSIT),
                new StakeDelegated(a, POOL), new VoteDelegated(a, DRepTarget.ALWAYS_ABSTAIN));
    }

    @Test
    void withdrawalDrainsBeforeCertificates() {
        CredentialKey a = keyCred(1);
        LedgerView pre = base().account(new AccountState(a, KEY_DEPOSIT, BigInteger.valueOf(55), null, null)).build();
        Transaction tx = tx(body()
                .withdrawals(List.of(new Withdrawal(Fixtures.rewardAddressHex(1), BigInteger.valueOf(55))))
                .certs(List.of(new StakeDeregistration(stakeCred(1))))
                .build());

        TxEffects effects = derive(tx, pre);

        assertThat(effects.changes()).containsExactly(new RewardWithdrawn(a, BigInteger.valueOf(55)),
                new AccountUnregistered(a, KEY_DEPOSIT));
    }

    @Test
    void withdrawalFromBech32RewardAddress() {
        CredentialKey a = keyCred(1);
        String bech32 = new Address(
                HexUtil.decodeHexString(Fixtures.rewardAddressHex(1))).toBech32();
        LedgerView pre = base().account(new AccountState(a, KEY_DEPOSIT, BigInteger.TEN, null, null)).build();
        Transaction tx = tx(body().withdrawals(List.of(new Withdrawal(bech32, BigInteger.TEN))).build());

        TxEffects effects = derive(tx, pre);

        assertThat(effects.changes()).containsExactly(new RewardWithdrawn(a, BigInteger.TEN));
        assertThat(OverlayLedgerView.over(pre).apply(effects).account(a).require("a").rewardBalance()).isZero();
    }

    // ------------------------------------------------------------------ pools

    @Test
    void newPoolPaysDepositAndRegisteredPoolReregisters() {
        var fresh = Fixtures.poolRegistration(0x71, 0x21);
        var existing = Fixtures.poolRegistration(0x70, 0x11);
        LedgerView pre = base().pool(existing, POOL_DEPOSIT).build();
        var update = Fixtures.poolRegistration(0x70, 0x12);

        TxEffects effects = derive(txWithCerts(fresh, update, fresh), pre);

        PoolId freshId = new PoolId(hash28(0x71));
        assertThat(effects.changes()).containsExactly(new PoolRegistered(freshId, fresh, POOL_DEPOSIT),
                new PoolReregistered(POOL, update), new PoolReregistered(freshId, fresh));
    }

    @Test
    void poolRetirementThenReregistrationCancelsRetirement() {
        var existing = Fixtures.poolRegistration(0x70, 0x11);
        LedgerView pre = base().pool(existing, POOL_DEPOSIT).build();
        Transaction tx = txWithCerts(new PoolRetirement(HexUtil.decodeHexString(POOL.hashHex()), EPOCH + 5),
                existing);

        TxEffects effects = derive(tx, pre);

        assertThat(effects.changes()).containsExactly(new PoolRetirementScheduled(POOL, EPOCH + 5),
                new PoolReregistered(POOL, existing));
        assertThat(OverlayLedgerView.over(pre).apply(effects).pool(POOL).require("p").retiringEpoch()).isNull();
    }

    // ------------------------------------------------------------------ DReps and committee

    @Test
    void drepRegistrationUsesDepositParameterAndDormantAdjustedExpiry() {
        CredentialKey d = keyCred(0x40);
        LedgerView pre = base().dormantEpochs(2).build();

        TxEffects effects = derive(txWithCerts(new RegDRepCert(cred(0x40), DREP_DEPOSIT, null)), pre);

        // computeDRepExpiry: 100 + 20 - 2 (GovCert.hs:294-306).
        assertThat(effects.changes()).containsExactly(
                new DRepRegistered(d, DREP_DEPOSIT, EPOCH + DREP_ACTIVITY - 2));
    }

    @Test
    void drepUnregistrationRefundsRecordedDeposit() {
        CredentialKey d = keyCred(0x40);
        BigInteger oldDeposit = BigInteger.valueOf(400_000_000);
        LedgerView pre = base().drep(new DRepState(d, oldDeposit, 110L)).build();

        TxEffects effects = derive(txWithCerts(new UpdateDRepCert(cred(0x40), null),
                new UnregDRepCert(cred(0x40), oldDeposit), new RegDRepCert(cred(0x40), DREP_DEPOSIT, null),
                new UnregDRepCert(cred(0x40), DREP_DEPOSIT)), pre);

        assertThat(effects.changes()).containsExactly(
                new DRepUpdated(d, EPOCH + DREP_ACTIVITY),
                new DRepUnregistered(d, oldDeposit),
                new DRepRegistered(d, DREP_DEPOSIT, EPOCH + DREP_ACTIVITY),
                new DRepUnregistered(d, DREP_DEPOSIT));
    }

    @Test
    void committeeAuthorizationAndResignation() {
        TxEffects effects = derive(txWithCerts(new AuthCommitteeHotCert(cred(1), cred(0x51)),
                new ResignCommitteeColdCert(cred(2), null)), base().build());

        assertThat(effects.changes()).containsExactly(new CommitteeHotAuthorized(keyCred(1), keyCred(0x51)),
                new CommitteeResigned(keyCred(2)));
    }

    // ------------------------------------------------------------------ governance

    @Test
    void proposalsGetIndexedIdsAndLifetimeExpiry() {
        var prev = new com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId(hash32(0x99), 2);
        ParameterChangeAction change = new ParameterChangeAction(prev, null, null);
        UpdateCommittee update = new UpdateCommittee(null, Set.of(), Map.of(cred(0x33), 150),
                new UnitInterval(BigInteger.ONE, BigInteger.TWO));
        Transaction tx = tx(body().proposalProcedures(List.of(
                new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(1), change, null),
                new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(2), new InfoAction(), null),
                new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(3), update, null))).build());

        TxEffects effects = derive(tx, base().build());

        List<ProposalState> proposals = effects.changes().stream()
                .map(c -> ((ProposalSubmitted) c).proposal()).toList();
        assertThat(proposals).extracting(ProposalState::id).containsExactly(
                new GovActionId(TX_ID, 0), new GovActionId(TX_ID, 1), new GovActionId(TX_ID, 2));
        assertThat(proposals).extracting(ProposalState::type).containsExactly(
                GovActionType.PARAMETER_CHANGE_ACTION, GovActionType.INFO_ACTION, GovActionType.UPDATE_COMMITTEE);
        // mkGovActionState: gasExpiresAfter = currentEpoch + govActionLifetime (Gov.hs:409-417).
        assertThat(proposals).allSatisfy(p -> {
            assertThat(p.proposedEpoch()).isEqualTo(EPOCH);
            assertThat(p.expiresAfterEpoch()).isEqualTo(EPOCH + GOV_ACTION_LIFETIME);
            assertThat(p.deposit()).isEqualTo(GOV_ACTION_DEPOSIT);
        });
        assertThat(proposals.get(0).prevActionId()).isEqualTo(new GovActionId(hash32(0x99), 2));
        assertThat(proposals.get(1).prevActionId()).isNull();
        assertThat(proposals.get(0).returnAddress()).isEqualTo(Fixtures.rewardAddressHex(1));

        OverlayLedgerView after = OverlayLedgerView.over(base().build()).apply(effects);
        assertThat(after.proposal(new GovActionId(TX_ID, 2)).isPresent()).isTrue();
        assertThat(after.committeeCandidates().require("c")).containsExactly(keyCred(0x33));
    }

    @Test
    void submittedParameterChangesCarryTheirUpdateKeysFromTheTransactionBytes() throws Exception {
        ParameterChangeAction change = new ParameterChangeAction(null,
                ProtocolParamUpdate.builder().maxTxSize(16_384).collateralPercent(150).build(), null);
        Anchor anchor = new Anchor("https://example.org", new byte[32]);
        String rewardAccount = new Address(HexUtil.decodeHexString(Fixtures.rewardAddressHex(1))).toBech32();
        Transaction tx = tx(body().proposalProcedures(List.of(
                new ProposalProcedure(GOV_ACTION_DEPOSIT, rewardAccount, change, anchor),
                new ProposalProcedure(GOV_ACTION_DEPOSIT, rewardAccount, new InfoAction(), anchor)))
                .build());
        byte[] txCbor = tx.serialize();

        TxEffects effects = deriver.derive(txCbor, tx, null, base().build(), Fixtures.env(), true);

        List<ProposalState> proposals = effects.changes().stream()
                .filter(c -> c instanceof ProposalSubmitted)
                .map(c -> ((ProposalSubmitted) c).proposal()).toList();
        assertThat(proposals.get(0).paramUpdateKeys()).containsExactlyInAnyOrder(3, 23);
        assertThat(proposals.get(0).anyInSecurityGroup()).isTrue();
        assertThat(proposals.get(1).paramUpdateKeys()).isNull();
        assertThat(proposals.get(1).anyInSecurityGroup()).isFalse();
    }

    @Test
    void proposalEndsDormancyBeforeCertificatesAndVotesRefreshDRepExpiry() {
        CredentialKey d = keyCred(0x40);
        LedgerView pre = base().dormantEpochs(4).drep(new DRepState(d, DREP_DEPOSIT, 103L)).build();
        Map<com.bloxbean.cardano.client.transaction.spec.governance.Voter,
                Map<com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId, VotingProcedure>>
                voting = new LinkedHashMap<>();
        var ownProposal = new com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId(TX_ID, 0);
        voting.put(new com.bloxbean.cardano.client.transaction.spec.governance.Voter(VoterType.DREP_KEY_HASH,
                cred(0x40)), Map.of(ownProposal, new VotingProcedure(Vote.YES, null)));
        voting.put(new com.bloxbean.cardano.client.transaction.spec.governance.Voter(VoterType.DREP_KEY_HASH,
                cred(0x41)), Map.of(ownProposal, new VotingProcedure(Vote.NO, null)));
        Transaction tx = tx(body()
                .proposalProcedures(List.of(new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(1),
                        new InfoAction(), null)))
                .votingProcedures(new VotingProcedures(voting))
                .certs(List.of(new RegDRepCert(cred(0x42), DREP_DEPOSIT, null)))
                .build());

        TxEffects effects = derive(tx, pre);

        List<LedgerChange> changes = effects.changes();
        assertThat(changes.get(0)).isEqualTo(new DormantDRepExpiriesBumped(4, EPOCH));
        // Voting DRep refreshed with the counter already reset; the unregistered voter is skipped.
        assertThat(changes.get(1)).isEqualTo(new DRepActivityUpdated(d, EPOCH + DREP_ACTIVITY));
        // Registration after the reset ignores the old dormant count.
        assertThat(changes.get(2)).isEqualTo(new DRepRegistered(keyCred(0x42), DREP_DEPOSIT, EPOCH + DREP_ACTIVITY));
        assertThat(changes.get(3)).isInstanceOf(ProposalSubmitted.class);
        assertThat(changes.subList(4, changes.size())).containsExactly(
                new VoteCast(new Voter(Voter.Role.DREP, d), new GovActionId(TX_ID, 0), Vote.YES),
                new VoteCast(new Voter(Voter.Role.DREP, keyCred(0x41)), new GovActionId(TX_ID, 0), Vote.NO));

        OverlayLedgerView after = OverlayLedgerView.over(pre).apply(effects);
        assertThat(after.dormantEpochs().require("d")).isZero();
        assertThat(after.drep(d).require("d").expiryEpoch()).isEqualTo(EPOCH + DREP_ACTIVITY);
    }

    @Test
    void votesOnEarlierProposalIncludeStakePoolVoters() {
        GovActionId gaid = new GovActionId(hash32(0x77), 0);
        LedgerView pre = base().proposal(new ProposalState(gaid, GovActionType.INFO_ACTION, new InfoAction(), null,
                98, 104, GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(1))).build();
        var voter = new com.bloxbean.cardano.client.transaction.spec.governance.Voter(
                VoterType.STAKING_POOL_KEY_HASH, Credential.fromKey(HexUtil.decodeHexString(POOL.hashHex())));
        Map<com.bloxbean.cardano.client.transaction.spec.governance.Voter,
                Map<com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId, VotingProcedure>>
                voting = Map.of(voter, Map.of(
                        new com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId(
                                gaid.txHashHex(), 0), new VotingProcedure(Vote.ABSTAIN, null)));
        Transaction tx = tx(body().votingProcedures(new VotingProcedures(voting)).build());

        TxEffects effects = derive(tx, pre);

        assertThat(effects.changes()).containsExactly(
                new VoteCast(new Voter(Voter.Role.STAKE_POOL, CredentialKey.key(POOL.hashHex())), gaid, Vote.ABSTAIN));
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    void unavailableReadThrows() {
        LedgerView pre = base().account(AccountState.registered(keyCred(1), KEY_DEPOSIT))
                .unavailableKey(keyCred(1)).build();

        assertThatThrownBy(() -> derive(txWithCerts(new StakeDeregistration(stakeCred(1))), pre))
                .isInstanceOf(LedgerStateUnavailableException.class);
    }

    @Test
    void unavailableDormantCountThrowsForProposals() {
        LedgerView pre = base().unavailable(InMemoryLedgerView.Area.GOVERNANCE).build();
        Transaction tx = tx(body().proposalProcedures(List.of(new ProposalProcedure(GOV_ACTION_DEPOSIT,
                Fixtures.rewardAddressHex(1), new InfoAction(), null))).build());

        assertThatThrownBy(() -> derive(tx, pre)).isInstanceOf(LedgerStateUnavailableException.class);
    }

    @Test
    void mirCertificateIsRejected() {
        assertThatThrownBy(() -> derive(txWithCerts(new MoveInstataneous()), base().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MoveInstataneous");
    }

    @Test
    void effectsAreIndependentOfCertificateFoldGranularity() {
        CredentialKey a = keyCred(1);
        LedgerView pre = base().drep(new DRepState(keyCred(0x40), DREP_DEPOSIT, 110L)).build();
        List<Certificate> certs = new ArrayList<>(List.of(
                new StakeRegistration(stakeCred(1)),
                new VoteDelegCert(stakeCred(1),
                        DRep.addrKeyHash(hash28(0x40))),
                new UnregDRepCert(cred(0x40), DREP_DEPOSIT)));

        TxEffects effects = derive(tx(body().certs(certs).build()), pre);
        AccountState after = OverlayLedgerView.over(pre).apply(effects).account(a).require("a");

        // The DRep deregistration in the same transaction clears the delegation made before it.
        assertThat(after.drepDelegation()).isNull();
        assertThat(effects.changes()).contains(new VoteDelegated(a, DRepTarget.credential(keyCred(0x40))));
    }

    @Test
    void pv9DRepRegistrationIgnoresDormantEpochsButUpdateDoesNot() {
        // computeDRepExpiryVersioned (GovCert.hs:280-292) vs computeDRepExpiry for updates (:256-272).
        CredentialKey d = keyCred(0x40);
        LedgerView pre = base().dormantEpochs(2).build();
        Transaction tx = txWithCerts(new RegDRepCert(cred(0x40), DREP_DEPOSIT, null),
                new UpdateDRepCert(cred(0x40), null));

        TxEffects pv9 = deriver.derive(null, tx, TX_ID, pre, Fixtures.env(9), true);
        TxEffects pv10 = derive(tx, pre);

        assertThat(pv9.changes()).containsExactly(new DRepRegistered(d, DREP_DEPOSIT, EPOCH + DREP_ACTIVITY),
                new DRepUpdated(d, EPOCH + DREP_ACTIVITY - 2));
        assertThat(pv10.changes()).containsExactly(new DRepRegistered(d, DREP_DEPOSIT, EPOCH + DREP_ACTIVITY - 2),
                new DRepUpdated(d, EPOCH + DREP_ACTIVITY - 2));
    }

    @Test
    void protocolParametersComeFromTheView() {
        LedgerView noParams = InMemoryLedgerView.builder().build();
        LedgerView unavailable = base().unavailable(InMemoryLedgerView.Area.PARAMS).build();

        assertThatThrownBy(() -> derive(txWithCerts(new StakeRegistration(stakeCred(1))), noParams))
                .isInstanceOf(LedgerStateUnavailableException.class);
        assertThatThrownBy(() -> derive(tx(body().build()), unavailable))
                .isInstanceOf(LedgerStateUnavailableException.class);
        ProtocolParams other = Fixtures.protocolParams();
        other.setKeyDeposit("3000000");
        assertThat(derive(txWithCerts(new StakeRegistration(stakeCred(1))), base().protocolParams(other).build())
                .changes()).containsExactly(new AccountRegistered(keyCred(1), BigInteger.valueOf(3_000_000)));
    }

    @Test
    void proposalWithoutDormancyDoesNotBump() {
        Transaction tx = tx(body().proposalProcedures(List.of(new ProposalProcedure(GOV_ACTION_DEPOSIT,
                Fixtures.rewardAddressHex(1), new InfoAction(), null))).build());

        TxEffects effects = derive(tx, base().dormantEpochs(0).build());

        assertThat(effects.changes()).hasSize(1).allMatch(c -> c instanceof ProposalSubmitted);
    }

    @Test
    void secondProposalTransactionAfterBumpLayerDoesNotBumpAgain() {
        LedgerView pre = base().dormantEpochs(3).drep(new DRepState(keyCred(0x40), DREP_DEPOSIT, 104L)).build();
        Transaction first = tx(body().proposalProcedures(List.of(new ProposalProcedure(GOV_ACTION_DEPOSIT,
                Fixtures.rewardAddressHex(1), new InfoAction(), null))).build());
        Transaction second = tx(body().inputs(List.of(new TransactionInput(IN_TX, 1)))
                .proposalProcedures(List.of(new ProposalProcedure(GOV_ACTION_DEPOSIT,
                        Fixtures.rewardAddressHex(2), new InfoAction(), null))).build());

        TxEffects firstEffects = derive(first, pre);
        OverlayLedgerView afterFirst = OverlayLedgerView.over(pre).apply(firstEffects);
        TxEffects secondEffects = deriver.derive(null, second, hash32(0xef), afterFirst, Fixtures.env(), true);
        OverlayLedgerView afterSecond = afterFirst.apply(secondEffects);

        assertThat(firstEffects.changes().getFirst()).isEqualTo(new DormantDRepExpiriesBumped(3, EPOCH));
        assertThat(secondEffects.changes()).noneMatch(c -> c instanceof DormantDRepExpiriesBumped);
        assertThat(afterSecond.drep(keyCred(0x40)).require("d").expiryEpoch()).isEqualTo(107L);
        assertThat(afterSecond.dormantEpochs().require("d")).isZero();
    }

    /**
     * Invariant 5: derivation leaves the pre-state untouched, and applying the effects as one
     * layer gives the same state as the stepwise fold (per certificate) and as a per-change fold,
     * for every key the transaction touches.
     */
    @Test
    void oneLayerApplyEqualsStepwiseFoldForEveryTouchedKey() {
        CredentialKey a = keyCred(1);
        CredentialKey b = keyCred(2);
        CredentialKey d = keyCred(0x40);
        CredentialKey d2 = keyCred(0x41);
        var existingPool = Fixtures.poolRegistration(0x70, 0x11);
        InMemoryLedgerView base = base()
                .dormantEpochs(2)
                .account(new AccountState(a, BigInteger.valueOf(1_000_000), BigInteger.valueOf(70), POOL,
                        DRepTarget.credential(d)))
                .account(new AccountState(b, KEY_DEPOSIT, BigInteger.ZERO, null, DRepTarget.credential(d)))
                .drep(new DRepState(d, DREP_DEPOSIT, 101L))
                .drep(new DRepState(d2, DREP_DEPOSIT, 99L))
                .pool(existingPool, POOL_DEPOSIT)
                .committeeMember(new CommitteeMemberState(keyCred(0x60), keyCred(0x61), false, 300L))
                .build();
        // A pre-state with a layer of its own, so the fold runs over an overlay.
        LedgerView pre = OverlayLedgerView.over(base).apply(TxEffects.ofChanges(hash32(0x0a),
                List.of(new AccountRegistered(keyCred(3), KEY_DEPOSIT))));

        var dVoter = new com.bloxbean.cardano.client.transaction.spec.governance.Voter(VoterType.DREP_KEY_HASH,
                cred(0x41));
        var ownProposal = new com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId(TX_ID, 1);
        UpdateCommittee update = new UpdateCommittee(null, Set.of(), Map.of(cred(0x62), 400),
                new UnitInterval(BigInteger.ONE, BigInteger.TWO));
        Transaction tx = tx(body()
                .withdrawals(List.of(new Withdrawal(Fixtures.rewardAddressHex(1), BigInteger.valueOf(70))))
                .certs(List.of(
                        new UnregCert(stakeCred(1), BigInteger.valueOf(1_000_000)),
                        new StakeVoteRegDelegCert(stakeCred(1), POOL.hashHex(), DRep.addrKeyHash(hash28(0x40)),
                                KEY_DEPOSIT),
                        Fixtures.poolRegistration(0x71, 0x21),
                        new StakeRegistration(stakeCred(4)),
                        new StakeDelegation(stakeCred(4), new StakePoolId(HexUtil.decodeHexString(hash28(0x71)))),
                        Fixtures.poolRegistration(0x70, 0x12),
                        new PoolRetirement(HexUtil.decodeHexString(hash28(0x71)), EPOCH + 3),
                        new UnregDRepCert(cred(0x40), DREP_DEPOSIT),
                        new RegDRepCert(cred(0x40), DREP_DEPOSIT, null),
                        new UpdateDRepCert(cred(0x41), null),
                        new AuthCommitteeHotCert(cred(0x62), cred(0x61)),
                        new ResignCommitteeColdCert(cred(0x60), null)))
                .proposalProcedures(List.of(
                        new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(2), update, null),
                        new ProposalProcedure(GOV_ACTION_DEPOSIT, Fixtures.rewardAddressHex(2), new InfoAction(),
                                null)))
                .votingProcedures(new VotingProcedures(Map.of(dVoter,
                        Map.of(ownProposal, new VotingProcedure(Vote.YES, null)))))
                .build());

        Touched touched = touchedKeys(derive(tx, pre).changes());
        Snapshot before = Snapshot.of(pre, touched);
        TxEffects effects = derive(tx, pre);

        // Pre-state unchanged by derivation, and derivation is deterministic.
        assertThat(Snapshot.of(pre, touched)).isEqualTo(before);
        assertThat(derive(tx, pre)).isEqualTo(effects);
        assertThat(before.accounts().get(a).require("a").deposit()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThat(before.dormant()).isEqualTo(Lookup.present(2L));

        // One layer vs the deriver's per-certificate fold (plus the governance step).
        LedgerView oneLayer = OverlayLedgerView.over(pre).apply(effects);
        ProtocolParams pp = pre.protocolParams().require("pp");
        IntraTxFold fold = IntraTxFold.start(TX_ID, pre);
        fold = fold.step(TxEffectsDeriver.preCertificateChanges(fold.current(), tx.getBody(), pp, Fixtures.env()));
        for (Certificate cert : tx.getBody().getCerts()) {
            fold = fold.step(TxEffectsDeriver.certificateChanges(fold.current(), cert, pp, Fixtures.env()));
        }
        List<LedgerChange> governance = effects.changes().subList(fold.changes().size(), effects.changes().size());
        assertThat(effects.changes().subList(0, fold.changes().size())).isEqualTo(fold.changes());
        LedgerView stepwise = fold.step(governance).current();

        // And vs one layer per change.
        OverlayLedgerView perChange = OverlayLedgerView.over(pre);
        for (LedgerChange change : effects.changes()) {
            perChange = perChange.apply(TxEffects.ofChanges(TX_ID, List.of(change)));
        }

        Snapshot expected = Snapshot.of(oneLayer, touched);
        assertThat(Snapshot.of(stepwise, touched)).isEqualTo(expected);
        assertThat(Snapshot.of(perChange, touched)).isEqualTo(expected);

        // Spot checks that the scenario exercised what it claims.
        assertThat(expected.accounts().get(a).require("a").deposit()).isEqualTo(KEY_DEPOSIT);
        assertThat(expected.accounts().get(a).require("a").delegatedPool()).isEqualTo(POOL);
        // The DRep deregistration later in the same transaction clears a's fresh delegation too.
        assertThat(expected.accounts().get(a).require("a").drepDelegation()).isNull();
        assertThat(expected.accounts().get(b).require("b").drepDelegation()).isNull();
        assertThat(expected.dreps().get(d).require("d").deposit()).isEqualTo(DREP_DEPOSIT);
        assertThat(expected.dreps().get(d2).require("d2").expiryEpoch()).isEqualTo(EPOCH + DREP_ACTIVITY);
        assertThat(expected.pools().get(new PoolId(hash28(0x71))).require("p").retiringEpoch()).isEqualTo(EPOCH + 3);
        assertThat(expected.candidates().require("c")).contains(keyCred(0x62));
        assertThat(expected.dormant()).isEqualTo(Lookup.present(0L));
    }

    /** Every key the changes touch, plus accounts and DReps affected by lazy rules. */
    private record Touched(Set<CredentialKey> accounts, Set<PoolId> pools, Set<String> vrfs, Set<CredentialKey> dreps,
                           Set<CredentialKey> colds, Set<CredentialKey> hots, Set<GovActionId> proposals) {
    }

    private static Touched touchedKeys(List<LedgerChange> changes) {
        Touched t = new Touched(new HashSet<>(Set.of(keyCred(1), keyCred(2), keyCred(3))), new HashSet<>(),
                new HashSet<>(), new HashSet<>(Set.of(keyCred(0x40), keyCred(0x41))), new HashSet<>(),
                new HashSet<>(), new HashSet<>());
        for (LedgerChange c : changes) {
            switch (c) {
                case AccountRegistered x -> t.accounts().add(x.credential());
                case AccountUnregistered x -> t.accounts().add(x.credential());
                case StakeDelegated x -> t.accounts().add(x.credential());
                case VoteDelegated x -> t.accounts().add(x.credential());
                case RewardWithdrawn x -> t.accounts().add(x.credential());
                case PoolRegistered x -> {
                    t.pools().add(x.pool());
                    t.vrfs().add(HexUtil.encodeHexString(x.params().getVrfKeyHash()));
                }
                case PoolReregistered x -> {
                    t.pools().add(x.pool());
                    t.vrfs().add(HexUtil.encodeHexString(x.params().getVrfKeyHash()));
                }
                case PoolRetirementScheduled x -> t.pools().add(x.pool());
                case DRepRegistered x -> t.dreps().add(x.credential());
                case DRepUpdated x -> t.dreps().add(x.credential());
                case DRepUnregistered x -> t.dreps().add(x.credential());
                case DRepActivityUpdated x -> t.dreps().add(x.credential());
                case DormantDRepExpiriesBumped x -> { }
                case CommitteeHotAuthorized x -> {
                    t.colds().add(x.cold());
                    t.hots().add(x.hot());
                }
                case CommitteeResigned x -> t.colds().add(x.cold());
                case ProposalSubmitted x -> t.proposals().add(x.proposal().id());
                case VoteCast x -> t.proposals().add(x.actionId());
            }
        }
        t.vrfs().add(hash32(0x11));
        t.hots().add(keyCred(0x61));
        return t;
    }

    private record Snapshot(Map<CredentialKey, Lookup<AccountState>> accounts, Map<PoolId, Lookup<PoolState>> pools,
                            Map<String, Lookup<PoolId>> vrfs, Map<CredentialKey, Lookup<DRepState>> dreps,
                            Map<CredentialKey, Lookup<CommitteeMemberState>> colds,
                            Map<CredentialKey, Lookup<List<CommitteeMemberState>>> hots,
                            Map<GovActionId, Lookup<ProposalState>> proposals,
                            Lookup<Set<CredentialKey>> candidates, Lookup<Long> dormant) {

        static Snapshot of(LedgerView v, Touched t) {
            Map<CredentialKey, Lookup<AccountState>> accounts = new HashMap<>();
            t.accounts().forEach(k -> accounts.put(k, v.account(k)));
            Map<PoolId, Lookup<PoolState>> pools = new HashMap<>();
            t.pools().forEach(k -> pools.put(k, v.pool(k)));
            Map<String, Lookup<PoolId>> vrfs = new HashMap<>();
            t.vrfs().forEach(k -> vrfs.put(k, v.poolByVrfKeyHash(k)));
            Map<CredentialKey, Lookup<DRepState>> dreps = new HashMap<>();
            t.dreps().forEach(k -> dreps.put(k, v.drep(k)));
            Map<CredentialKey, Lookup<CommitteeMemberState>> colds = new HashMap<>();
            t.colds().forEach(k -> colds.put(k, v.committeeMemberByCold(k)));
            Map<CredentialKey, Lookup<List<CommitteeMemberState>>> hots = new HashMap<>();
            t.hots().forEach(k -> hots.put(k, v.committeeMembersByHot(k)));
            Map<GovActionId, Lookup<ProposalState>> proposals = new HashMap<>();
            t.proposals().forEach(k -> proposals.put(k, v.proposal(k)));
            return new Snapshot(accounts, pools, vrfs, dreps, colds, hots, proposals, v.committeeCandidates(),
                    v.dormantEpochs());
        }
    }
}
