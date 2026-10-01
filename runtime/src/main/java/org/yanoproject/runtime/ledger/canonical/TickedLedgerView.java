package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.HexStrings;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledgerstate.EpochBoundaryPreview;
import org.yanoproject.ledgerstate.EpochBoundaryPreview.GovernanceEffects;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader.StoredProposal;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.runtime.tx.ProtocolParamsMapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntToLongFunction;
import java.util.function.LongToIntFunction;

/**
 * The ledger state at a target slot (ADR-056 §3, invariant 6): the canonical state of one
 * {@link CanonicalSnapshot}, advanced through the epoch boundary between the ledger tip and the
 * slot when there is one.
 *
 * <h2>Which epoch</h2>
 * The decision uses the snapshot's {@link CanonicalTip#ledgerEpoch()}, never the tip slot's epoch:
 * a block producer applies the boundary in a write section of its own before selecting the block
 * that crosses it, so the ledger can already be in {@code E+1} while the tip slot is in {@code E}
 * (ticking again would apply the boundary twice).
 * <ul>
 *   <li>target epoch = ledger epoch: {@link Mode#CANONICAL}, every read is the canonical view's;</li>
 *   <li>target epoch = ledger epoch + 1: {@link Mode#TICKED}, the canonical view plus the boundary's
 *       validation-visible effects, dry-run over the same snapshot ({@link EpochBoundaryPreview});</li>
 *   <li>otherwise (more than one boundary ahead, behind the ledger, or an unknown epoch):
 *       {@link Mode#UNAVAILABLE}, every read is {@link Lookup.Unavailable}.</li>
 * </ul>
 *
 * <h2>Ticked values</h2>
 * <ul>
 *   <li><b>Protocol parameters</b> of the new epoch: finalization (carry forward, era overlays,
 *       pre-Conway pending updates) then the enacted ParameterChange updates in order.</li>
 *   <li><b>Pools</b>: pools POOLREAP retires are absent from {@link #pool} and the VRF index; the
 *       others are read at the new epoch (so re-registered parameters become active).</li>
 *   <li><b>Governance</b>: proposals removed by Phase 1 (enacted, expired, siblings, descendants) are
 *       absent; enacted roots, committee (UpdateCommittee, NoConfidence) and constitution guardrail
 *       are the post-enactment values; committee candidates come from the remaining proposals.</li>
 *   <li><b>Unchanged by the boundary</b>: UTxO, DRep registration and deposit (an inactive DRep is
 *       still registered and Present). DRep expiry and the
 *       dormant-epoch counter are passed through from the canonical view although the boundary can
 *       change them: no Conway transaction rule checks them (cardano-ledger f649f975: GOV only checks
 *       that a DRep voter is registered, {@code Conway/Rules/Gov.hs:593-604}, and that DReps may vote
 *       on the action type, {@code Gov.hs:371-376}; expiry is read by RATIFY, {@code Ratify.hs:267},
 *       and only updated by LEDGER/CERTS/GOVCERT, {@code Ledger.hs:389-390},
 *       {@code Certs.hs:257-328}, {@code GovCert.hs:222-226, 262-270}). They only feed effects of
 *       later transactions, which are never persisted.</li>
 * </ul>
 *
 * <h2>Fail closed (retryable once the boundary block is applied)</h2>
 * <ul>
 *   <li>{@link #account}: reward balances of the completed epoch are not dry-run, so transactions
 *       with withdrawals, (de)registrations or delegations are rejected until the boundary block
 *       lands; plain payments are unaffected.</li>
 *   <li>{@link #treasury}: depends on the rewards.</li>
 *   <li>A HardForkInitiation enacted at the boundary: every read.</li>
 *   <li>An era or protocol-version transition at the boundary (a pre-Conway update changing the
 *       protocol version, or protocol version 9+ while the era answers captured with the snapshot do
 *       not yet place the new epoch in Conway — era metadata learns an era only from its first block):
 *       every governance read and the protocol parameters.</li>
 *   <li>Governance tracking disabled, the Conway genesis bootstrap at this boundary, or governance
 *       epoch processing not configured: every governance read, DReps and the dormant counter
 *       included, and (from protocol version 9) the protocol parameters, since enactments are
 *       unknown.</li>
 *   <li>POOLREAP needing a disabled refund processor, or anything the dry run cannot read: the
 *       affected reads.</li>
 * </ul>
 *
 * <h2>Ownership and caching</h2>
 * The view holds its own reference to the snapshot (through a private {@link CanonicalLedgerView})
 * and releases it on {@link #close()}; closing it never closes the caller's base view. The dry run
 * is computed once per snapshot and target epoch and memoized in the snapshot
 * ({@link CanonicalSnapshot#memoized}), so it is shared by every view over that snapshot and freed
 * with it.
 */
