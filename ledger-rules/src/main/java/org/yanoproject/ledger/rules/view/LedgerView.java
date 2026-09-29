package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * Read-only ledger state for Conway transaction validation (ADR-056 §3).
 *
 * <p>Every read returns a {@link Lookup}. Across all methods, {@link Lookup.Unavailable} means the
 * view could not answer (store failure, not ready, snapshot released) and must never be read as
 * absence. The per-method docs say what {@link Lookup.Absent} means.</p>
 *
 * <p>Implementations must be safe for concurrent reads and must answer every read from one
 * consistent ledger state (one canonical generation plus, for overlays, a fixed set of layers).</p>
 */
public interface LedgerView {

    /**
     * @param outpoint output reference (hash in any case)
     * @return the unspent output; Absent when it does not exist or is already spent
     */
    Lookup<UtxoEntry> utxo(Outpoint outpoint);

    /**
     * @param credential stake credential
     * @return the account; Absent when the credential is not registered
     */
    Lookup<AccountState> account(CredentialKey credential);

    /**
     * @param poolId pool id
     * @return the pool; Absent when it is not registered (a retirement that has not yet taken
     *         effect is still Present, with {@link PoolState#retiringEpoch()} set)
     */
    Lookup<PoolState> pool(PoolId poolId);

    /**
     * Reverse VRF index used by the PV11 {@code VRFKeyHashAlreadyRegistered} check (Haskell
     * {@code psVRFKeyHashes}, which holds the VRF keys of active and future pool parameters).
     *
     * @param vrfKeyHashHex VRF key hash, hex
     * @return the pool that holds it; Absent when no registered pool uses it
     */
    Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex);

    /**
     * @param credential DRep credential
     * @return the DRep; Absent when it is not registered
     */
    Lookup<DRepState> drep(CredentialKey credential);

    /**
     * @param cold committee cold credential
     * @return what is known about it; Absent when the credential is neither in the elected
     *         committee nor has an authorization or resignation record
     */
    Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold);

    /**
     * Haskell allows several cold credentials to authorize the same hot credential, so this
     * returns all of them.
     *
     * @param hot committee hot credential
     * @return the members whose current (non-resigned) authorization is {@code hot}; Present with
     *         an empty list when there are none (never Absent)
     */
    Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot);

    /**
     * @return the cold credentials added by the {@code UpdateCommittee} proposals in
     *         {@code Proposals} (Haskell {@code cgceCommitteeProposals}, Ledger.hs:370), including
     *         proposals past their {@code expiresAfter} epoch that the next boundary has not yet
     *         removed (see {@link #proposal(GovActionId)}); Present with an empty set when there are
     *         none
     */
    Lookup<Set<CredentialKey>> committeeCandidates();

    /**
     * Reads a proposal from Haskell's {@code Proposals}.
     *
     * <p>A proposal stays in {@code Proposals} after its {@code expiresAfter} epoch until an epoch
     * boundary removes it, so a vote on it must fail with {@code VotingOnExpiredGovAction}
     * (Gov.hs:360-362, 607), not {@code GovActionsDoNotExist} (Gov.hs:605). The boundary into epoch
     * {@code E+1} removes a proposal when {@code expiresAfter < E}: RATIFY compares with
     * {@code reCurrentEpoch}, the epoch the DRep pulser started in (Ratify.hs:357-358,
     * DRepPulser.hs:398-404), not the new epoch. So a proposal with {@code expiresAfter = E} is still
     * Present throughout {@code E+1}.</p>
     *
     * @param id action id
     * @return the proposal while it is in {@code Proposals}, whether or not past its expiry; Absent
     *         only when it was never proposed or an epoch boundary removed it (enacted, expired or
     *         dropped as a sibling or descendant per RATIFY/ENACT)
     */
    Lookup<ProposalState> proposal(GovActionId id);

    /**
     * Enumerates the committee state: every cold credential {@link #committeeMemberByCold} answers
     * Present for (elected members, and candidates or former members that have a hot-key
     * authorization or a resignation), including the resigned flag and the elected term's expiry.
     * Callers that need the elected committee only filter on {@link CommitteeMemberState#isElected()}.
     *
     * @return the entries ordered by credential type, then hash; Present with an empty list when
     *         there are none (never Absent)
     */
    Lookup<List<CommitteeMemberState>> committeeMembers();

    /**
     * Enumerates Haskell's {@code Proposals}: every proposal {@link #proposal(GovActionId)} answers
     * Present for, including proposals past their {@code expiresAfter} epoch that no boundary has
     * removed yet.
     *
     * @return the proposals, oldest submission first (implementations document how they order
     *         proposals submitted in the same block); Present with an empty list when there are
     *         none (never Absent)
     */
    Lookup<List<ProposalState>> activeProposals();

    /** @return the enacted roots per purpose (never Absent; use {@link EnactedRoots#NONE}) */
    Lookup<EnactedRoots> enactedRoots();

    /**
     * @return the constitution's guardrail script hash; Absent when the constitution has no
     *         guardrail script
     */
    Lookup<String> guardrailScriptHash();

    /** @return {@code vsNumDormantEpochs}, the count of dormant epochs (never Absent) */
    Lookup<Long> dormantEpochs();

    /** @return the treasury at the start of the view's epoch (never Absent) */
    Lookup<BigInteger> treasury();

    /**
     * The epoch-effective protocol parameters (never Absent).
     *
     * <p><b>Cost models.</b> {@link ProtocolParams#getCostModelsRaw()} must hold each language's cost model as the
     * ledger stores it ({@code costModelsValid}): the parameter list in the ledger's canonical order, keyed
     * {@code PlutusV1}/{@code PlutusV2}/{@code PlutusV3}, with no entry for a language that has no cost model. The
     * Java rules hash these lists into the script integrity hash's language views; the named
     * {@code costModels} map is not used for that, and a view whose parameters carry cost models only in the named
     * form makes such a transaction {@code ENGINE.LedgerStateUnavailable}.</p>
     */
    Lookup<ProtocolParams> protocolParams();
}
