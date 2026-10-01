package org.yanoproject.ledger.rules.conway.mempool;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;
import org.yanoproject.ledger.rules.view.model.Outpoints;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 5 {@code MEMPOOL} fixtures, through the whole Java transition ({@code MEMPOOL} in front of
 * {@code LEDGER}, Conway/Rules/Mempool.hs:103-138): the all-inputs-spent duplicate reports only
 * {@code ConwayMempoolFailure}; up to protocol version 10 an unelected committee vote is judged against the
 * <em>incoming</em> committee state, even when the transaction's own certificates would change it; from 11 the check is
 * {@code GOV}'s; and rule {@code LEDGER} (block selection, shadow sync) never reports a {@code MEMPOOL} failure.
 */
class MempoolTransitionTest {

    private static List<String> run(TxSpec spec, int protocolMajor, Rule rule) {
        InMemoryLedgerView view = MutationWorld.view(protocolMajor);
        return run(ConwayTxBuilder.build(spec, view).cbor(), view, protocolMajor, rule);
    }

    private static List<String> run(byte[] cbor, LedgerView view, int protocolMajor, Rule rule) {
        return EngineTestSupport.names(EngineTestSupport.validateWith(new JavaLedgerValidationEngine(
                new StubEvaluator()), cbor, view, MutationWorld.env(protocolMajor), rule));
    }

    /** The world after another transaction spent {@link MutationWorld#KEY_INPUT}. */
    private static LedgerView keyInputSpent(int protocolMajor) {
        TransactionInput key = MutationWorld.KEY_INPUT;
        return OverlayLedgerView.over(MutationWorld.view(protocolMajor)).apply(new TxEffects("ab".repeat(32), true,
                List.of(Outpoints.of(key.getTransactionId(), key.getIndex())), List.of(), List.of()));
    }

    /** A committee vote by {@code hot} on the world's standing info action. */
    private static VotingProcedures committeeVote(TestKey hot) {
        VotingProcedures votes = new VotingProcedures();
        votes.add(new Voter(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, Credential.fromKey(hot.keyHash())),
                new GovActionId(MutationWorld.INFO_ACTION.txHashHex(), MutationWorld.INFO_ACTION.index()),
                new VotingProcedure(Vote.YES, null));
        return votes;
    }

    @Test
    @Covers("LEDGER.ConwayMempoolFailure")
    void anAllInputsSpentDuplicateReportsOnlyTheMempoolFailure() {
        // Its only spending input was spent by another transaction (a duplicate), and it has another fault too: a
        // stated treasury value that is wrong. whenFailureFreeDefault skips all of LEDGER.
        TxSpec duplicate = MutationWorld.simpleSpec();
        duplicate.currentTreasuryValue = BigInteger.ONE;
        for (int pv : new int[]{10, 11}) {
            byte[] cbor = ConwayTxBuilder.build(duplicate, MutationWorld.view(pv)).cbor();
            assertThat(run(cbor, keyInputSpent(pv), pv, Rule.MEMPOOL)).containsExactly("LEDGER.ConwayMempoolFailure");
            // Rule LEDGER never reports MEMPOOL's failure: the same transaction fails with LEDGER's own predicates.
            assertThat(run(cbor, keyInputSpent(pv), pv, Rule.LEDGER)).doesNotContain("LEDGER.ConwayMempoolFailure")
                    .contains("UTXO.BadInputsUTxO", "LEDGER.ConwayTreasuryValueMismatch");
        }
        // One unspent spending input is enough to run LEDGER (rooted at MEMPOOL, LEDGER's list is reversed once more).
        TxSpec partly = MutationWorld.simpleSpec();
        partly.inputs.add(MutationWorld.RICH_INPUT);
        byte[] cbor = ConwayTxBuilder.build(partly, MutationWorld.view(10)).cbor();
        assertThat(run(cbor, keyInputSpent(10), 10, Rule.MEMPOOL)).containsExactly("UTXO.BadInputsUTxO",
                "UTXO.ValueNotConservedUTxO");
    }

    @Test
    void anUnelectedCommitteeVoteIsAMempoolFailureUpToProtocolVersion10() {
        // dev-aa's key is the hot key of a member without a term.
        TxSpec unelected = MutationWorld.simpleSpec();
        unelected.votingProcedures = committeeVote(TestKey.DEV_AA);
        unelected.signers.add(TestKey.DEV_AA);
        assertThat(run(unelected, 10, Rule.MEMPOOL)).containsExactly("LEDGER.ConwayMempoolFailure");
        assertThat(run(unelected, 10, Rule.LEDGER)).containsExactly("Valid");
        // From 11 the check moves to GOV, for blocks and the mempool alike, and MEMPOOL no longer reports it.
        assertThat(run(unelected, 11, Rule.MEMPOOL)).containsExactly("GOV.UnelectedCommitteeVoters");
        assertThat(run(unelected, 11, Rule.LEDGER)).containsExactly("GOV.UnelectedCommitteeVoters");
    }

    @Test
    void upToProtocolVersion10TheIncomingCommitteeStateDecides() {
        // The elected member dev-77 authorises dev-aa's key in the same transaction and dev-aa votes. MEMPOOL judges
        // the incoming state, where dev-aa's key belongs only to an unelected member; GOV (after CERTS) would accept.
        TxSpec authorised = MutationWorld.simpleSpec();
        authorised.certs.add(new AuthCommitteeHotCert(MutationWorld.credential(TestKey.DEV_77),
                MutationWorld.credential(TestKey.DEV_AA)));
        authorised.votingProcedures = committeeVote(TestKey.DEV_AA);
        authorised.signers.add(TestKey.DEV_AA);
        authorised.signers.add(TestKey.DEV_77);
        assertThat(run(authorised, 10, Rule.MEMPOOL)).containsExactly("LEDGER.ConwayMempoolFailure");
        assertThat(run(authorised, 10, Rule.LEDGER)).containsExactly("Valid");
        // At 11 GOV judges the post-CERTS state: the key is now an elected member's.
        assertThat(run(authorised, 11, Rule.MEMPOOL)).containsExactly("Valid");
    }

    @Test
    void theUnelectedVoteFailureDoesNotStopLedger() {
        // failOnNonEmpty records the failure and LEDGER still runs; MEMPOOL's list holds LEDGER's failures (reversed
        // once more) before its own.
        TxSpec both = MutationWorld.simpleSpec();
        both.votingProcedures = committeeVote(TestKey.DEV_AA);
        both.signers.add(TestKey.DEV_AA);
        both.currentTreasuryValue = BigInteger.ONE;
        assertThat(run(both, 10, Rule.MEMPOOL)).containsExactly("LEDGER.ConwayTreasuryValueMismatch",
                "LEDGER.ConwayMempoolFailure");
    }

    @Test
    void theElectedMembersHotKeyVotes() {
        TxSpec elected = MutationWorld.simpleSpec();
        elected.votingProcedures = committeeVote(TestKey.DEV_42);
        assertThat(run(elected, 10, Rule.MEMPOOL)).containsExactly("Valid");
        assertThat(run(elected, 11, Rule.MEMPOOL)).containsExactly("Valid");
    }
}
