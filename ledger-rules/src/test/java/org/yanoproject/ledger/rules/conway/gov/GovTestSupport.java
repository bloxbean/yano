package org.yanoproject.ledger.rules.conway.gov;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;

import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.ConwayLedgerTransition;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.certs.CertsRule;
import org.yanoproject.ledger.rules.conway.ledger.LedgerPreChecks;
import org.yanoproject.ledger.rules.conway.utxow.UtxowRule;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.math.BigInteger;
import java.util.List;

/**
 * Transactions for the {@code GOV}, {@code LEDGER} and {@code MEMPOOL} tests, in the mutation world
 * ({@link MutationWorld}: {@code dev-77} and {@code dev-bb} registered, {@code dev-77}'s DRep and pool, {@code dev-77}
 * an elected committee member with hot key {@code dev-42}, an unelected member with hot key {@code dev-aa}, the standing
 * {@link MutationWorld#INFO_ACTION} and {@link MutationWorld#PARAMETER_CHANGE_ACTION}, no enacted roots, no guardrail
 * script, epoch 0).
 */
final class GovTestSupport {

    static final BigInteger DEPOSIT = MutationWorld.GOV_ACTION_DEPOSIT;
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    private GovTestSupport() {
    }

    static BigInteger ada(long amount) {
        return ADA.multiply(BigInteger.valueOf(amount));
    }

    /** A proposal with the world's deposit, returned to {@code dev-77}'s testnet account. */
    static ProposalProcedure proposal(GovAction action) {
        return proposal(action, TestKey.DEV_77, MutationWorld.NETWORK, DEPOSIT);
    }

    static ProposalProcedure proposal(GovAction action, TestKey returnKey, Network network, BigInteger deposit) {
        return ProposalProcedure.builder()
                .deposit(deposit)
                .rewardAccount(MutationWorld.rewardAccount(returnKey, network))
                .govAction(action)
                .anchor(new Anchor("https://example.com/proposal.json", new byte[32]))
                .build();
    }

    /**
     * The simple base spending {@link MutationWorld#GOV_INPUT} too, with {@code proposals} balanced as Haskell's value
     * conservation counts them: {@code ppGovActionDeposit} per proposal, whatever the stated deposit
     * ({@code conwayProposalsDeposits}, Conway/TxBody.hs:383-391).
     */
    static TxSpec proposing(ProposalProcedure... proposals) {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.GOV_INPUT);
        for (ProposalProcedure p : proposals) {
            spec.proposals.add(p);
        }
        spec.changeAdjust = DEPOSIT.multiply(BigInteger.valueOf(proposals.length)).negate();
        return spec;
    }

    /** The simple base with votes, signed by {@code signers} too. */
    static TxSpec voting(VotingProcedures votes, TestKey... signers) {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.votingProcedures = votes;
        for (TestKey key : signers) {
            if (!spec.signers.contains(key)) {
                spec.signers.add(key);
            }
        }
        return spec;
    }

    static VotingProcedures votes() {
        return new VotingProcedures();
    }

    static VotingProcedures vote(VotingProcedures votes, Voter voter, org.yanoproject.ledger.rules.view.model.GovActionId id) {
        votes.add(voter, new GovActionId(id.txHashHex(), id.index()), new VotingProcedure(Vote.YES, null));
        return votes;
    }

    static VotingProcedures vote(Voter voter, org.yanoproject.ledger.rules.view.model.GovActionId id) {
        return vote(votes(), voter, id);
    }

    static Voter drep(TestKey key) {
        return new Voter(VoterType.DREP_KEY_HASH, Credential.fromKey(key.keyHash()));
    }

    static Voter committee(TestKey hot) {
        return new Voter(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, Credential.fromKey(hot.keyHash()));
    }

    static Voter pool(TestKey operator) {
        return new Voter(VoterType.STAKING_POOL_KEY_HASH, Credential.fromKey(operator.keyHash()));
    }

    static GovActionId ccl(org.yanoproject.ledger.rules.view.model.GovActionId id) {
        return id == null ? null : new GovActionId(id.txHashHex(), id.index());
    }

    static List<String> run(TxSpec spec, int protocolMajor) {
        return run(spec, MutationWorld.view(protocolMajor), protocolMajor);
    }

    static List<String> run(TxSpec spec, LedgerView view, int protocolMajor) {
        return run(spec, view, EngineTestSupport.env(protocolMajor), TxValidationRequest.Rule.LEDGER);
    }

    static List<String> run(TxSpec spec, LedgerView view, ValidationEnv env, TxValidationRequest.Rule rule) {
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        return EngineTestSupport.names(EngineTestSupport.validateWith(new JavaLedgerValidationEngine(new StubEvaluator()),
                cbor, view, env, rule));
    }

    /**
     * Validates with an engine whose {@code GOV} identifies this transaction's proposals as {@code (proposalTxId, i)},
     * so a proposal or vote can name them (see {@link GovRule#apply(org.yanoproject.ledger.rules.conway.RuleFrame,
     * String)}).
     */
    static List<String> runNamingOwnProposals(TxSpec spec, String proposalTxId, int protocolMajor) {
        LedgerView view = MutationWorld.view(protocolMajor);
        ConwayLedgerTransition transition = new ConwayLedgerTransition(LedgerPreChecks::apply, CertsRule::apply,
                frame -> GovRule.apply(frame, proposalTxId), UtxowRule::apply);
        JavaLedgerValidationEngine engine = new JavaLedgerValidationEngine(new StubEvaluator(),
                ConwayLedgerConstants.HASKELL, transition);
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        return EngineTestSupport.names(EngineTestSupport.validateWith(engine, cbor, view,
                EngineTestSupport.env(protocolMajor), TxValidationRequest.Rule.LEDGER));
    }
}
