package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.effects.LedgerChange;
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
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.HexStrings;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A persistent (immutable, structure-sharing) stack of per-transaction {@link TxEffects} layers over
 * a base {@link LedgerView} (ADR-056 §3).
 *
 * <ul>
 *   <li>{@link #apply(TxEffects)} returns a <em>new</em> view with one more layer; the receiver is
 *       unchanged and stays valid, so a mempool state, a block selection and a frozen shadow view
 *       can share layers.</li>
 *   <li>Reads resolve newest layer first, then the base.</li>
 *   <li>Each layer is materialised at {@code apply} time: its changes are applied in order to the
 *       state below it, so a layer holds final per-key states for the keys the transaction
 *       touched. Two rules are applied lazily at read time instead, because they affect keys the
 *       transaction does not name: a DRep deregistration clears vote delegations to that DRep
 *       held in older state (GovCert.hs:246-255), and a dormant-period bump shifts every older
 *       DRep expiry (Certs.hs:308-328).</li>
 *   <li><b>PV &ge; 10 assumption.</b> Haskell clears delegations through the DRep's reverse index
 *       ({@code drepDelegs}); the overlay clears every account whose forward delegation names the
 *       DRep. The two agree only from PV10: before it, re-delegation left stale reverse entries
 *       (Deleg.hs:363-373, {@code preserveIncorrectDelegation = pv < 10}) until the PV10 hard fork
 *       rebuilt the index (HardFork.hs:70-104). The overlay is therefore only valid for PV &ge; 10
 *       state, which is also the Java engine's scope (invariant 7).</li>
 *   <li><b>VRF index.</b> {@link #poolByVrfKeyHash(String)} is maintained at every protocol version,
 *       with Haskell's {@code psVRFKeyHashes} bookkeeping (Pool.hs:265-270, 283-297), including its
 *       quirk that a second re-registration with a new VRF drops the previous future VRF even when
 *       it is also the active one. Haskell keeps the map only from PV11 and back-fills it at the
 *       PV11 fork from active and future parameters (HardFork.hs:106-115). Only PV11+ rules read it,
 *       so the difference is unobservable, provided the canonical view serves the same set: the
 *       VRF keys of active and future parameters of every registered pool.</li>
 *   <li>Enacted roots, the guardrail script, treasury and protocol parameters change only at an
 *       epoch boundary, so they always come from the base.</li>
 *   <li>Votes are recorded in {@link TxEffects} but not indexed: no Conway transaction rule reads
 *       earlier votes.</li>
 * </ul>
 *
 * <p>Reads cost O(layers) hash lookups. The view does not retain or release its base; the owner
 * manages the base's lifetime through {@link #base()} (see {@link Retainable}).</p>
 */
public final class OverlayLedgerView implements LedgerView {

    private final LedgerView base;
    private final Node top;

    private OverlayLedgerView(LedgerView base, Node top) {
        this.base = base;
        this.top = top;
    }

    /** @return an overlay with no layers over {@code base} */
    public static OverlayLedgerView over(LedgerView base) {
        return new OverlayLedgerView(Objects.requireNonNull(base, "base"), null);
    }

    /**
     * Pushes one transaction's effects.
     *
     * @param effects effects derived against this view
     * @return a new view with the layer on top; this view is unchanged
     * @throws LedgerStateUnavailableException if state the effects update is unavailable below
     * @throws IllegalStateException           if the effects update state that does not exist,
     *                                         which means they were not derived against this view
     */
    public OverlayLedgerView apply(TxEffects effects) {
        Objects.requireNonNull(effects, "effects");
        Layer layer = new LayerBuilder(this, effects).build();
        return new OverlayLedgerView(base, new Node(layer, top, layerCount() + 1));
    }

    /**
     * @param layerCount number of layers to keep, oldest first
     * @return a view with only the oldest {@code layerCount} layers
     */
    public OverlayLedgerView truncateTo(int layerCount) {
        if (layerCount < 0 || layerCount > layerCount()) {
            throw new IllegalArgumentException("layerCount out of range [0, " + layerCount() + "]: " + layerCount);
        }
        Node n = top;
        while (n != null && n.size > layerCount) {
            n = n.below;
        }
        return n == top ? this : new OverlayLedgerView(base, n);
    }

    public int layerCount() {
        return top == null ? 0 : top.size;
    }

    /** @return the transaction ids of the layers, oldest first */
    public List<String> layerTxIds() {
        List<String> ids = new ArrayList<>(layerCount());
        for (Node n = top; n != null; n = n.below) {
            ids.add(n.layer.txId);
        }
        return ids.reversed();
    }

    /**
     * Returns an immutable view with the same layers and base. Because the stack is persistent,
     * this view already is immutable and {@code freeze()} returns {@code this}; the method exists
     * so callers state the intent. Retaining the base snapshot for the frozen view's lifetime is
     * the caller's job ({@link #base()}).
     */
    public OverlayLedgerView freeze() {
        return this;
    }

    /** @return the base view, so owners can manage its lifetime */
    public LedgerView base() {
        return base;
    }

    // ---------------------------------------------------------------- reads

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint key = Outpoints.normalize(outpoint);
        for (Node n = top; n != null; n = n.below) {
            UtxoEntry produced = n.layer.produced.get(key);
            if (produced != null) {
                return Lookup.present(produced);
            }
            if (n.layer.consumed.contains(key)) {
                return Lookup.absent();
            }
        }
        return base.utxo(key);
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        // Allocated only when a newer layer deregistered a DRep (rare), not per read.
        Set<CredentialKey> unregisteredAbove = Set.of();
        for (Node n = top; n != null; n = n.below) {
            Lookup<AccountState> v = n.layer.accounts.get(credential);
            if (v != null) {
                return clearDelegation(v, unregisteredAbove);
            }
            if (!n.layer.unregisteredDReps.isEmpty()) {
                if (unregisteredAbove.isEmpty()) {
                    unregisteredAbove = new HashSet<>();
                }
                unregisteredAbove.addAll(n.layer.unregisteredDReps);
            }
        }
        return clearDelegation(base.account(credential), unregisteredAbove);
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        Objects.requireNonNull(poolId, "poolId");
        for (Node n = top; n != null; n = n.below) {
            Lookup<PoolState> v = n.layer.pools.get(poolId);
            if (v != null) {
                return v;
            }
        }
        return base.pool(poolId);
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        String key = HexStrings.normalize(vrfKeyHashHex, "vrf key hash", HexStrings.HASH32);
        for (Node n = top; n != null; n = n.below) {
            Lookup<PoolId> v = n.layer.vrf.get(key);
            if (v != null) {
                return v;
            }
        }
        return base.poolByVrfKeyHash(key);
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        // Allocated only when a newer layer ended a dormant period (rare), not per read.
        List<DormantDRepExpiriesBumped> bumpsAbove = List.of();
        for (Node n = top; n != null; n = n.below) {
            Lookup<DRepState> v = n.layer.dreps.get(credential);
            if (v != null) {
                return applyBumps(v, bumpsAbove);
            }
            if (!n.layer.bumps.isEmpty()) {
                if (bumpsAbove.isEmpty()) {
                    bumpsAbove = new ArrayList<>();
                }
                bumpsAbove.addAll(0, n.layer.bumps);
            }
        }
        return applyBumps(base.drep(credential), bumpsAbove);
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        Objects.requireNonNull(cold, "cold");
        for (Node n = top; n != null; n = n.below) {
            Lookup<CommitteeMemberState> v = n.layer.committee.get(cold);
            if (v != null) {
                return v;
            }
        }
        return base.committeeMemberByCold(cold);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cost: this scans every committee entry of every layer (O(total committee changes in the
     * stack)), because a later layer can move a cold credential away from {@code hot}. Committee
     * certificates are rare, so the scan is short in practice.</p>
     */
    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        Objects.requireNonNull(hot, "hot");
        Map<CredentialKey, CommitteeMemberState> newestByCold = new LinkedHashMap<>();
        Set<CredentialKey> overridden = new HashSet<>();
        for (Node n = top; n != null; n = n.below) {
            for (Map.Entry<CredentialKey, Lookup<CommitteeMemberState>> e : n.layer.committee.entrySet()) {
                if (overridden.add(e.getKey()) && e.getValue() instanceof Lookup.Present<CommitteeMemberState> p) {
                    newestByCold.put(e.getKey(), p.value());
                }
            }
        }
        Lookup<List<CommitteeMemberState>> fromBase = base.committeeMembersByHot(hot);
        if (!(fromBase instanceof Lookup.Present<List<CommitteeMemberState>> present)) {
            return fromBase;
        }
        List<CommitteeMemberState> result = new ArrayList<>();
        for (CommitteeMemberState m : present.value()) {
            if (!overridden.contains(m.cold())) {
                result.add(m);
            }
        }
        for (CommitteeMemberState m : newestByCold.values()) {
            if (!m.resigned() && hot.equals(m.hot())) {
                result.add(m);
            }
        }
        result.sort(Comparator.comparing((CommitteeMemberState m) -> m.cold().type())
                .thenComparing(m -> m.cold().hashHex()));
        return Lookup.present(List.copyOf(result));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The base list with every layer's committee changes (hot-key authorizations, resignations)
     * applied, newest layer winning per cold credential.</p>
     */
    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        Map<CredentialKey, Lookup<CommitteeMemberState>> newestByCold = new LinkedHashMap<>();
        for (Node n = top; n != null; n = n.below) {
            n.layer.committee.forEach(newestByCold::putIfAbsent);
        }
        Lookup<List<CommitteeMemberState>> fromBase = base.committeeMembers();
        if (newestByCold.isEmpty() || !(fromBase instanceof Lookup.Present<List<CommitteeMemberState>> present)) {
            return fromBase;
        }
        Map<CredentialKey, CommitteeMemberState> merged = new LinkedHashMap<>();
        for (CommitteeMemberState m : present.value()) {
            merged.put(m.cold(), m);
        }
        newestByCold.forEach((cold, v) -> {
            if (v instanceof Lookup.Present<CommitteeMemberState> p) {
                merged.put(cold, p.value());
            } else {
                merged.remove(cold);
            }
        });
        return Lookup.present(merged.values().stream().sorted(InMemoryLedgerView.COMMITTEE_ORDER).toList());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The base list followed by the proposals submitted in the layers, oldest layer first and in
     * body order within a layer (the order Haskell inserts them into {@code Proposals}).</p>
     */
    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        Lookup<List<ProposalState>> fromBase = base.activeProposals();
        if (top == null || !(fromBase instanceof Lookup.Present<List<ProposalState>> present)) {
            return fromBase;
        }
        List<List<ProposalState>> layersNewestFirst = new ArrayList<>();
        for (Node n = top; n != null; n = n.below) {
            if (!n.layer.proposalOrder.isEmpty()) {
                layersNewestFirst.add(n.layer.proposalOrder);
            }
        }
        if (layersNewestFirst.isEmpty()) {
            return fromBase;
        }
        Map<GovActionId, ProposalState> merged = new LinkedHashMap<>();
        for (ProposalState p : present.value()) {
            merged.put(p.id(), p);
        }
        for (List<ProposalState> layer : layersNewestFirst.reversed()) {
            for (ProposalState p : layer) {
                merged.put(p.id(), p);
            }
        }
        return Lookup.present(List.copyOf(merged.values()));
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        Set<CredentialKey> added = new HashSet<>();
        for (Node n = top; n != null; n = n.below) {
            added.addAll(n.layer.candidates);
        }
        Lookup<Set<CredentialKey>> fromBase = base.committeeCandidates();
        if (added.isEmpty() || !(fromBase instanceof Lookup.Present<Set<CredentialKey>> present)) {
            return fromBase;
        }
        added.addAll(present.value());
        return Lookup.present(Set.copyOf(added));
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        Objects.requireNonNull(id, "id");
        for (Node n = top; n != null; n = n.below) {
            ProposalState p = n.layer.proposals.get(id);
            if (p != null) {
                return Lookup.present(p);
            }
        }
        return base.proposal(id);
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return base.enactedRoots();
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return base.guardrailScriptHash();
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        for (Node n = top; n != null; n = n.below) {
            if (n.layer.dormantEpochs != null) {
                return Lookup.present(n.layer.dormantEpochs);
            }
        }
        return base.dormantEpochs();
    }

    @Override
    public Lookup<BigInteger> treasury() {
        return base.treasury();
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return base.protocolParams();
    }

    // ---------------------------------------------------------------- lazy rules

    /** A DRep deregistration clears vote delegations to it (GovCert.hs:246-255). */
    static Lookup<AccountState> clearDelegation(Lookup<AccountState> v, Set<CredentialKey> unregisteredDReps) {
        if (!unregisteredDReps.isEmpty() && v instanceof Lookup.Present<AccountState> p) {
            DRepTarget target = p.value().drepDelegation();
            if (target != null && target.kind() == DRepTarget.Kind.CREDENTIAL
                    && unregisteredDReps.contains(target.credential())) {
                return Lookup.present(p.value().withDRepDelegation(null));
            }
        }
        return v;
    }

    /** Applies dormant-period bumps, oldest first. */
    static Lookup<DRepState> applyBumps(Lookup<DRepState> v, List<DormantDRepExpiriesBumped> bumpsOldestFirst) {
        if (bumpsOldestFirst.isEmpty() || !(v instanceof Lookup.Present<DRepState> p)) {
            return v;
        }
        long expiry = p.value().expiryEpoch();
        for (DormantDRepExpiriesBumped bump : bumpsOldestFirst) {
            expiry = bump.bump(expiry);
        }
        return Lookup.present(p.value().withExpiryEpoch(expiry));
    }

    // ---------------------------------------------------------------- structure

    private record Node(Layer layer, Node below, int size) {
    }

    /** One transaction's materialised effects. Immutable after construction. */
    private static final class Layer {
        final String txId;
        final Set<Outpoint> consumed;
        final Map<Outpoint, UtxoEntry> produced;
        final Map<CredentialKey, Lookup<AccountState>> accounts;
        final Map<PoolId, Lookup<PoolState>> pools;
        final Map<String, Lookup<PoolId>> vrf;
        final Map<CredentialKey, Lookup<DRepState>> dreps;
        final Set<CredentialKey> unregisteredDReps;
        final List<DormantDRepExpiriesBumped> bumps;
        final Long dormantEpochs;
        final Map<CredentialKey, Lookup<CommitteeMemberState>> committee;
        final Map<GovActionId, ProposalState> proposals;
        final List<ProposalState> proposalOrder;
        final Set<CredentialKey> candidates;

        Layer(LayerBuilder b) {
            this.txId = b.effects.txId();
            this.consumed = Set.copyOf(b.effects.consumed());
            Map<Outpoint, UtxoEntry> out = new HashMap<>();
            for (UtxoEntry e : b.effects.produced()) {
                out.put(e.outpoint(), e);
            }
            this.produced = Map.copyOf(out);
            this.accounts = Map.copyOf(b.accounts);
            this.pools = Map.copyOf(b.pools);
            this.vrf = Map.copyOf(b.vrf);
            this.dreps = Map.copyOf(b.dreps);
            this.unregisteredDReps = Set.copyOf(b.unregisteredDReps);
            this.bumps = List.copyOf(b.bumps);
            this.dormantEpochs = b.dormantEpochs;
            this.committee = Map.copyOf(b.committee);
            this.proposals = Map.copyOf(b.proposals);
            this.proposalOrder = List.copyOf(b.proposals.values());
            this.candidates = Set.copyOf(b.candidates);
        }
    }

    /**
     * Applies one transaction's changes in order. Reads see the state below the layer plus the
     * changes applied so far, so a register-then-delegate transaction works.
     */
    private static final class LayerBuilder {
        final OverlayLedgerView below;
        final TxEffects effects;
        final Map<CredentialKey, Lookup<AccountState>> accounts = new HashMap<>();
        final Map<PoolId, Lookup<PoolState>> pools = new HashMap<>();
        final Map<String, Lookup<PoolId>> vrf = new HashMap<>();
        final Map<CredentialKey, Lookup<DRepState>> dreps = new HashMap<>();
        final Set<CredentialKey> unregisteredDReps = new HashSet<>();
        final List<DormantDRepExpiriesBumped> bumps = new ArrayList<>();
        Long dormantEpochs;
        final Map<CredentialKey, Lookup<CommitteeMemberState>> committee = new HashMap<>();
        final Map<GovActionId, ProposalState> proposals = new LinkedHashMap<>();
        final Set<CredentialKey> candidates = new HashSet<>();

        LayerBuilder(OverlayLedgerView below, TxEffects effects) {
            this.below = below;
            this.effects = effects;
        }

        Layer build() {
            for (LedgerChange change : effects.changes()) {
                applyChange(change);
            }
            return new Layer(this);
        }

        private void applyChange(LedgerChange change) {
            switch (change) {
                case AccountRegistered c -> accounts.put(c.credential(),
                        Lookup.present(AccountState.registered(c.credential(), c.deposit())));
                case AccountUnregistered c -> accounts.put(c.credential(), Lookup.absent());
                case StakeDelegated c -> updateAccount(c.credential(), a -> a.withDelegatedPool(c.pool()));
                case VoteDelegated c -> updateAccount(c.credential(), a -> a.withDRepDelegation(c.drep()));
                case RewardWithdrawn c -> updateAccount(c.credential(), a -> a.withRewardBalance(BigInteger.ZERO));
                case PoolRegistered c -> registerPool(c);
                case PoolReregistered c -> reregisterPool(c);
                case PoolRetirementScheduled c -> {
                    PoolState pool = readPool(c.pool()).require("pool " + c.pool());
                    pools.put(c.pool(), Lookup.present(pool.withRetiringEpoch(c.epoch())));
                }
                case DRepRegistered c -> dreps.put(c.credential(),
                        Lookup.present(new DRepState(c.credential(), c.deposit(), c.expiryEpoch())));
                case DRepUpdated c -> {
                    DRepState drep = readDRep(c.credential()).require("drep " + c.credential());
                    dreps.put(c.credential(), Lookup.present(drep.withExpiryEpoch(c.expiryEpoch())));
                }
                case DRepUnregistered c -> unregisterDRep(c.credential());
                case DRepActivityUpdated c -> readDRep(c.credential()).orElseThrowUnavailable().ifPresent(d ->
                        dreps.put(c.credential(), Lookup.present(d.withExpiryEpoch(c.expiryEpoch()))));
                case DormantDRepExpiriesBumped c -> bumpDormant(c);
                case CommitteeHotAuthorized c -> {
                    CommitteeMemberState member = readCommittee(c.cold()).orElseThrowUnavailable()
                            .orElseGet(() -> new CommitteeMemberState(c.cold(), null, false, null));
                    committee.put(c.cold(), Lookup.present(member.withHot(c.hot())));
                }
                case CommitteeResigned c -> {
                    CommitteeMemberState member = readCommittee(c.cold()).orElseThrowUnavailable()
                            .orElseGet(() -> new CommitteeMemberState(c.cold(), null, false, null));
                    committee.put(c.cold(), Lookup.present(member.asResigned()));
                }
                case ProposalSubmitted c -> {
                    proposals.put(c.proposal().id(), c.proposal());
                    candidates.addAll(InMemoryLedgerView.updateCommitteeCandidates(c.proposal()));
                }
                case VoteCast c -> {
                    // Votes are not indexed (see class doc).
                }
            }
        }

        private void updateAccount(CredentialKey cred, UnaryOperator<AccountState> update) {
            AccountState account = readAccount(cred).require("account " + cred);
            accounts.put(cred, Lookup.present(update.apply(account)));
        }

        private void registerPool(PoolRegistered c) {
            String vrfHex = vrfOf(c.params());
            pools.put(c.pool(), Lookup.present(new PoolState(c.pool(), c.deposit(), vrfHex, null, c.params(), null)));
            vrf.put(vrfHex, Lookup.present(c.pool()));
        }

        /** Shelley/Rules/Pool.hs:277-305, including the psVRFKeyHashes bookkeeping. */
        private void reregisterPool(PoolReregistered c) {
            PoolState pool = readPool(c.pool()).require("pool " + c.pool());
            String newVrf = vrfOf(c.params());
            PoolRegistration previousFuture = pool.futureParams();
            if (previousFuture != null) {
                String previousVrf = vrfOf(previousFuture);
                if (!previousVrf.equals(newVrf)) {
                    vrf.put(previousVrf, Lookup.absent());
                }
            }
            vrf.put(newVrf, Lookup.present(c.pool()));
            pools.put(c.pool(), Lookup.present(pool.withFutureParams(c.params()).withRetiringEpoch(null)));
        }

        private void unregisterDRep(CredentialKey cred) {
            dreps.put(cred, Lookup.absent());
            unregisteredDReps.add(cred);
            Set<CredentialKey> single = Set.of(cred);
            accounts.replaceAll((k, v) -> clearDelegation(v, single));
        }

        private void bumpDormant(DormantDRepExpiriesBumped bump) {
            bumps.add(bump);
            dormantEpochs = 0L;
            List<DormantDRepExpiriesBumped> single = List.of(bump);
            dreps.replaceAll((k, v) -> applyBumps(v, single));
        }

        private Lookup<AccountState> readAccount(CredentialKey cred) {
            Lookup<AccountState> v = accounts.get(cred);
            return v != null ? v : clearDelegation(below.account(cred), unregisteredDReps);
        }

        private Lookup<PoolState> readPool(PoolId id) {
            Lookup<PoolState> v = pools.get(id);
            return v != null ? v : below.pool(id);
        }

        private Lookup<DRepState> readDRep(CredentialKey cred) {
            Lookup<DRepState> v = dreps.get(cred);
            return v != null ? v : applyBumps(below.drep(cred), bumps);
        }

        private Lookup<CommitteeMemberState> readCommittee(CredentialKey cold) {
            Lookup<CommitteeMemberState> v = committee.get(cold);
            return v != null ? v : below.committeeMemberByCold(cold);
        }

        private static String vrfOf(PoolRegistration params) {
            return HexStrings.normalize(HexUtil.encodeHexString(
                    Objects.requireNonNull(params.getVrfKeyHash(), "vrfKeyHash")), "vrf key hash", HexStrings.HASH32);
        }
    }
}
