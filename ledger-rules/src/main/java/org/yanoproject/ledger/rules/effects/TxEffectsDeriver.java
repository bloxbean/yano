package org.yanoproject.ledger.rules.effects;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NewConstitution;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NoConfidence;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.ValidationEnv;
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
import org.yanoproject.ledger.rules.util.RewardAddresses;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledger.rules.view.model.Voter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes a valid transaction's {@link TxEffects} from (transaction, pre-state, protocol
 * parameters, phase-2 verdict), independently of the validation engine (ADR-056 invariant 4).
 *
 * <p>The deriver does not validate: it assumes the transaction is valid for the given verdict
 * against {@code preState}. It throws {@link LedgerStateUnavailableException} when a read it needs is
 * unavailable, and {@link IllegalArgumentException} / {@link IllegalStateException} when the
 * transaction cannot be valid (a certificate Conway forbids, a refund for an unregistered
 * credential).</p>
 *
 * <p>Order, following Haskell Conway {@code LEDGER} at cardano-ledger {@code f649f975}:</p>
 * <ol>
 *   <li>UTxO: spending inputs consumed, outputs produced at their indexes
 *       ({@code Shelley.updateUTxOState}); for {@code isValid=false} only collateral is consumed and
 *       the collateral return produced (Babbage/Rules/Utxo.hs:474-487).</li>
 *   <li>Before the first certificate: the dormant-DRep expiry bump when the transaction has
 *       proposals, the expiry refresh of voting DReps, and the withdrawal drain. This is the
 *       {@code CERTS} base case before PV11 (Conway/Rules/Certs.hs:223-241) and the {@code LEDGER}
 *       pre-step from PV11 (Ledger.hs:383-393); the resulting state is the same.</li>
 *   <li>Certificates in body order, each against the state left by the previous ones (an
 *       {@link IntraTxFold}), so register→delegate and register→deregister in one transaction work
 *       and refunds come from the recorded deposit.</li>
 *   <li>Proposals, then votes ({@code GOV}, Gov.hs:483-613).</li>
 * </ol>
 */
public final class TxEffectsDeriver {

    /**
     * @param txCbor      the transaction bytes, used for the id when {@code txId} is null
     * @param tx          the decoded transaction
     * @param txId        the transaction id (lowercase hex), or {@code null} to compute it
     * @param preState    the state the transaction was validated against; its
     *                    {@link LedgerView#protocolParams()} supplies the epoch-effective parameters
     * @param env         the validation environment (current epoch, protocol version)
     * @param phase2Valid the phase-2 verdict
     * @return the transaction's effects
     */
    public TxEffects derive(byte[] txCbor, Transaction tx, String txId, LedgerView preState, ValidationEnv env,
                            boolean phase2Valid) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(preState, "preState");
        Objects.requireNonNull(env, "env");
        String id = txId != null ? txId : TxIdentity.txIdHex(Objects.requireNonNull(txCbor, "txCbor"));
        TransactionBody body = Objects.requireNonNull(tx.getBody(), "tx body");

        if (!phase2Valid) {
            return collateralOnly(id, body);
        }

        List<Outpoint> consumed = inputs(body.getInputs());
        List<UtxoEntry> produced = new ArrayList<>();
        List<TransactionOutput> outputs = nullToEmpty(body.getOutputs());
        for (int i = 0; i < outputs.size(); i++) {
            produced.add(new UtxoEntry(Outpoints.of(id, i), outputs.get(i)));
        }

