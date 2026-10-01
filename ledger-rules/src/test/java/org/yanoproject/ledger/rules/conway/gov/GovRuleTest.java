package org.yanoproject.ledger.rules.conway.gov;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.spec.UnitInterval;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.ProtocolVersion;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.Constitution;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NewConstitution;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NoConfidence;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.BuiltTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.ProposalState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.DEPOSIT;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.ada;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.ccl;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.committee;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.drep;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.pool;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.proposal;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.proposing;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.run;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.runNamingOwnProposals;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.vote;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.votes;
import static org.yanoproject.ledger.rules.conway.gov.GovTestSupport.voting;

/**
 * {@code GOV} ({@code conwayGovTransition}, Conway/Rules/Gov.hs:446-613): one {@code @Covers} test per constructor
 * with Haskell's whole failure list (rooted at {@code LEDGER}), the lineage model (enacted root, in-flight parent,
 * same-transaction parent, wrong purpose), hard-fork version chaining, committee updates, the voter matrix including
 * stake-pool votes on security-group parameter changes, and the protocol-version-11 unelected committee check.
 */
class GovRuleTest {

    private static final String SAME_TX = "d0".repeat(32);

    private static byte[] nativeScriptHash() {
        try {
            return MutationWorld.NATIVE_SCRIPT.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ParameterChangeAction parameterChange(GovActionId parent, ProtocolParamUpdate update) {
        return new ParameterChangeAction(ccl(parent), update, null);
    }

    private static ProtocolParamUpdate collateralPercent(int value) {
        return ProtocolParamUpdate.builder().collateralPercent(value).build();
    }

    private static HardForkInitiationAction hardFork(GovActionId parent, int major, int minor) {
        return new HardForkInitiationAction(ccl(parent), new ProtocolVersion(major, minor));
    }

    private static TreasuryWithdrawalsAction treasuryWithdrawals(Withdrawal... withdrawals) {
        return new TreasuryWithdrawalsAction(new ArrayList<>(List.of(withdrawals)), null);
    }

    private static Withdrawal withdrawal(TestKey key, Network network, BigInteger amount) {
        return new Withdrawal(MutationWorld.rewardAccount(key, network), amount);
    }

    private static UpdateCommittee updateCommittee(Set<Credential> remove, Map<Credential, Integer> add) {
        return new UpdateCommittee(null, new LinkedHashSet<>(remove), new LinkedHashMap<>(add),
                new UnitInterval(BigInteger.TWO, BigInteger.valueOf(3)));
    }

    private static InMemoryLedgerView.Builder world(int protocolMajor) {
        return MutationWorld.builder(MutationWorld.protocolParams(protocolMajor));
    }

    private static ProposalState standing(GovActionId id, GovActionType type, GovAction action, Set<Integer> keys) {
        return new ProposalState(id, type, action, null, 0, MutationWorld.GOV_ACTION_LIFETIME, DEPOSIT,
                MutationWorld.rewardAccount(TestKey.DEV_77, MutationWorld.NETWORK), keys);
    }

    // ------------------------------------------------------------------------------------------------ proposals

    @Test
    void validProposalsOfEveryKind() {
        assertThat(run(proposing(proposal(new InfoAction())), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(parameterChange(null, collateralPercent(140)))), 10))
                .containsExactly("Valid");
        assertThat(run(proposing(proposal(hardFork(null, 11, 0))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(treasuryWithdrawals(withdrawal(TestKey.DEV_77, MutationWorld.NETWORK,
                ada(10))))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(new NoConfidence(null))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(updateCommittee(Set.of(MutationWorld.credential(TestKey.DEV_BB)),
                Map.of(MutationWorld.credential(TestKey.DEV_42), 50)))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(new NewConstitution(null, Constitution.builder()
                .anchor(new Anchor("https://example.com/constitution.txt", new byte[32])).build()))), 10))
                .containsExactly("Valid");
        assertThat(run(proposing(proposal(new InfoAction()), proposal(parameterChange(null, collateralPercent(140)))),
                11)).containsExactly("Valid");
    }

    @Test
    @Covers("GOV.ProposalDepositIncorrect")
    void theDepositIsTheGovActionDeposit() {
        // Value conservation counts ppGovActionDeposit whatever the stated deposit (conwayProposalsDeposits), so the
        // transaction balanced with it fails only GOV; balanced with the stated amount it fails UTXO too.
        assertThat(run(proposing(proposal(new InfoAction(), TestKey.DEV_77, MutationWorld.NETWORK,
                DEPOSIT.subtract(BigInteger.ONE))), 10)).containsExactly("GOV.ProposalDepositIncorrect");
        assertThat(run(proposing(proposal(new InfoAction(), TestKey.DEV_77, MutationWorld.NETWORK,
                DEPOSIT.add(BigInteger.ONE))), 11)).containsExactly("GOV.ProposalDepositIncorrect");
        TxSpec stated = proposing(proposal(new InfoAction(), TestKey.DEV_77, MutationWorld.NETWORK,
                DEPOSIT.subtract(BigInteger.ONE)));
        stated.changeAdjust = stated.changeAdjust.add(BigInteger.ONE);
        assertThat(run(stated, 10)).containsExactly("UTXO.ValueNotConservedUTxO", "GOV.ProposalDepositIncorrect");
    }

    @Test
    @Covers("GOV.ProposalReturnAccountDoesNotExist")
    void theReturnAccountMustBeRegistered() {
        assertThat(run(proposing(proposal(new InfoAction(), TestKey.DEV_AA, MutationWorld.NETWORK, DEPOSIT)), 10))
                .containsExactly("GOV.ProposalReturnAccountDoesNotExist");
        // Judged after CERTS: an account registered earlier in the transaction exists, one deregistered does not.
        TxSpec registered = proposing(proposal(new InfoAction(), TestKey.DEV_42, MutationWorld.NETWORK, DEPOSIT));
        registered.certs.add(new RegCert(
                MutationWorld.stakeCredential(TestKey.DEV_42), MutationWorld.KEY_DEPOSIT));
        registered.changeAdjust = registered.changeAdjust.subtract(MutationWorld.KEY_DEPOSIT);
        assertThat(run(registered, 10)).containsExactly("Valid");
        TxSpec deregistered = proposing(proposal(new InfoAction(), TestKey.DEV_77, MutationWorld.NETWORK, DEPOSIT));
        deregistered.certs.add(new UnregCert(
                MutationWorld.stakeCredential(TestKey.DEV_77), MutationWorld.KEY_DEPOSIT));
        deregistered.signers.add(TestKey.DEV_77);
        deregistered.changeAdjust = deregistered.changeAdjust.add(MutationWorld.KEY_DEPOSIT);
        assertThat(run(deregistered, 10)).containsExactly("GOV.ProposalReturnAccountDoesNotExist");
        // Only the credential counts: a registered credential's mainnet account exists (the network is its own check).
        assertThat(run(proposing(proposal(new InfoAction(), TestKey.DEV_77, Networks.mainnet(), DEPOSIT)), 10))
                .containsExactly("GOV.ProposalProcedureNetworkIdMismatch");
    }

    @Test
    @Covers("GOV.ProposalProcedureNetworkIdMismatch")
    void theReturnAccountIsOnTheLedgersNetwork() {
        assertThat(run(proposing(proposal(new InfoAction(), TestKey.DEV_77, Networks.mainnet(), DEPOSIT)), 11))
                .containsExactly("GOV.ProposalProcedureNetworkIdMismatch");
    }

    @Test
    @Covers("GOV.TreasuryWithdrawalsNetworkIdMismatch")
    void treasuryWithdrawalAccountsAreOnTheLedgersNetwork() {
        assertThat(run(proposing(proposal(treasuryWithdrawals(withdrawal(TestKey.DEV_77, Networks.mainnet(),
                ada(10))))), 10)).containsExactly("GOV.TreasuryWithdrawalsNetworkIdMismatch");
    }

    @Test
    @Covers("GOV.TreasuryWithdrawalReturnAccountsDoNotExist")
    void treasuryWithdrawalAccountsMustBeRegistered() {
        assertThat(run(proposing(proposal(treasuryWithdrawals(
                withdrawal(TestKey.DEV_AA, MutationWorld.NETWORK, ada(10)),
                withdrawal(TestKey.DEV_77, MutationWorld.NETWORK, ada(10))))), 10))
                .containsExactly("GOV.TreasuryWithdrawalReturnAccountsDoNotExist");
    }

    @Test
    @Covers("GOV.ZeroTreasuryWithdrawals")
    void treasuryWithdrawalsMustWithdrawSomething() {
        assertThat(run(proposing(proposal(treasuryWithdrawals(withdrawal(TestKey.DEV_77, MutationWorld.NETWORK,
                BigInteger.ZERO)))), 10)).containsExactly("GOV.ZeroTreasuryWithdrawals");
        // F.fold of an empty map is mempty too.
        assertThat(run(proposing(proposal(treasuryWithdrawals())), 10)).containsExactly("GOV.ZeroTreasuryWithdrawals");
    }

    @Test
    @Covers("GOV.InvalidGuardrailsScriptHash")
    void thePolicyIsTheConstitutionsGuardrailScript() {
        byte[] policy = nativeScriptHash();
        // The constitution has no guardrail script; the proposal names one (provided, so UTXOW is satisfied).
        TxSpec named = proposing(proposal(new ParameterChangeAction(null, collateralPercent(140), policy)));
        named.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(run(named, 10)).containsExactly("GOV.InvalidGuardrailsScriptHash");
        TxSpec withdrawals = proposing(proposal(new TreasuryWithdrawalsAction(new ArrayList<>(List.of(
                withdrawal(TestKey.DEV_77, MutationWorld.NETWORK, ada(10)))), policy)));
        withdrawals.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(run(withdrawals, 10)).containsExactly("GOV.InvalidGuardrailsScriptHash");

        // The constitution names the script: omitting it fails, naming it passes.
        InMemoryLedgerView guarded = world(10).guardrailScriptHash(HexUtil.encodeHexString(policy)).build();
        assertThat(run(proposing(proposal(parameterChange(null, collateralPercent(140)))), guarded, 10))
                .containsExactly("GOV.InvalidGuardrailsScriptHash");
        assertThat(run(named, guarded, 10)).containsExactly("Valid");
        // Other actions carry no policy and are not checked.
        assertThat(run(proposing(proposal(new InfoAction())), guarded, 10)).containsExactly("Valid");
    }

    @Test
    @Covers("GOV.MalformedProposal")
    void parameterUpdatesMustBeWellFormed() {
        assertThat(run(proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder().maxTxSize(0).build()))),
                10)).containsExactly("GOV.MalformedProposal");
        assertThat(run(proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder()
                .collateralPercent(0).build()))), 10)).containsExactly("GOV.MalformedProposal");
        assertThat(run(proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder()
                .adaPerUtxoByte(BigInteger.ZERO).build()))), 10)).containsExactly("GOV.MalformedProposal");
        // emptyPParamsUpdate
        assertThat(run(proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder().build()))), 10))
                .containsExactly("GOV.MalformedProposal");
        // nOpt = 0 is malformed only from protocol version 11.
        TxSpec noPools = proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder().nOpt(0).build())));
        assertThat(run(noPools, 10)).containsExactly("Valid");
        assertThat(run(noPools, 11)).containsExactly("GOV.MalformedProposal");
        // Conway keys CCL cannot express, written into the bytes: govActionLifetime (29) = 0.
        TxSpec lifetime = proposing(proposal(parameterChange(null, collateralPercent(140))));
        lifetime.bodyEdit = RawParamUpdates.replaceParamUpdate(Map.of(29, 0L));
        assertThat(run(lifetime, 10)).containsExactly("GOV.MalformedProposal");
        lifetime.bodyEdit = RawParamUpdates.replaceParamUpdate(Map.of(29, 5L));
        assertThat(run(lifetime, 10)).containsExactly("Valid");
    }

    // ------------------------------------------------------------------------------------------------ lineage

    @Test
    @Covers("GOV.InvalidPrevGovActionId")
    void theParentIsTheEnactedRootOrAProposalOfTheSamePurpose() {
        // In-flight parent of the same purpose.
        assertThat(run(proposing(proposal(parameterChange(MutationWorld.PARAMETER_CHANGE_ACTION,
                collateralPercent(140)))), 10)).containsExactly("Valid");
        // A parent of another purpose (an info action has none) or one that does not exist.
        assertThat(run(proposing(proposal(parameterChange(MutationWorld.INFO_ACTION, collateralPercent(140)))), 10))
                .containsExactly("GOV.InvalidPrevGovActionId");
        assertThat(run(proposing(proposal(parameterChange(new GovActionId("e0".repeat(32), 0),
                collateralPercent(140)))), 10)).containsExactly("GOV.InvalidPrevGovActionId");
        assertThat(run(proposing(proposal(new NoConfidence(ccl(MutationWorld.PARAMETER_CHANGE_ACTION)))), 10))
                .containsExactly("GOV.InvalidPrevGovActionId");

        // After a root was enacted, an empty parent is no longer valid; the root is.
        GovActionId root = new GovActionId("e1".repeat(32), 3);
        InMemoryLedgerView enacted = world(10).enactedRoots(new EnactedRoots(root, null, null, null)).build();
        assertThat(run(proposing(proposal(parameterChange(null, collateralPercent(140)))), enacted, 10))
                .containsExactly("GOV.InvalidPrevGovActionId");
        assertThat(run(proposing(proposal(parameterChange(root, collateralPercent(140)))), enacted, 10))
                .containsExactly("Valid");
        // The root of another purpose does not help.
        assertThat(run(proposing(proposal(new NoConfidence(ccl(root)))), enacted, 10))
                .containsExactly("GOV.InvalidPrevGovActionId");
    }

    @Test
    void anEarlierProposalOfTheSameTransactionIsAParent() {
        GovActionId first = new GovActionId(SAME_TX, 0);
        TxSpec chained = proposing(proposal(parameterChange(null, collateralPercent(140))),
                proposal(parameterChange(first, collateralPercent(130))));
        assertThat(runNamingOwnProposals(chained, SAME_TX, 10)).containsExactly("Valid");
        // A later one is not (it is not in Proposals yet), nor one whose own parent was invalid (it was not added).
        TxSpec forward = proposing(proposal(parameterChange(new GovActionId(SAME_TX, 1), collateralPercent(140))),
                proposal(parameterChange(null, collateralPercent(130))));
        assertThat(runNamingOwnProposals(forward, SAME_TX, 10)).containsExactly("GOV.InvalidPrevGovActionId");
        TxSpec orphaned = proposing(proposal(parameterChange(MutationWorld.INFO_ACTION, collateralPercent(140))),
                proposal(parameterChange(first, collateralPercent(130))));
        assertThat(runNamingOwnProposals(orphaned, SAME_TX, 10))
                .containsExactly("GOV.InvalidPrevGovActionId", "GOV.InvalidPrevGovActionId");
        // Other checks failing does not keep a proposal out of Proposals.
        TxSpec badDeposit = proposing(proposal(parameterChange(null, collateralPercent(140)), TestKey.DEV_77,
                MutationWorld.NETWORK, DEPOSIT.subtract(BigInteger.ONE)),
                proposal(parameterChange(first, collateralPercent(130))));
        assertThat(runNamingOwnProposals(badDeposit, SAME_TX, 10)).containsExactly("GOV.ProposalDepositIncorrect");
    }

    // ------------------------------------------------------------------------------------------------ hard forks

    @Test
    @Covers("GOV.ProposalCantFollow")
    void aHardForkMustFollowItsPredecessor() {
        // Against the enacted root (none here): the current version 10.0 is followed by 11.0 or 10.1 only.
        assertThat(run(proposing(proposal(hardFork(null, 11, 0))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(hardFork(null, 10, 1))), 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(hardFork(null, 10, 0))), 10)).containsExactly("GOV.ProposalCantFollow");
        assertThat(run(proposing(proposal(hardFork(null, 11, 1))), 10)).containsExactly("GOV.ProposalCantFollow");
        // Two majors ahead: compared with the current version whatever the parent, then the lineage check.
        assertThat(run(proposing(proposal(hardFork(null, 12, 0))), 10)).containsExactly("GOV.ProposalCantFollow");
        assertThat(run(proposing(proposal(hardFork(MutationWorld.INFO_ACTION, 12, 0))), 10))
                .containsExactly("GOV.ProposalCantFollow", "GOV.InvalidPrevGovActionId");
        assertThat(run(proposing(proposal(hardFork(null, 12, 0))), 11)).containsExactly("Valid");

        // Against an in-flight hard fork to 11.0: 12.0 or 11.1 follow it, 11.0 repeats it.
        GovActionId inFlight = new GovActionId("e2".repeat(32), 0);
        InMemoryLedgerView pending = world(10).proposal(standing(inFlight, GovActionType.HARD_FORK_INITIATION_ACTION,
                new HardForkInitiationAction(null, new ProtocolVersion(11, 0)), null)).build();
        assertThat(run(proposing(proposal(hardFork(inFlight, 11, 1))), pending, 10)).containsExactly("Valid");
        assertThat(run(proposing(proposal(hardFork(inFlight, 11, 0))), pending, 10))
                .containsExactly("GOV.ProposalCantFollow");
        // A parent that is not a hard fork: no version comparison, only the lineage failure.
        assertThat(run(proposing(proposal(hardFork(MutationWorld.PARAMETER_CHANGE_ACTION, 11, 0))), 10))
                .containsExactly("GOV.InvalidPrevGovActionId");

        // Within the transaction: the second chains on the first.
        GovActionId first = new GovActionId(SAME_TX, 0);
        assertThat(runNamingOwnProposals(proposing(proposal(hardFork(null, 11, 0)), proposal(hardFork(first, 11, 1))),
                SAME_TX, 10)).containsExactly("Valid");
        assertThat(runNamingOwnProposals(proposing(proposal(hardFork(null, 11, 0)), proposal(hardFork(first, 11, 0))),
                SAME_TX, 10)).containsExactly("GOV.ProposalCantFollow");
    }

    @Test
    void aProtocolMajorVersionBeyondTwelveDoesNotDecode() {
        assertThat(run(proposing(proposal(hardFork(null, 13, 0))), 11)).containsExactly("ENGINE.DecodingFailure");
    }

    // ------------------------------------------------------------------------------------------------ committee

    @Test
    @Covers("GOV.ConflictingCommitteeUpdate")
    void aMemberCannotBeAddedAndRemoved() {
        Credential bb = MutationWorld.credential(TestKey.DEV_BB);
        assertThat(run(proposing(proposal(updateCommittee(Set.of(bb), Map.of(bb, 50)))), 10))
                .containsExactly("GOV.ConflictingCommitteeUpdate");
    }

    @Test
    @Covers("GOV.ExpirationEpochTooSmall")
    void newMembersExpireAfterTheCurrentEpoch() {
        Credential cold = MutationWorld.credential(TestKey.DEV_42);
        // The world is at epoch 0: an expiry of 0 is already reached.
        assertThat(run(proposing(proposal(updateCommittee(Set.of(), Map.of(cold, 0)))), 10))
                .containsExactly("GOV.ExpirationEpochTooSmall");
        assertThat(run(proposing(proposal(updateCommittee(Set.of(), Map.of(cold, 1)))), 10)).containsExactly("Valid");
        // Both committee checks, in Haskell's order (two GOV predicates: execution order in LEDGER's list).
        Credential bb = MutationWorld.credential(TestKey.DEV_BB);
        assertThat(run(proposing(proposal(updateCommittee(Set.of(bb), Map.of(bb, 0)))), 10))
                .containsExactly("GOV.ConflictingCommitteeUpdate", "GOV.ExpirationEpochTooSmall");
    }

    // ------------------------------------------------------------------------------------------------ votes

    @Test
    @Covers("GOV.VotersDoNotExist")
    void votersMustExistAfterTheCertificates() {
        assertThat(run(voting(vote(drep(TestKey.DEV_77), MutationWorld.INFO_ACTION), TestKey.DEV_77), 10))
                .containsExactly("Valid");
        assertThat(run(voting(vote(drep(TestKey.DEV_AA), MutationWorld.INFO_ACTION), TestKey.DEV_AA), 10))
                .containsExactly("GOV.VotersDoNotExist");
        assertThat(run(voting(vote(committee(TestKey.DEV_42), MutationWorld.INFO_ACTION)), 10))
                .containsExactly("Valid");
        assertThat(run(voting(vote(committee(TestKey.DEV_BB), MutationWorld.INFO_ACTION), TestKey.DEV_BB), 10))
                .containsExactly("GOV.VotersDoNotExist");
        assertThat(run(voting(vote(pool(TestKey.DEV_BB), MutationWorld.INFO_ACTION), TestKey.DEV_BB), 10))
                .containsExactly("Valid");
        assertThat(run(voting(vote(pool(TestKey.DEV_AA), MutationWorld.INFO_ACTION), TestKey.DEV_AA), 10))
                .containsExactly("GOV.VotersDoNotExist");

        // A DRep registered earlier in the transaction votes; one deregistered earlier does not exist.
        TxSpec registered = voting(vote(drep(TestKey.DEV_42), MutationWorld.INFO_ACTION));
        registered.inputs.add(MutationWorld.RICH_INPUT);
        registered.certs.add(new RegDRepCert(MutationWorld.credential(TestKey.DEV_42), MutationWorld.DREP_DEPOSIT,
                null));
        registered.changeAdjust = MutationWorld.DREP_DEPOSIT.negate();
        assertThat(run(registered, 10)).containsExactly("Valid");
        TxSpec deregistered = voting(vote(drep(TestKey.DEV_77), MutationWorld.INFO_ACTION), TestKey.DEV_77);
        deregistered.certs.add(new UnregDRepCert(MutationWorld.credential(TestKey.DEV_77), MutationWorld.DREP_DEPOSIT));
        deregistered.changeAdjust = MutationWorld.DREP_DEPOSIT;
        assertThat(run(deregistered, 10)).containsExactly("GOV.VotersDoNotExist");
        // A committee member that resigned earlier in the transaction has no hot key (scenario 00169).
        TxSpec resigned = voting(vote(committee(TestKey.DEV_42), MutationWorld.INFO_ACTION), TestKey.DEV_77);
        resigned.certs.add(new ResignCommitteeColdCert(MutationWorld.credential(TestKey.DEV_77), null));
        assertThat(run(resigned, 10)).containsExactly("GOV.VotersDoNotExist");
        // A hot key authorised earlier in the transaction votes.
        TxSpec authorised = voting(vote(committee(TestKey.DEV_BB), MutationWorld.INFO_ACTION), TestKey.DEV_77,
                TestKey.DEV_BB);
        authorised.certs.add(new AuthCommitteeHotCert(MutationWorld.credential(TestKey.DEV_77),
                MutationWorld.credential(TestKey.DEV_BB)));
        assertThat(run(authorised, 10)).containsExactly("Valid");
    }

    @Test
    @Covers("GOV.GovActionsDoNotExist")
    void votesMustBeOnProposalsInTheState() {
        GovActionId absent = new GovActionId("e3".repeat(32), 0);
        assertThat(run(voting(vote(drep(TestKey.DEV_77), absent), TestKey.DEV_77), 10))
                .containsExactly("GOV.GovActionsDoNotExist");
        // Only known voters' votes are looked up: an unknown voter on an unknown action is VotersDoNotExist only.
        assertThat(run(voting(vote(drep(TestKey.DEV_AA), absent), TestKey.DEV_AA), 10))
                .containsExactly("GOV.VotersDoNotExist");
        // Both, in Haskell's order.
        TxSpec both = voting(vote(vote(votes(), drep(TestKey.DEV_AA), MutationWorld.INFO_ACTION),
                drep(TestKey.DEV_77), absent), TestKey.DEV_AA, TestKey.DEV_77);
        assertThat(run(both, 10)).containsExactly("GOV.VotersDoNotExist", "GOV.GovActionsDoNotExist");
    }

    @Test
    void aVoteOnAnEarlierProposalOfTheSameTransactionIsOnAnExistingAction() {
        TxSpec spec = proposing(proposal(new InfoAction()));
        spec.votingProcedures = vote(drep(TestKey.DEV_77), new GovActionId(SAME_TX, 0));
        spec.signers.add(TestKey.DEV_77);
        assertThat(runNamingOwnProposals(spec, SAME_TX, 10)).containsExactly("Valid");
        spec.votingProcedures = vote(drep(TestKey.DEV_77), new GovActionId(SAME_TX, 1));
        assertThat(runNamingOwnProposals(spec, SAME_TX, 10)).containsExactly("GOV.GovActionsDoNotExist");
    }

    @Test
    @Covers("GOV.VotingOnExpiredGovAction")
    void votesAreNotForExpiredActions() {
        // The standing proposals expire after epoch 6: votable in epoch 6, expired in epoch 7 (still in Proposals).
        TxSpec spec = voting(vote(vote(votes(), drep(TestKey.DEV_77), MutationWorld.INFO_ACTION), committee(
                TestKey.DEV_42), MutationWorld.INFO_ACTION), TestKey.DEV_77);
        assertThat(run(spec, MutationWorld.view(10), atEpoch(6), TxValidationRequest.Rule.LEDGER))
                .containsExactly("Valid");
        assertThat(run(spec, MutationWorld.view(10), atEpoch(7), TxValidationRequest.Rule.LEDGER))
                .containsExactly("GOV.VotingOnExpiredGovAction");
        // A proposal of the transaction itself expires after currentEpoch + govActionLifetime.
        TxSpec own = proposing(proposal(new InfoAction()));
        own.votingProcedures = vote(drep(TestKey.DEV_77), new GovActionId(SAME_TX, 0));
        own.signers.add(TestKey.DEV_77);
        assertThat(runNamingOwnProposals(own, SAME_TX, 10)).containsExactly("Valid");
    }

    /**
     * {@link #votesAreNotForExpiredActions()} in the world of every protocol version (ADR-056 Phase 5c): the check has
     * no version gate, and the votes (a DRep and a committee member on the standing info action) are allowed at every
     * version, the bootstrap phase included (Gov.hs:378-391).
     */
    @Test
    @Covers(value = "GOV.VotingOnExpiredGovAction", pv = {9, 10, 11})
    void votesAreNotForExpiredActionsAtEveryProtocolVersion() {
        TxSpec spec = voting(vote(vote(votes(), drep(TestKey.DEV_77), MutationWorld.INFO_ACTION), committee(
                TestKey.DEV_42), MutationWorld.INFO_ACTION), TestKey.DEV_77);
        for (int pv = 9; pv <= 11; pv++) {
            assertThat(run(spec, MutationWorld.view(pv), atEpoch(6, pv), TxValidationRequest.Rule.LEDGER))
                    .as("protocol version %d, epoch 6", pv).containsExactly("Valid");
            assertThat(run(spec, MutationWorld.view(pv), atEpoch(7, pv), TxValidationRequest.Rule.LEDGER))
                    .as("protocol version %d, epoch 7", pv).containsExactly("GOV.VotingOnExpiredGovAction");
        }
    }

    private static ValidationEnv atEpoch(long epoch) {
        ValidationEnv base = EngineTestSupport.env(10);
        return new ValidationEnv(base.currentSlot(), epoch, 10, 0, base.networkId(), base.slotConfig(),
                base.phase2EnvDigest());
    }

    private static ValidationEnv atEpoch(long epoch, int protocolMajor) {
        ValidationEnv base = EngineTestSupport.env(protocolMajor);
        return new ValidationEnv(base.currentSlot(), epoch, protocolMajor, 0, base.networkId(), base.slotConfig(),
                base.phase2EnvDigest());
    }

    @Test
    @Covers("GOV.DisallowedVoters")
    void theVoterMatrix() {
        GovActionId noConfidence = new GovActionId("e4".repeat(32), 0);
        GovActionId constitution = new GovActionId("e4".repeat(32), 1);
        GovActionId withdrawals = new GovActionId("e4".repeat(32), 2);
        GovActionId hardFork = new GovActionId("e4".repeat(32), 3);
        GovActionId security = new GovActionId("e4".repeat(32), 4);
        InMemoryLedgerView view = world(10)
                .proposal(standing(noConfidence, GovActionType.NO_CONFIDENCE, new NoConfidence(null), null))
                .proposal(standing(constitution, GovActionType.NEW_CONSTITUTION, new NewConstitution(), null))
                .proposal(standing(withdrawals, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                        new TreasuryWithdrawalsAction(), null))
                .proposal(standing(hardFork, GovActionType.HARD_FORK_INITIATION_ACTION,
                        new HardForkInitiationAction(null, new ProtocolVersion(11, 0)), null))
                .proposal(standing(security, GovActionType.PARAMETER_CHANGE_ACTION, new ParameterChangeAction(),
                        Set.of(23, 3)))
                .build();
        // Committee: not on NoConfidence or UpdateCommittee.
        assertThat(run(voting(vote(committee(TestKey.DEV_42), noConfidence)), view, 10))
                .containsExactly("GOV.DisallowedVoters");
        for (GovActionId allowed : List.of(constitution, withdrawals, hardFork, security,
                MutationWorld.PARAMETER_CHANGE_ACTION, MutationWorld.INFO_ACTION)) {
            assertThat(run(voting(vote(committee(TestKey.DEV_42), allowed)), view, 10)).as(allowed.toString())
                    .containsExactly("Valid");
        }
        // DReps: on everything.
        for (GovActionId any : List.of(noConfidence, constitution, withdrawals, hardFork, security,
                MutationWorld.PARAMETER_CHANGE_ACTION, MutationWorld.INFO_ACTION)) {
            assertThat(run(voting(vote(drep(TestKey.DEV_77), any), TestKey.DEV_77), view, 10)).as(any.toString())
                    .containsExactly("Valid");
        }
        // Stake pools: not on NewConstitution, TreasuryWithdrawals or parameter changes outside the security group.
        for (GovActionId denied : List.of(constitution, withdrawals, MutationWorld.PARAMETER_CHANGE_ACTION)) {
            assertThat(run(voting(vote(pool(TestKey.DEV_77), denied), TestKey.DEV_77), view, 10)).as(denied.toString())
                    .containsExactly("GOV.DisallowedVoters");
        }
        for (GovActionId allowed : List.of(noConfidence, hardFork, security, MutationWorld.INFO_ACTION)) {
            assertThat(run(voting(vote(pool(TestKey.DEV_77), allowed), TestKey.DEV_77), view, 10))
                    .as(allowed.toString()).containsExactly("Valid");
        }
    }

    @Test
    void stakePoolsVoteOnParameterChangesTouchingTheSecurityGroupOnly() {
        // The stake-pool SecurityGroup (Conway/PParams.hs:644-708): keys 0, 1, 2, 3, 4, 17, 21, 22, 30, 33.
        Set<Integer> security = Set.of(0, 1, 2, 3, 4, 17, 21, 22, 30, 33);
        GovActionId id = new GovActionId("e5".repeat(32), 0);
        for (int key = 0; key <= 33; key++) {
            if (key >= 12 && key <= 15) {
                continue; // not Conway parameters (12, 13, 15) or not updatable (14)
            }
            InMemoryLedgerView view = world(10).proposal(standing(id, GovActionType.PARAMETER_CHANGE_ACTION,
                    new ParameterChangeAction(), new HashSet<>(List.of(key, 23)))).build();
            assertThat(run(voting(vote(pool(TestKey.DEV_77), id), TestKey.DEV_77), view, 10)).as("key " + key)
                    .containsExactly(security.contains(key) ? "Valid" : "GOV.DisallowedVoters");
        }
    }

    @Test
    void aParameterChangeWithUnknownKeysFailsClosedForStakePoolVotes() {
        GovActionId id = new GovActionId("e5".repeat(32), 0);
        InMemoryLedgerView view = world(10).proposal(standing(id, GovActionType.PARAMETER_CHANGE_ACTION,
                new ParameterChangeAction(), null)).build();
        assertThat(run(voting(vote(pool(TestKey.DEV_77), id), TestKey.DEV_77), view, 10))
                .containsExactly("ENGINE.LedgerStateUnavailable");
    }

    @Test
    void aSameTransactionSecurityGroupChangeIsJudgedFromItsBytes() {
        TxSpec spec = proposing(proposal(parameterChange(null, collateralPercent(140))));
        spec.votingProcedures = vote(pool(TestKey.DEV_77), new GovActionId(SAME_TX, 0));
        spec.signers.add(TestKey.DEV_77);
        assertThat(runNamingOwnProposals(spec, SAME_TX, 10)).containsExactly("GOV.DisallowedVoters");
        // govActionDeposit (30) is in the security group; CCL cannot write it, the raw edit can.
        spec.bodyEdit = RawParamUpdates.replaceParamUpdate(Map.of(30, 1_000_000L));
        assertThat(runNamingOwnProposals(spec, SAME_TX, 10)).containsExactly("Valid");
    }

    // ------------------------------------------------------------------------------------------------ PV 11

    @Test
    @Covers("GOV.UnelectedCommitteeVoters")
    void unelectedCommitteeMembersDoNotVoteFromProtocolVersion11() {
        TxSpec unelected = voting(vote(committee(TestKey.DEV_AA), MutationWorld.INFO_ACTION), TestKey.DEV_AA);
        assertThat(run(unelected, 11)).containsExactly("GOV.UnelectedCommitteeVoters");
        // Before 11 only MEMPOOL rejects it (MempoolTransitionTest); a block accepts it.
        assertThat(run(unelected, 10)).containsExactly("Valid");
        // Judged against the post-CERTS committee state: an elected member authorising the key in the transaction
        // makes it an elected member's key.
        TxSpec authorised = voting(vote(committee(TestKey.DEV_AA), MutationWorld.INFO_ACTION), TestKey.DEV_AA,
                TestKey.DEV_77);
        authorised.certs.add(new AuthCommitteeHotCert(MutationWorld.credential(TestKey.DEV_77),
                MutationWorld.credential(TestKey.DEV_AA)));
        assertThat(run(authorised, 11)).containsExactly("Valid");
        // The elected member's key votes.
        assertThat(run(voting(vote(committee(TestKey.DEV_42), MutationWorld.INFO_ACTION)), 11))
                .containsExactly("Valid");
        // Checked before the proposals; GOV's predicates reach LEDGER's list in execution order (two reversals).
        TxSpec both = proposing(proposal(new InfoAction(), TestKey.DEV_AA, MutationWorld.NETWORK, DEPOSIT));
        both.votingProcedures = vote(committee(TestKey.DEV_AA), MutationWorld.INFO_ACTION);
        both.signers.add(TestKey.DEV_AA);
        assertThat(run(both, 11)).containsExactly("GOV.UnelectedCommitteeVoters",
                "GOV.ProposalReturnAccountDoesNotExist");
    }

    // ------------------------------------------------------------------------------------------------ PV 9 (bootstrap)

    @Test
    @Covers("GOV.DisallowedProposalDuringBootstrap")
    void onlyBootstrapActionsMayBeProposedAtProtocolVersion9() {
        // checkBootstrapProposal (Gov.hs:435-444): ParameterChange, HardForkInitiation and InfoAction only (:633-639).
        assertThat(run(proposing(proposal(new InfoAction())), 9)).containsExactly("Valid");
        assertThat(run(proposing(proposal(parameterChange(null, collateralPercent(140)))), 9))
                .containsExactly("Valid");
        assertThat(run(proposing(proposal(hardFork(null, 10, 0))), 9)).containsExactly("Valid");
        List<GovAction> disallowed = List.of(
                treasuryWithdrawals(withdrawal(TestKey.DEV_77, MutationWorld.NETWORK, ada(10))),
                new NoConfidence(null),
                updateCommittee(Set.of(MutationWorld.credential(TestKey.DEV_BB)),
                        Map.of(MutationWorld.credential(TestKey.DEV_42), 50)),
                new NewConstitution(null, Constitution.builder()
                        .anchor(new Anchor("https://example.com/constitution.txt", new byte[32])).build()));
        for (GovAction action : disallowed) {
            assertThat(run(proposing(proposal(action)), 9)).as(action.getType().name())
                    .containsExactly("GOV.DisallowedProposalDuringBootstrap");
            // The other side of the gate: valid from protocol version 10.
            assertThat(run(proposing(proposal(action)), 10)).as(action.getType().name()).containsExactly("Valid");
        }
        // The first check of processProposal (:483); the proposal's other checks still run, in order.
        assertThat(run(proposing(proposal(new NoConfidence(null), TestKey.DEV_77, MutationWorld.NETWORK,
                DEPOSIT.subtract(BigInteger.ONE))), 9))
                .containsExactly("GOV.DisallowedProposalDuringBootstrap", "GOV.ProposalDepositIncorrect");
        // Per proposal: only the disallowed one fails.
        assertThat(run(proposing(proposal(new InfoAction()), proposal(new NoConfidence(null))), 9))
                .containsExactly("GOV.DisallowedProposalDuringBootstrap");
    }

    @Test
    @Covers("GOV.DisallowedVotesDuringBootstrap")
    void bootstrapVotersAtProtocolVersion9() {
        // checkBootstrapVotes (Gov.hs:378-391): DReps only on InfoAction; committee and pools only on bootstrap actions.
        GovActionId withdrawals = new GovActionId("e6".repeat(32), 0);
        GovActionId hardFork = new GovActionId("e6".repeat(32), 1);
        GovActionId constitution = new GovActionId("e6".repeat(32), 2);
        InMemoryLedgerView pv9 = standingActions(9, withdrawals, hardFork, constitution);
        InMemoryLedgerView pv10 = standingActions(10, withdrawals, hardFork, constitution);

        assertThat(run(voting(vote(drep(TestKey.DEV_77), MutationWorld.INFO_ACTION), TestKey.DEV_77), pv9, 9))
                .containsExactly("Valid");
        for (GovActionId denied : List.of(MutationWorld.PARAMETER_CHANGE_ACTION, hardFork, withdrawals)) {
            TxSpec spec = voting(vote(drep(TestKey.DEV_77), denied), TestKey.DEV_77);
            assertThat(run(spec, pv9, 9)).as(denied.toString())
                    .containsExactly("GOV.DisallowedVotesDuringBootstrap");
            assertThat(run(spec, pv10, 10)).as(denied.toString()).containsExactly("Valid");
        }
        for (GovActionId allowed : List.of(MutationWorld.INFO_ACTION, MutationWorld.PARAMETER_CHANGE_ACTION,
                hardFork)) {
            assertThat(run(voting(vote(committee(TestKey.DEV_42), allowed)), pv9, 9)).as(allowed.toString())
                    .containsExactly("Valid");
        }
        TxSpec committeeOnWithdrawal = voting(vote(committee(TestKey.DEV_42), withdrawals));
        assertThat(run(committeeOnWithdrawal, pv9, 9)).containsExactly("GOV.DisallowedVotesDuringBootstrap");
        assertThat(run(committeeOnWithdrawal, pv10, 10)).containsExactly("Valid");
        assertThat(run(voting(vote(pool(TestKey.DEV_77), hardFork), TestKey.DEV_77), pv9, 9)).containsExactly("Valid");
        // After GovActionsDoNotExist, before DisallowedVoters (:605-608): a pool on NewConstitution breaks both.
        TxSpec poolOnConstitution = voting(vote(pool(TestKey.DEV_77), constitution), TestKey.DEV_77);
        assertThat(run(poolOnConstitution, pv9, 9))
                .containsExactly("GOV.DisallowedVotesDuringBootstrap", "GOV.DisallowedVoters");
        assertThat(run(poolOnConstitution, pv10, 10)).containsExactly("GOV.DisallowedVoters");
        TxSpec withUnknown = voting(vote(vote(votes(), drep(TestKey.DEV_77), hardFork), drep(TestKey.DEV_77),
                new GovActionId("e3".repeat(32), 0)), TestKey.DEV_77);
        assertThat(run(withUnknown, pv9, 9))
                .containsExactly("GOV.GovActionsDoNotExist", "GOV.DisallowedVotesDuringBootstrap");
    }

    private static InMemoryLedgerView standingActions(int protocolMajor, GovActionId withdrawals, GovActionId hardFork,
                                                      GovActionId constitution) {
        return world(protocolMajor)
                .proposal(standing(withdrawals, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                        new TreasuryWithdrawalsAction(), null))
                .proposal(standing(hardFork, GovActionType.HARD_FORK_INITIATION_ACTION,
                        new HardForkInitiationAction(null, new ProtocolVersion(10, 0)), null))
                .proposal(standing(constitution, GovActionType.NEW_CONSTITUTION, new NewConstitution(), null))
                .build();
    }

    @Test
    @Covers("GOV.ProposalReturnAccountDoesNotExist")
    @Covers("GOV.TreasuryWithdrawalReturnAccountsDoNotExist")
    void returnAccountsAreCheckedFromProtocolVersion10() {
        // unless hardforkConwayBootstrapPhase (Gov.hs:504-520).
        TxSpec unregisteredReturn = proposing(proposal(new InfoAction(), TestKey.DEV_AA, MutationWorld.NETWORK,
                DEPOSIT));
        assertThat(run(unregisteredReturn, 9)).containsExactly("Valid");
        assertThat(run(unregisteredReturn, 10)).containsExactly("GOV.ProposalReturnAccountDoesNotExist");
        // A treasury withdrawal is not a bootstrap action; at 9 the account checks are skipped.
        TxSpec unregisteredWithdrawal = proposing(proposal(treasuryWithdrawals(withdrawal(TestKey.DEV_AA,
                MutationWorld.NETWORK, ada(10))), TestKey.DEV_AA, MutationWorld.NETWORK, DEPOSIT));
        assertThat(run(unregisteredWithdrawal, 9)).containsExactly("GOV.DisallowedProposalDuringBootstrap");
        assertThat(run(unregisteredWithdrawal, 10)).containsExactly("GOV.ProposalReturnAccountDoesNotExist",
                "GOV.TreasuryWithdrawalReturnAccountsDoNotExist");
    }

    @Test
    @Covers("GOV.MalformedProposal")
    void coinsPerUTxOByteMayBeZeroDuringTheBootstrapPhase() {
        // ppuWellFormed: hardforkConwayBootstrapPhase pv || coinsPerUTxOByte /= 0 (Conway/PParams.hs:949-950).
        TxSpec zeroCoinsPerByte = proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder()
                .adaPerUtxoByte(BigInteger.ZERO).build())));
        assertThat(run(zeroCoinsPerByte, 9)).containsExactly("Valid");
        assertThat(run(zeroCoinsPerByte, 10)).containsExactly("GOV.MalformedProposal");
        // Only key 17 is relaxed.
        assertThat(run(proposing(proposal(parameterChange(null, ProtocolParamUpdate.builder().adaPerUtxoByte(
                BigInteger.ZERO).maxTxSize(0).build()))), 9)).containsExactly("GOV.MalformedProposal");
    }

    @Test
    void govRunsOnlyForPhase2ValidTransactions() {
        // isValid = false: LEDGER skips the pre-checks, CERTS and GOV (Ledger.hs:363, 423).
        TxSpec spec = MutationWorld.scriptSpec();
        spec.isValid = false;
        spec.votingProcedures = vote(drep(TestKey.DEV_AA), MutationWorld.INFO_ACTION);
        spec.signers.add(TestKey.DEV_AA);
        List<String> names = EngineTestSupport.names(EngineTestSupport.validate(
                new EngineTestSupport.StubEvaluator(), ConwayTxBuilder.build(spec, MutationWorld.view()).cbor(), MutationWorld.view(), MutationWorld.env(), null));
        assertThat(names).doesNotContain("GOV.VotersDoNotExist");
    }

    @Test
    void votingProceduresDecodeAsHaskellDoes() throws Exception {
        // A vote other than 0-2, and a voter without votes, do not decode (Procedures.hs:408-416, decodeEnumBounded).
        // CCL refuses both too, so the Java decoder is exercised directly on the edited bytes.
        BuiltTx built = ConwayTxBuilder.build(voting(vote(drep(TestKey.DEV_77), MutationWorld.INFO_ACTION),
                TestKey.DEV_77), MutationWorld.view(10));
        byte[] badVote = editVotes(built.cbor(), byVoter -> {
            co.nstant.in.cbor.model.Map byAction = (co.nstant.in.cbor.model.Map) byVoter.get(
                    byVoter.getKeys().iterator().next());
            Array procedure = (Array) byAction.get(byAction.getKeys().iterator().next());
            procedure.getDataItems().set(0, new UnsignedInteger(3));
        });
        assertThatThrownBy(() -> RawTransaction.parse(badVote, built.tx())).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("unknown vote 3");
        byte[] noVotes = editVotes(built.cbor(), byVoter -> byVoter.put(byVoter.getKeys().iterator().next(),
                new co.nstant.in.cbor.model.Map()));
        assertThatThrownBy(() -> RawTransaction.parse(noVotes, built.tx())).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("didn't have any");
        assertThat(RawTransaction.parse(built.cbor(), built.tx()).votes().values()).singleElement()
                .isEqualTo(Map.of(MutationWorld.INFO_ACTION, 1));
    }

    private static byte[] editVotes(byte[] cbor, Consumer<co.nstant.in.cbor.model.Map> edit) throws Exception {
        Array tx = (Array) CborSerializationUtil.deserialize(cbor);
        co.nstant.in.cbor.model.Map body = (co.nstant.in.cbor.model.Map) tx.getDataItems().get(0);
        edit.accept((co.nstant.in.cbor.model.Map) body.get(new UnsignedInteger(19)));
        return CborSerializationUtil.serialize(tx);
    }
}