public final class TickedLedgerView implements LedgerView, AutoCloseable {

    /** How the view answers. */
    public enum Mode {
        /** The target slot is in the ledger epoch: the canonical view. */
        CANONICAL,
        /** One boundary ahead: canonical plus the boundary's dry-run effects. */
        TICKED,
        /** Every read is unavailable. */
        UNAVAILABLE
    }

    private static final String RETRY = "; retry after the block that crosses the boundary is applied";

    private final CanonicalLedgerView canonical;
    private final CanonicalSnapshot snapshot;
    private final Mode mode;
    private final int targetEpoch;
    private final String unavailableReason;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TickedLedgerView(CanonicalLedgerView canonical, Mode mode, int targetEpoch, String unavailableReason) {
        this.canonical = canonical;
        this.snapshot = canonical.snapshot();
        this.mode = mode;
        this.targetEpoch = targetEpoch;
        this.unavailableReason = unavailableReason;
    }

    /**
     * Creates the view of {@code base}'s snapshot at {@code targetSlot}. The new view takes its own
     * reference to the snapshot; {@code base} stays owned by the caller.
     *
     * @throws IllegalStateException if the snapshot is already freed
     */
    public static TickedLedgerView of(CanonicalLedgerView base, long targetSlot) {
        Objects.requireNonNull(base, "base");
        CanonicalLedgerView own = CanonicalLedgerView.over(base.snapshot());
        CanonicalSnapshot snapshot = own.snapshot();
        int ledgerEpoch = snapshot.tip().ledgerEpoch();
        int targetEpoch = targetSlot >= 0 ? snapshot.epochOfSlot(targetSlot) : -1;
        if (ledgerEpoch < 0) {
            return new TickedLedgerView(own, Mode.UNAVAILABLE, targetEpoch,
                    "the ledger epoch of canonical generation " + snapshot.generation() + " is unknown");
        }
        if (targetEpoch < 0) {
            return new TickedLedgerView(own, Mode.UNAVAILABLE, targetEpoch,
                    "the epoch of target slot " + targetSlot + " is unknown");
        }
        if (targetEpoch == ledgerEpoch) {
            return new TickedLedgerView(own, Mode.CANONICAL, targetEpoch, null);
        }
        if (targetEpoch == ledgerEpoch + 1) {
            return new TickedLedgerView(own, Mode.TICKED, targetEpoch, null);
        }
        if (targetEpoch > ledgerEpoch) {
            return new TickedLedgerView(own, Mode.UNAVAILABLE, targetEpoch,
                    "target slot " + targetSlot + " (epoch " + targetEpoch + ") is more than one boundary ahead of"
                            + " the ledger epoch " + ledgerEpoch + RETRY);
        }
        return new TickedLedgerView(own, Mode.UNAVAILABLE, targetEpoch,
                "target slot " + targetSlot + " (epoch " + targetEpoch + ") precedes the ledger epoch " + ledgerEpoch);
    }

    /**
     * The slot mempool admission validates at (ADR-056 step 1d, decision 6b): the slot after the tip, as
     * Haskell's mempool ticks to. When a block producer has already applied the next epoch's boundary in its
     * own write section (the ledger epoch is one ahead of that slot's epoch), the ledger state already
     * <em>is</em> the new epoch's, and the next block will be in it: the slot is moved to the first slot of the
     * ledger epoch, so the view is the unticked canonical one and {@code ValidationEnv.currentEpoch} equals
     * the ledger epoch. Larger gaps are left as they are ({@link Mode#UNAVAILABLE}).
     *
     * @return the admission slot for {@code snapshot}
     */
    public static long admissionSlot(CanonicalSnapshot snapshot) {
        return admissionSlot(snapshot.tip(), snapshot::epochOfSlot, snapshot::epochStartSlot);
    }

    /** {@link #admissionSlot(CanonicalSnapshot)} from a published tip and the gate's epoch functions. */
    static long admissionSlot(CanonicalTip tip, LongToIntFunction epochOfSlot, IntToLongFunction epochStartSlot) {
        long tipSlot = tip.slot();
        long target = tipSlot >= 0 ? tipSlot + 1 : 0;
        int ledgerEpoch = tip.ledgerEpoch();
        int targetEpoch = epochOfSlot.applyAsInt(target);
        if (ledgerEpoch >= 0 && targetEpoch >= 0 && targetEpoch == ledgerEpoch - 1) {
            long start = epochStartSlot.applyAsLong(ledgerEpoch);
            if (start >= 0) {
                return Math.max(target, start);
            }
        }
        return target;
    }

