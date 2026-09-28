package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.view.Fixtures.hash28;
import static org.yanoproject.ledger.rules.view.Fixtures.hash32;
import static org.yanoproject.ledger.rules.view.Fixtures.keyCred;

class InMemoryLedgerViewTest {

    @Test
    void unknownKeysAreAbsentNotUnavailable() {
        InMemoryLedgerView view = InMemoryLedgerView.builder().protocolParams(Fixtures.protocolParams()).build();

        assertThat(view.utxo(new Outpoint(hash32(1), 0)).isAbsent()).isTrue();
        assertThat(view.account(keyCred(1)).isAbsent()).isTrue();
        assertThat(view.pool(new PoolId(hash28(1))).isAbsent()).isTrue();
        assertThat(view.poolByVrfKeyHash(hash32(1)).isAbsent()).isTrue();
        assertThat(view.drep(keyCred(1)).isAbsent()).isTrue();
        assertThat(view.committeeMemberByCold(keyCred(1)).isAbsent()).isTrue();
        assertThat(view.committeeMembersByHot(keyCred(1))).isEqualTo(Lookup.present(List.of()));
        assertThat(view.proposal(new GovActionId(hash32(1), 0)).isAbsent()).isTrue();
        assertThat(view.guardrailScriptHash().isAbsent()).isTrue();
        assertThat(view.enactedRoots()).isEqualTo(Lookup.present(EnactedRoots.NONE));
        assertThat(view.dormantEpochs()).isEqualTo(Lookup.present(0L));
        assertThat(view.protocolParams().isPresent()).isTrue();
    }

    @Test
    void outpointLookupIgnoresHashCase() {
        InMemoryLedgerView view = InMemoryLedgerView.builder()
                .utxo(hash32(0xab).toUpperCase(), 1, Fixtures.output(5))
                .build();

        assertThat(view.utxo(new Outpoint(hash32(0xab), 1)).isPresent()).isTrue();
        assertThat(view.utxo(new Outpoint(hash32(0xab).toUpperCase(), 1)).isPresent()).isTrue();
    }

    @Test
    void injectedUnavailabilityByAreaAndKey() {
        CredentialKey broken = keyCred(2);
        InMemoryLedgerView view = InMemoryLedgerView.builder()
                .account(AccountState.registered(keyCred(1), BigInteger.TWO))
                .account(AccountState.registered(broken, BigInteger.TWO))
                .unavailableKey(broken)
                .unavailable(InMemoryLedgerView.Area.POOLS)
                .build();

        assertThat(view.account(keyCred(1)).isPresent()).isTrue();
        assertThat(view.account(broken).isUnavailable()).isTrue();
        assertThat(view.pool(new PoolId(hash28(9))).isUnavailable()).isTrue();
        assertThat(view.protocolParams().isUnavailable()).isTrue();
    }

    @Test
    void vrfIndexCoversActiveAndFutureParams() {
        var reg = Fixtures.poolRegistration(1, 0x11);
        var future = Fixtures.poolRegistration(1, 0x12);
        PoolId id = new PoolId(hash28(1));
        InMemoryLedgerView view = InMemoryLedgerView.builder()
                .pool(new PoolState(id, Fixtures.POOL_DEPOSIT, hash32(0x11),
                        null, reg, future))
                .build();

        assertThat(view.poolByVrfKeyHash(hash32(0x11))).isEqualTo(Lookup.present(id));
        assertThat(view.poolByVrfKeyHash(hash32(0x12))).isEqualTo(Lookup.present(id));
    }

    @Test
    void committeeCandidatesComeFromUpdateCommitteeProposals() {
        UpdateCommittee update = new UpdateCommittee(null, Set.of(),
                Map.of(Credential.fromKey(HexUtil.decodeHexString(hash28(7))), 200),
                new UnitInterval(BigInteger.ONE, BigInteger.TWO));
        GovActionId id = new GovActionId(hash32(3), 0);
        InMemoryLedgerView view = InMemoryLedgerView.builder()
                .proposal(new ProposalState(id, GovActionType.UPDATE_COMMITTEE, update, null, 90, 96,
                        BigInteger.TEN, Fixtures.rewardAddressHex(1)))
                .committeeCandidate(keyCred(8))
                .build();

        assertThat(view.committeeCandidates().require("candidates"))
                .containsExactlyInAnyOrder(keyCred(7), keyCred(8));
    }

    @Test
    void committeeMembersByHotReturnsEveryColdForTheHotKey() {
        CredentialKey hot = keyCred(0x50);
        InMemoryLedgerView view = InMemoryLedgerView.builder()
                .committeeMember(new CommitteeMemberState(keyCred(1), hot, false, 200L))
                .committeeMember(new CommitteeMemberState(keyCred(2), hot, false, 200L))
                .committeeMember(new CommitteeMemberState(keyCred(3), null, true, 200L))
                .build();

        assertThat(view.committeeMembersByHot(hot).require("hot"))
                .extracting(CommitteeMemberState::cold)
                .containsExactlyInAnyOrder(keyCred(1), keyCred(2));
    }
}