        ProtocolParams pp = protocolParams(preState);
        IntraTxFold fold = IntraTxFold.start(id, preState);
        fold = fold.step(preCertificateChanges(fold.current(), body, pp, env));
        for (Certificate cert : nullToEmpty(body.getCerts())) {
            fold = fold.step(certificateChanges(fold.current(), cert, pp, env));
        }
        List<LedgerChange> changes = new ArrayList<>(fold.changes());
        changes.addAll(proposalChanges(id, body, pp, env));
        changes.addAll(voteChanges(body));
        return new TxEffects(id, true, consumed, produced, changes);
    }

    /**
     * @return the view's protocol parameters
     * @throws LedgerStateUnavailableException when they are unavailable
     */
    static ProtocolParams protocolParams(LedgerView view) {
        return view.protocolParams().require("protocol parameters");
    }

    /**
     * {@code isValid=false}: consume the collateral inputs and produce the collateral return at
     * index {@code length outputs} (Babbage/Collateral.hs:52-60, Babbage/Rules/Utxo.hs:474-487).
     */
    private static TxEffects collateralOnly(String id, TransactionBody body) {
        List<Outpoint> consumed = inputs(body.getCollateral());
        List<UtxoEntry> produced = new ArrayList<>();
        if (body.getCollateralReturn() != null) {
            int index = Math.min(nullToEmpty(body.getOutputs()).size(), 0xFFFF);
            produced.add(new UtxoEntry(Outpoints.of(id, index), body.getCollateralReturn()));
        }
        return new TxEffects(id, false, consumed, produced, List.of());
    }

    /**
     * The step before the first certificate: dormant-DRep bump, voting-DRep expiry refresh and
     * withdrawal drain. Certs.hs:223-241 (PV10) / Ledger.hs:383-393 (PV11+).
     *
     * @param state the pre-transaction state ({@link IntraTxFold#current()} of a fresh fold)
     * @return the step's changes, in order
     */
    public static List<LedgerChange> preCertificateChanges(LedgerView state, TransactionBody body, ProtocolParams pp,
                                                           ValidationEnv env) {
        List<LedgerChange> changes = new ArrayList<>();
        boolean hasProposals = !nullToEmpty(body.getProposalProcedures()).isEmpty();
        long dormant = 0;
        if (hasProposals) {
            dormant = state.dormantEpochs().require("dormant epochs");
            if (dormant > 0) {
                // updateDormantDRepExpiries: bump all DReps and reset the counter (Certs.hs:257-266).
                changes.add(new DormantDRepExpiriesBumped(dormant, env.currentEpoch()));
                dormant = 0;
            }
        }
        VotingProcedures votes = body.getVotingProcedures();
        if (votes != null && votes.getVoting() != null && !votes.getVoting().isEmpty()) {
            if (!hasProposals) {
                dormant = state.dormantEpochs().require("dormant epochs");
            }
            long expiry = drepExpiry(pp, env.currentEpoch(), dormant);
            for (var voter : votes.getVoting().keySet()) {
                Voter v = Voter.of(voter);
                // updateVotingDRepExpiries uses Map.adjust: unregistered voters are skipped (Certs.hs:278-292).
                if (v.role() == Voter.Role.DREP
                        && state.drep(v.credential()).orElseThrowUnavailable().isPresent()) {
                    changes.add(new DRepActivityUpdated(v.credential(), expiry));
                }
            }
        }
        for (Withdrawal w : nullToEmpty(body.getWithdrawals())) {
            // drainAccounts sets the balance to zero (cardano-ledger-core State/Account.hs:277).
            changes.add(new RewardWithdrawn(RewardAddresses.credential(w.getRewardAddress()), w.getCoin()));
        }
        return changes;
    }

    /**
     * One certificate's changes, assuming the certificate is valid against {@code state}.
     *
     * @param state the state left by the earlier steps ({@link IntraTxFold#current()})
     * @return the certificate's changes, in order
     */
    public static List<LedgerChange> certificateChanges(LedgerView state, Certificate cert, ProtocolParams pp,
                                                        ValidationEnv env) {
        return switch (cert) {
            // Legacy and Conway registration both record ppKeyDeposit (Conway/Rules/Deleg.hs:233-239).
            case StakeRegistration c -> List.of(register(CredentialKey.of(c.getStakeCredential()), pp));
            case RegCert c -> List.of(register(CredentialKey.of(c.getStakeCredential()), pp));
            case StakeDeregistration c -> List.of(unregister(state, CredentialKey.of(c.getStakeCredential())));
            case UnregCert c -> List.of(unregister(state, CredentialKey.of(c.getStakeCredential())));
            case StakeDelegation c -> List.of(new StakeDelegated(CredentialKey.of(c.getStakeCredential()),
                    PoolId.of(c.getStakePoolId().getPoolKeyHash())));
            case VoteDelegCert c -> List.of(new VoteDelegated(CredentialKey.of(c.getStakeCredential()),
                    DRepTarget.of(c.getDrep())));
            case StakeVoteDelegCert c -> {
                CredentialKey cred = CredentialKey.of(c.getStakeCredential());
                yield List.of(new StakeDelegated(cred, new PoolId(c.getPoolKeyHash())),
                        new VoteDelegated(cred, DRepTarget.of(c.getDrep())));
            }
            // ConwayRegDelegCert: register with ppKeyDeposit, then delegate (Deleg.hs:293-301).
            case StakeRegDelegCert c -> {
                CredentialKey cred = CredentialKey.of(c.getStakeCredential());
                yield List.of(register(cred, pp), new StakeDelegated(cred, new PoolId(c.getPoolKeyHash())));
            }
            case VoteRegDelegCert c -> {
                CredentialKey cred = CredentialKey.of(c.getStakeCredential());
                yield List.of(register(cred, pp), new VoteDelegated(cred, DRepTarget.of(c.getDrep())));
            }
            case StakeVoteRegDelegCert c -> {
                CredentialKey cred = CredentialKey.of(c.getStakeCredential());
                yield List.of(register(cred, pp), new StakeDelegated(cred, new PoolId(c.getPoolKeyHash())),
                        new VoteDelegated(cred, DRepTarget.of(c.getDrep())));
            }
            case PoolRegistration c -> List.of(poolRegistration(state, c, pp));
            case PoolRetirement c -> List.of(new PoolRetirementScheduled(PoolId.of(c.getPoolKeyHash()), c.getEpoch()));
            // ConwayRegDRep records ppDRepDeposit and the versioned expiry (GovCert.hs:210-232).
            case RegDRepCert c -> {
                long dormant = state.dormantEpochs().require("dormant epochs");
                long expiry = env.protocolMajor() == 9
                        ? env.currentEpoch() + requireInt(pp.getDrepActivity(), "drepActivity")
                        : drepExpiry(pp, env.currentEpoch(), dormant);
                yield List.of(new DRepRegistered(CredentialKey.of(c.getDrepCredential()),
                        requireCoin(pp.getDrepDeposit(), "drepDeposit"), expiry));
            }
            // ConwayUnRegDRep: the refund is the recorded deposit (GovCert.hs:234-255).
            case UnregDRepCert c -> {
                CredentialKey cred = CredentialKey.of(c.getDrepCredential());
                yield List.of(new DRepUnregistered(cred, state.drep(cred).require("drep " + cred).deposit()));
            }
            // ConwayUpdateDRep always uses computeDRepExpiry (GovCert.hs:256-272).
            case UpdateDRepCert c -> List.of(new DRepUpdated(CredentialKey.of(c.getDrepCredential()),
                    drepExpiry(pp, env.currentEpoch(), state.dormantEpochs().require("dormant epochs"))));
            case AuthCommitteeHotCert c -> List.of(new CommitteeHotAuthorized(
                    CredentialKey.of(c.getCommitteeColdCredential()), CredentialKey.of(c.getCommitteeHotCredential())));
            case ResignCommitteeColdCert c ->
                    List.of(new CommitteeResigned(CredentialKey.of(c.getCommitteeColdCredential())));
            default -> throw new IllegalArgumentException(
                    "Certificate not allowed in Conway: " + cert.getClass().getSimpleName());
        };
    }

    private static AccountRegistered register(CredentialKey cred, ProtocolParams pp) {
        return new AccountRegistered(cred, requireCoin(pp.getKeyDeposit(), "keyDeposit"));
    }

    /** The refund is the deposit recorded in the account, not the certificate's coin (Deleg.hs:240-247). */
    private static AccountUnregistered unregister(LedgerView state, CredentialKey cred) {
        return new AccountUnregistered(cred, state.account(cred).require("account " + cred).deposit());
    }

    /**
     * A registered pool re-registers: no deposit, future params, retirement cancelled
     * (Shelley/Rules/Pool.hs:277-305). Otherwise a new pool pays ppPoolDeposit (:263-275).
     */
    private static LedgerChange poolRegistration(LedgerView state, PoolRegistration cert, ProtocolParams pp) {
        PoolId id = PoolId.of(cert.getOperator());
        Lookup<PoolState> existing = state.pool(id);
        if (existing.orElseThrowUnavailable().isPresent()) {
            return new PoolReregistered(id, cert);
        }
        return new PoolRegistered(id, cert, requireCoin(pp.getPoolDeposit(), "poolDeposit"));
    }

    /**
     * Proposal ids are (txId, index in proposal_procedures) and expire after
     * {@code currentEpoch + govActionLifetime} (Gov.hs:483-486, 561-563; mkGovActionState :409-417).
     */
    private static List<LedgerChange> proposalChanges(String txId, TransactionBody body, ProtocolParams pp,
                                                      ValidationEnv env) {
        List<ProposalProcedure> procedures = nullToEmpty(body.getProposalProcedures());
        if (procedures.isEmpty()) {
            return List.of();
        }
        long lifetime = requireInt(pp.getGovActionLifetime(), "govActionLifetime");
        List<LedgerChange> changes = new ArrayList<>();
        for (int i = 0; i < procedures.size(); i++) {
            ProposalProcedure p = procedures.get(i);
            GovAction action = Objects.requireNonNull(p.getGovAction(), "govAction");
            changes.add(new ProposalSubmitted(new ProposalState(new GovActionId(txId, i), action.getType(), action,
                    prevActionId(action), env.currentEpoch(), env.currentEpoch() + lifetime,
                    Objects.requireNonNull(p.getDeposit(), "proposal deposit"), p.getRewardAccount())));
        }
        return changes;
    }

    private static GovActionId prevActionId(GovAction action) {
        return GovActionId.of(switch (action) {
            case ParameterChangeAction a -> a.getPrevGovActionId();
            case HardForkInitiationAction a -> a.getPrevGovActionId();
            case NoConfidence a -> a.getPrevGovActionId();
            case UpdateCommittee a -> a.getPrevGovActionId();
            case NewConstitution a -> a.getPrevGovActionId();
            default -> null;
        });
    }

    private static List<LedgerChange> voteChanges(TransactionBody body) {
        VotingProcedures procedures = body.getVotingProcedures();
        if (procedures == null || procedures.getVoting() == null) {
            return List.of();
        }
        List<LedgerChange> changes = new ArrayList<>();
        for (var byVoter : procedures.getVoting().entrySet()) {
            Voter voter = Voter.of(byVoter.getKey());
            for (var vote : byVoter.getValue().entrySet()) {
                changes.add(new VoteCast(voter, GovActionId.of(vote.getKey()), vote.getValue().getVote()));
            }
        }
        return changes;
    }

    /** computeDRepExpiry = currentEpoch + drepActivity - numDormantEpochs (GovCert.hs:294-306). */
    private static long drepExpiry(ProtocolParams pp, long currentEpoch, long dormantEpochs) {
        return currentEpoch + requireInt(pp.getDrepActivity(), "drepActivity") - dormantEpochs;
    }

    private static List<Outpoint> inputs(List<TransactionInput> inputs) {
        List<Outpoint> result = new ArrayList<>();
        for (TransactionInput in : nullToEmpty(inputs)) {
            result.add(Outpoints.of(in.getTransactionId(), in.getIndex()));
        }
        return result;
    }

    private static BigInteger requireCoin(Object value, String name) {
        return switch (value) {
            case null -> throw new IllegalArgumentException("Protocol parameter " + name + " is missing");
            case BigInteger b -> b;
            case String s -> new BigInteger(s);
            default -> throw new IllegalArgumentException("Unsupported " + name + " type: " + value.getClass());
        };
    }

    private static long requireInt(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Protocol parameter " + name + " is missing");
        }
        return value;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