    /** @return how this view answers */
    public Mode mode() {
        return mode;
    }

    /** @return the epoch of the target slot (-1 when unknown) */
    public int targetEpoch() {
        return targetEpoch;
    }

    /** @return the canonical generation every read answers from */
    public long generation() {
        return snapshot.generation();
    }

    /** @return the snapshot this view reads (still owned by this view) */
    public CanonicalSnapshot snapshot() {
        return snapshot;
    }

    /**
     * The dry run behind a {@link Mode#TICKED} view (for diagnostics and the ticking gate): pool
     * refunds, reward_rest credits and the governance effects, including the values the view itself
     * fails closed on.
     *
     * @return the preview; unavailable in the other modes or when it cannot be computed
     */
    public Lookup<EpochBoundaryPreview> boundaryPreview() {
        if (mode != Mode.TICKED) {
            return Lookup.unavailable("no boundary is ticked in mode " + mode);
        }
        return preview();
    }

    /** Releases this view's reference to its snapshot. Idempotent. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            canonical.close();
        }
    }

    // ------------------------------------------------------------------ dry run

    private Lookup<EpochBoundaryPreview> preview() {
        if (closed.get()) {
            return Lookup.unavailable("ticked ledger view closed");
        }
        int epoch = targetEpoch;
        return snapshot.memoized("ticked-boundary:" + epoch, "dry run of the boundary into epoch " + epoch, state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            if (ledger == null) {
                return Lookup.unavailable("account state is disabled");
            }
            EpochBoundaryPreview.Inputs inputs = state.boundaryInputs();
            if (inputs == null || inputs.newEpoch() != epoch) {
                return Lookup.unavailable("no boundary dry-run inputs were captured for epoch " + epoch);
            }
            return snapshot.generationMemoized("ticked-boundary:" + epoch,
                    () -> Lookup.present(EpochBoundaryPreview.compute(ledger, inputs)));
        });
    }

    /**
     * Runs {@code read} in the mode's way: canonical reads in {@link Mode#CANONICAL}, the dry run in
     * {@link Mode#TICKED}.
     */
    private <T> Lookup<T> answer(String what, Function<CanonicalLedgerView, Lookup<T>> canonicalRead,
                                 Function<EpochBoundaryPreview, Lookup<T>> tickedRead) {
        if (closed.get()) {
            return Lookup.unavailable(what + ": ticked ledger view closed");
        }
        return switch (mode) {
            case CANONICAL -> canonicalRead.apply(canonical);
            case UNAVAILABLE -> Lookup.unavailable(what + ": " + unavailableReason);
            case TICKED -> {
                Lookup<EpochBoundaryPreview> preview = preview();
                if (!(preview instanceof Lookup.Present<EpochBoundaryPreview> present)) {
                    yield Lookup.unavailable(what + ": " + ((Lookup.Unavailable<EpochBoundaryPreview>) preview).reason());
                }
                EpochBoundaryPreview boundary = present.value();
                if (boundary.unavailableReason() != null) {
                    yield Lookup.unavailable(what + ": " + boundary.unavailableReason() + RETRY);
                }
                if (boundary.hardForkEnacted()) {
                    yield Lookup.unavailable(what + ": a HardForkInitiation is enacted at the boundary into epoch "
                            + targetEpoch + "; the ticked state is not dry-run" + RETRY);
                }
                yield tickedRead.apply(boundary);
            }
        };
    }

    /** Governance-dependent read of a ticked view. */
    private <T> Lookup<T> governance(String what, EpochBoundaryPreview boundary,
                                     Function<GovernanceEffects, Lookup<T>> read) {
        GovernanceEffects effects = boundary.governance();
        if (effects == null) {
            return Lookup.unavailable(what + ": " + boundary.governanceUnavailableReason()
                    + " (governance effects of the boundary into epoch " + targetEpoch + " are unknown)" + RETRY);
        }
        return read.apply(effects);
    }

