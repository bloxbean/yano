package org.yanoproject.ledger.rules.conway.mempool;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.Outpoints;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.view.Fixtures.hash28;
import static org.yanoproject.ledger.rules.view.Fixtures.hash32;
import static org.yanoproject.ledger.rules.view.Fixtures.output;
import static org.yanoproject.ledger.rules.view.Fixtures.protocolParams;

class MempoolRuleTest {

    private static final String UNSPENT = hash32(0x01);
    private static final String SPENT = hash32(0x02);

    private static InMemoryLedgerView.Builder view() {
        return InMemoryLedgerView.builder().protocolParams(protocolParams()).utxo(UNSPENT, 0, output(5_000_000));
    }

    private static TransactionBody body(List<TransactionInput> inputs, VotingProcedures votes) {
        return TransactionBody.builder().inputs(new ArrayList<>(inputs)).votingProcedures(votes).build();
    }

    private static TransactionInput input(String txId, int index) {
        return TransactionInput.builder().transactionId(txId).index(index).build();
    }

    private static VotingProcedures committeeVotes(Credential... hotCredentials) {
        VotingProcedures votes = VotingProcedures.builder().build();
        for (Credential hot : hotCredentials) {
            VoterType type = hot.getType() == CredentialType.Key
                    ? VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH
                    : VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH;
            votes.add(new Voter(type, hot), new GovActionId(hash32(0x77), 0),
                    new VotingProcedure(Vote.YES, null));
        }
        return votes;
    }

    @Test
    void allSpendingInputsSpentReportsOnlyTheMempoolFailure() {
        MempoolRule.Result result = MempoolRule.apply(body(List.of(input(SPENT, 0), input(UNSPENT, 7)), null),
                view().build(), 10);

        assertThat(result.continueToLedger()).isFalse();
        assertThat(result.failures()).containsExactly(new LedgerFailure(LedgerRuleName.LEDGER,
                "ConwayMempoolFailure", LedgerFailure.Phase.PHASE_1,
                "All inputs are spent. Transaction has probably already been included"));
    }

    @Test
    void oneUnspentInputIsEnough() {
        MempoolRule.Result result = MempoolRule.apply(body(List.of(input(SPENT, 0), input(UNSPENT, 0)), null),
                view().build(), 10);
        assertThat(result.passed()).isTrue();
        assertThat(result.continueToLedger()).isTrue();
    }

    @Test
    void anEmptyInputSetCountsAsAllSpent() {
        // Haskell: notAllSpent = any (`Map.member` utxo) inputs, and `any` of nothing is False.
        MempoolRule.Result result = MempoolRule.apply(body(List.of(), null), view().build(), 10);
        assertThat(result.continueToLedger()).isFalse();
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void unavailableInputFailsClosedUnlessAnotherInputIsKnownUnspent() {
        InMemoryLedgerView unavailable = view().unavailableKey(Outpoints.of(SPENT, 0)).build();
        assertThatThrownBy(() -> MempoolRule.apply(body(List.of(input(SPENT, 0)), null), unavailable, 10))
                .isInstanceOf(LedgerStateUnavailableException.class);
        assertThat(MempoolRule.apply(body(List.of(input(SPENT, 0), input(UNSPENT, 0)), null), unavailable, 10)
                .passed()).isTrue();
    }

    @Test
    void unelectedCommitteeVotersAreRejectedUpToProtocolVersion10AndLedgerStillRuns() {
        CredentialKey electedCold = CredentialKey.key(hash28(0x10));
        CredentialKey electedHot = CredentialKey.key(hash28(0x11));
        CredentialKey candidateCold = CredentialKey.key(hash28(0x20));
        CredentialKey candidateHot = CredentialKey.key(hash28(0x21));
        CredentialKey scriptHot = CredentialKey.script(hash28(0x31));
        InMemoryLedgerView state = view()
                .committeeMember(new CommitteeMemberState(electedCold, electedHot, false, 200L))
                .committeeMember(new CommitteeMemberState(candidateCold, candidateHot, false, null))
                .build();
        TransactionBody tx = body(List.of(input(UNSPENT, 0)), committeeVotes(
                Credential.fromKey(electedHot.hashHex()), Credential.fromKey(candidateHot.hashHex()),
                Credential.fromScript(scriptHot.hashHex())));

        MempoolRule.Result pv10 = MempoolRule.apply(tx, state, 10);
        assertThat(pv10.continueToLedger()).isTrue();
        assertThat(pv10.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.qualifiedName()).isEqualTo("LEDGER.ConwayMempoolFailure");
            // Haskell Set order: ScriptHashObj before KeyHashObj.
            assertThat(failure.detail()).isEqualTo(
                    "Unelected committee members are not allowed to cast votes: "
                            + "[ScriptHashObj (ScriptHash \"" + scriptHot.hashHex() + "\"),"
                            + "KeyHashObj (KeyHash {unKeyHash = \"" + candidateHot.hashHex() + "\"})]");
        });

        // From PV11 the check lives in GOV (UnelectedCommitteeVoters), not in MEMPOOL.
        assertThat(MempoolRule.apply(tx, state, 11).passed()).isTrue();
    }

    @Test
    void resignedOrUnauthorisedElectedMembersDoNotAuthoriseTheirOldHotKey() {
        CredentialKey cold = CredentialKey.key(hash28(0x10));
        CredentialKey hot = CredentialKey.key(hash28(0x11));
        InMemoryLedgerView resigned = view()
                .committeeMember(new CommitteeMemberState(cold, null, true, 200L))
                .build();
        TransactionBody tx = body(List.of(input(UNSPENT, 0)), committeeVotes(Credential.fromKey(hot.hashHex())));

        assertThat(MempoolRule.apply(tx, resigned, 10).passed()).isFalse();
    }
}