    // ------------------------------------------------------------------ UTxO and accounts

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        return answer("utxo", view -> view.utxo(outpoint), boundary -> canonical.utxo(outpoint));
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        return answer("account " + credential, view -> view.account(credential),
                boundary -> Lookup.unavailable("account " + credential + ": reward balances of epoch "
                        + (targetEpoch - 1) + " are not dry-run at the boundary into epoch " + targetEpoch + RETRY));
    }

    // ------------------------------------------------------------------ pools

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        Objects.requireNonNull(poolId, "poolId");
        String what = "pool " + poolId;
        return answer(what, view -> view.pool(poolId), boundary -> {
            if (boundary.poolsUnavailableReason() != null) {
                return Lookup.unavailable(what + ": " + boundary.poolsUnavailableReason() + RETRY);
            }
            if (boundary.retiredPools().contains(poolId.hashHex())) {
                return Lookup.absent();
            }
            return snapshot.read(what, state -> {
                LedgerStateSnapshotReader ledger = state.ledger();
                if (ledger == null) {
                    return Lookup.unavailable("account state is disabled");
                }
                return CanonicalLedgerView.readPool(ledger, poolId, targetEpoch);
            });
        });
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        String key = HexStrings.normalize(vrfKeyHashHex, "vrf key hash", HexStrings.HASH32);
        String what = "pool by VRF key hash";
        return answer(what, view -> view.poolByVrfKeyHash(key), boundary -> {
            if (boundary.poolsUnavailableReason() != null) {
                return Lookup.unavailable(what + ": " + boundary.poolsUnavailableReason() + RETRY);
            }
            Set<String> retired = boundary.retiredPools();
            int epoch = targetEpoch;
            Lookup<Map<String, PoolId>> index = snapshot.memoized("ticked-pool-vrf-index:" + epoch,
                    "pool VRF index at epoch " + epoch, state -> CanonicalLedgerView.vrfIndex(state, epoch, retired));
            return index.map(byVrf -> byVrf.get(key));
        });
    }

    // ------------------------------------------------------------------ DReps (passed through)

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        return answer("drep " + credential, view -> view.drep(credential),
                boundary -> governance("drep " + credential, boundary, effects -> canonical.drep(credential)));
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return answer("dormant epochs", CanonicalLedgerView::dormantEpochs,
                boundary -> governance("dormant epochs", boundary, effects -> canonical.dormantEpochs()));
    }

    // ------------------------------------------------------------------ committee

    private static CanonicalLedgerView.CommitteeRecords tickedCommittee(GovernanceEffects effects) {
        Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> members = effects.committeeMembers();
        return new CanonicalLedgerView.CommitteeRecords() {
            @Override
            public Optional<CommitteeMemberRecord> member(int credType, String coldHash) {
                return Optional.ofNullable(members.get(
                        new GovernanceStateStore.CredentialKey(credType, coldHash.toLowerCase(Locale.ROOT))));
            }

            @Override
            public Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> all() {
                return members;
            }

            @Override
            public boolean certificatePathDropped(GovernanceStateStore.CredentialKey cold) {
                return effects.prunedCommitteeColds().contains(cold);
            }
        };
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        Objects.requireNonNull(cold, "cold");
        String what = "committee member " + cold;
        return answer(what, view -> view.committeeMemberByCold(cold), boundary -> governance(what, boundary,
                effects -> snapshot.read(what, state -> {
                    LedgerStateSnapshotReader ledger = state.ledger();
                    if (ledger == null) {
                        return Lookup.unavailable("governance tracking is disabled");
                    }
                    return CanonicalLedgerView.committeeMemberByCold(ledger, tickedCommittee(effects), cold);
                })));
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        Objects.requireNonNull(hot, "hot");
        String what = "committee members by hot " + hot;
        return answer(what, view -> view.committeeMembersByHot(hot), boundary -> governance(what, boundary,
                effects -> snapshot.read(what,
                        state -> CanonicalLedgerView.committeeMembersByHot(tickedCommittee(effects), hot))));
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        String what = "committee members";
        return answer(what, CanonicalLedgerView::committeeMembers, boundary -> governance(what, boundary,
                effects -> snapshot.read(what, state -> {
                    LedgerStateSnapshotReader ledger = state.ledger();
                    if (ledger == null) {
                        return Lookup.unavailable("governance tracking is disabled");
                    }
                    return CanonicalLedgerView.committeeMembers(ledger, tickedCommittee(effects));
                })));
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        String what = "committee candidates";
        return answer(what, CanonicalLedgerView::committeeCandidates, boundary -> governance(what, boundary,
                effects -> snapshot.read(what, state -> {
                    Optional<GovernanceSnapshotReader> governance = CanonicalLedgerView.governance(state);
                    if (governance.isEmpty()) {
                        return Lookup.unavailable("governance tracking is disabled");
                    }
                    return CanonicalLedgerView.committeeCandidates(remaining(governance.get(), effects));
                })));
    }

    // ------------------------------------------------------------------ proposals and roots

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        Objects.requireNonNull(id, "id");
        String what = "proposal " + id;
        return answer(what, view -> view.proposal(id), boundary -> governance(what, boundary,
                effects -> removedKeys(effects).contains(key(id.txHashHex(), id.index()))
                        ? Lookup.absent()
                        : canonical.proposal(id)));
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        String what = "active proposals";
        return answer(what, CanonicalLedgerView::activeProposals, boundary -> governance(what, boundary,
                effects -> snapshot.read(what, state -> {
                    Optional<GovernanceSnapshotReader> governance = CanonicalLedgerView.governance(state);
                    if (governance.isEmpty()) {
                        return Lookup.unavailable("governance tracking is disabled");
                    }
                    return CanonicalLedgerView.activeProposals(remaining(governance.get(), effects));
                })));
    }

    private static List<StoredProposal> remaining(GovernanceSnapshotReader governance, GovernanceEffects effects) {
        Set<String> removed = removedKeys(effects);
        List<StoredProposal> remaining = new ArrayList<>();
        for (StoredProposal proposal : governance.proposals()) {
            if (!removed.contains(key(proposal.txHash(), proposal.index()))) {
                remaining.add(proposal);
            }
        }
        return remaining;
    }

    private static Set<String> removedKeys(GovernanceEffects effects) {
        Set<String> keys = new HashSet<>();
        for (var id : effects.removedProposals()) {
            keys.add(key(id.getTransactionId(), id.getGov_action_index()));
        }
        return keys;
    }

    private static String key(String txHash, int index) {
        return txHash.toLowerCase(Locale.ROOT) + "#" + index;
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        String what = "enacted roots";
        return answer(what, CanonicalLedgerView::enactedRoots, boundary -> governance(what, boundary, effects -> {
            Lookup<EnactedRoots> base = canonical.enactedRoots();
            if (!(base instanceof Lookup.Present<EnactedRoots> present) || effects.newRoots().isEmpty()) {
                return base;
            }
            EnactedRoots roots = present.value();
            return Lookup.present(new EnactedRoots(
                    newRoot(effects, GovActionType.PARAMETER_CHANGE_ACTION, roots.pparamUpdate()),
                    newRoot(effects, GovActionType.HARD_FORK_INITIATION_ACTION, roots.hardFork()),
                    newRoot(effects, GovActionType.UPDATE_COMMITTEE, roots.committee()),
                    newRoot(effects, GovActionType.NEW_CONSTITUTION, roots.constitution())));
        }));
    }

    private static GovActionId newRoot(GovernanceEffects effects, GovActionType purpose, GovActionId current) {
        var enacted = effects.newRoots().get(purpose);
        return enacted != null ? new GovActionId(enacted.getTransactionId(), enacted.getGov_action_index()) : current;
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        String what = "guardrail script hash";
        return answer(what, CanonicalLedgerView::guardrailScriptHash, boundary -> governance(what, boundary,
                effects -> {
                    Lookup<String> guardrail = CanonicalLedgerView.guardrail(effects.constitution());
                    return guardrail instanceof Lookup.Unavailable<String> u
                            ? Lookup.unavailable(what + ": " + u.reason())
                            : guardrail;
                }));
    }

    // ------------------------------------------------------------------ pots and parameters

    @Override
    public Lookup<BigInteger> treasury() {
        return answer("treasury", CanonicalLedgerView::treasury,
                boundary -> Lookup.unavailable("treasury: the treasury of epoch " + targetEpoch
                        + " depends on the rewards of epoch " + (targetEpoch - 1) + ", which are not dry-run" + RETRY));
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        String what = "protocol parameters";
        return answer(what, CanonicalLedgerView::protocolParams, boundary -> {
            Optional<ProtocolParamsSnapshot> params = boundary.protocolParams();
            if (params.isEmpty()) {
                return Lookup.unavailable(what + ": " + boundary.protocolParamsUnavailableReason() + RETRY);
            }
            // A fresh CCL object per read: ProtocolParams is a mutable bean.
            return Lookup.present(ProtocolParamsMapper.fromSnapshot(params.get()));
        });
    }

    @Override
    public String toString() {
        return "TickedLedgerView[mode=" + mode + ", targetEpoch=" + targetEpoch + ", " + snapshot + "]";
    }
}
