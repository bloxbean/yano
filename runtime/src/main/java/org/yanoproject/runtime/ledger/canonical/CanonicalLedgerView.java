package org.yanoproject.runtime.ledger.canonical;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.util.ProposalParamUpdateKeys;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.HexStrings;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledgerstate.AccountStateCborCodec.DRepDelegationRecord;
import org.yanoproject.ledgerstate.AccountStateCborCodec.PoolRegistrationData;
import org.yanoproject.ledgerstate.AccountStateCborCodec.StakeAccount;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.ConstitutionRecord;
import org.yanoproject.ledgerstate.governance.GovernanceCborCodec.LastEnactedAction;
import org.yanoproject.ledgerstate.governance.ConwayGenesisGovernance;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader.StoredProposal;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.ledgerstate.governance.model.DRepStateRecord;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;
import org.yanoproject.runtime.tx.ProtocolParamsMapper;
import org.yanoproject.runtime.utxo.UtxoSnapshotReader;
import org.yanoproject.runtime.utxo.UtxoSnapshotReader.StoredAsset;
import org.yanoproject.runtime.utxo.UtxoSnapshotReader.StoredOutput;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LedgerView} over one {@link CanonicalSnapshot} (ADR-056 §3).
 *
 * <p>The view retains its snapshot when created and releases that reference on {@link #close()}.
 * Every read goes to the snapshot's RocksDB snapshot or its in-memory copy, never to live stores.
 * Outcomes follow invariant 2: a missing record is {@link Lookup.Absent}; a read or decoding error, a
 * released snapshot, a disabled store, or state the store does not keep is {@link Lookup.Unavailable}
 * with a reason.</p>
 *
 * <p>Mapping notes (what Yano stores versus what the view model expects):</p>
 * <ul>
 *   <li><b>Pools.</b> The live registration record holds the <em>latest</em> parameters and the
 *       lifecycle deposit, but not relays or metadata, so {@link PoolState#params()} and
 *       {@link PoolState#futureParams()} are {@code null}. The active VRF key hash comes from the
 *       pool-parameter history row active at the ledger epoch {@code E} (rows are keyed registration
 *       epoch + 2 for fresh registrations and + 3 for re-registrations, so the newest row keyed
 *       {@code <= E+2} is the parameter set Haskell treats as active). A pool without such a row
 *       falls back to the live record only when it also has no later row; otherwise the live record
 *       could be future parameters and the read is unavailable.</li>
 *   <li><b>VRF index</b> (Haskell {@code psVRFKeyHashes}, {@code Shelley/Rules/Pool.hs:263-283}):
 *       built once per snapshot from a scan of every registered pool, holding the active VRF key hash
 *       and the live record's (the future parameters' after a re-registration in the current epoch).
 *       Because {@code futureParams} is {@code null}, an overlay re-registration that replaces a
 *       canonical pending future VRF cannot drop the old one the way Haskell does; that quirk only
 *       matters from PV11.</li>
 *   <li><b>DReps.</b> Registration and deposit come from the certificate record; the expiry from the
 *       governance DRep record, whether or not that record is marked active (activity only matters to
 *       ratification; a registered but inactive DRep can still vote and be delegated to). Yano defers Haskell's per-transaction dormant flush to epoch
 *       boundaries, so after a proposal-carrying transaction {@code (expiry, dormantEpochs)} can
 *       differ from Haskell's pair mid-epoch; their sum (the effective expiry) agrees.</li>
 *   <li><b>Committee.</b> The governance committee record (members, and hot-key placeholders with
 *       expiry 0 for candidates) is authoritative; a resignation of a cold credential that has no
 *       governance record is taken from the certificate-path resignation marker.</li>
 *   <li><b>Ordering.</b> {@link #activeProposals()} is ordered by proposal slot, then transaction id,
 *       then index; Yano does not keep the order of proposal transactions within one block, which
 *       Haskell's insertion order would follow.</li>
 *   <li><b>Governance disabled</b> ({@code yano.governance.enabled=false}, the default): every read
 *       that needs governance state, DReps included, is unavailable, never absent.</li>
 * </ul>
 */
public final class CanonicalLedgerView implements LedgerView, AutoCloseable {

    private final CanonicalSnapshot snapshot;
    private final AtomicBoolean closed = new AtomicBoolean();

    private CanonicalLedgerView(CanonicalSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Creates a view that holds its own reference to {@code snapshot}.
     *
     * @throws IllegalStateException if the snapshot is already freed
     */
    public static CanonicalLedgerView over(CanonicalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshot.retain();
        return new CanonicalLedgerView(snapshot);
    }

    /** @return the base snapshot (still owned by this view; retain it to keep it past {@link #close()}) */
    public CanonicalSnapshot snapshot() {
        return snapshot;
    }

    /** @return the canonical generation every read answers from */
    public long generation() {
        return snapshot.generation();
    }

    /** Releases this view's reference to its snapshot. Idempotent. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            snapshot.release();
        }
    }

    private <T> Lookup<T> read(String what, CanonicalSnapshot.SnapshotRead<T> read) {
        if (closed.get()) {
            return Lookup.unavailable(what + ": canonical ledger view closed");
        }
        return snapshot.read(what, read);
    }

    // ------------------------------------------------------------------ UTxO

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint key = Outpoints.normalize(outpoint);
        return read("utxo " + key.txHash() + "#" + key.index(), state -> {
            UtxoSnapshotReader utxo = state.utxo();
            if (utxo == null) {
                return Lookup.unavailable("UTxO store is disabled");
            }
            Optional<StoredOutput> stored = utxo.unspent(key.txHash(), key.index());
            return stored.isEmpty() ? Lookup.absent() : Lookup.present(toEntry(key, stored.get()));
        });
    }

    private static UtxoEntry toEntry(Outpoint outpoint, StoredOutput stored) throws Exception {
        Map<String, List<Asset>> byPolicy = new LinkedHashMap<>();
        for (StoredAsset asset : stored.assets()) {
            byPolicy.computeIfAbsent(asset.policyIdHex(), ignored -> new ArrayList<>())
                    .add(new Asset("0x" + HexUtil.encodeHexString(asset.assetName()), asset.quantity()));
        }
        List<MultiAsset> multiAssets = new ArrayList<>();
        byPolicy.forEach((policy, assets) -> multiAssets.add(new MultiAsset(policy, assets)));
        Value value = new Value(stored.lovelace(), multiAssets);

        byte[] inlineDatum = stored.inlineDatum();
        TransactionOutput output = new TransactionOutput(
                stored.address(),
                value,
                stored.datumHashHex() != null ? HexUtil.decodeHexString(stored.datumHashHex()) : null,
                inlineDatum != null ? PlutusData.deserialize(inlineDatum) : null,
                stored.referenceScript());
        return new UtxoEntry(outpoint, output, inlineDatum);
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        return read("account " + credential, state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            if (ledger == null) {
                return Lookup.unavailable("account state is disabled");
            }
            String genesisUnmodelled = genesisStateUnmodelled(state);
            if (genesisUnmodelled != null) {
                return Lookup.unavailable(genesisUnmodelled);
            }
            int type = credential.type().tag();
            Optional<StakeAccount> account = ledger.stakeAccount(type, credential.hashHex());
            if (account.isEmpty()) {
                return Lookup.absent();
            }
            PoolId pool = ledger.delegatedPool(type, credential.hashHex()).map(PoolId::new).orElse(null);
            DRepTarget drep = ledger.drepDelegation(type, credential.hashHex())
                    .map(CanonicalLedgerView::toDRepTarget)
                    .orElse(null);
            return Lookup.present(new AccountState(credential, account.get().deposit(), account.get().reward(),
                    pool, drep));
        });
    }

    private static DRepTarget toDRepTarget(DRepDelegationRecord record) {
        return switch (record.drepType()) {
            case 0 -> DRepTarget.credential(CredentialKey.key(record.drepHash()));
            case 1 -> DRepTarget.credential(CredentialKey.script(record.drepHash()));
            case 2 -> DRepTarget.ALWAYS_ABSTAIN;
            case 3 -> DRepTarget.ALWAYS_NO_CONFIDENCE;
            default -> throw new IllegalStateException("Unknown stored DRep delegation type " + record.drepType());
        };
    }

    // ------------------------------------------------------------------ pools

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        Objects.requireNonNull(poolId, "poolId");
        return read("pool " + poolId, state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            if (ledger == null) {
                return Lookup.unavailable("account state is disabled");
            }
            return readPool(ledger, poolId, snapshot.tip().ledgerEpoch());
        });
    }

    /**
     * Reads a pool as it is at ledger epoch {@code epoch} (see the class doc for the active VRF key
     * hash). Shared with {@link TickedLedgerView}, which reads at the ticked epoch.
     */
    static Lookup<PoolState> readPool(LedgerStateSnapshotReader ledger, PoolId poolId, int epoch) throws Exception {
        String hash = poolId.hashHex();
        Optional<PoolRegistrationData> live = ledger.poolRegistration(hash);
        if (live.isEmpty()) {
            return Lookup.absent();
        }
        Lookup<String> vrf = activeVrf(ledger, hash, live.get(), epoch);
        if (!(vrf instanceof Lookup.Present<String> present)) {
            return Lookup.unavailable(((Lookup.Unavailable<String>) vrf).reason());
        }
        Long retiring = ledger.poolRetirementEpoch(hash).orElse(null);
        return Lookup.present(new PoolState(poolId, live.get().deposit(), present.value(), retiring, null, null));
    }

    /** The VRF key hash of the parameters active at ledger epoch {@code epoch} (see the class doc). */
    private static Lookup<String> activeVrf(LedgerStateSnapshotReader ledger, String poolHash,
                                            PoolRegistrationData live, int epoch) {
        if (epoch < 0) {
            return Lookup.unavailable("ledger epoch is unknown; cannot tell active from future pool parameters");
        }
        Optional<PoolRegistrationData> active = ledger.poolParamsHistoryAtOrBefore(poolHash, epoch + 2);
        if (active.isEmpty()) {
            if (ledger.hasPoolParamsHistoryAfter(poolHash, epoch + 2)) {
                return Lookup.unavailable("pool " + poolHash + " has only future parameters in its history");
            }
            active = Optional.of(live);
        }
        String vrf = active.get().vrfKeyHash();
        if (vrf == null || vrf.isBlank()) {
            return Lookup.unavailable("pool " + poolHash + " has no stored VRF key hash");
        }
        return Lookup.present(vrf);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answered from a VRF index built once per snapshot (one scan of the registered pools).</p>
     */
    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        String key = HexStrings.normalize(vrfKeyHashHex, "vrf key hash", HexStrings.HASH32);
        if (closed.get()) {
            return Lookup.unavailable("pool by VRF key hash: canonical ledger view closed");
        }
        int epoch = snapshot.tip().ledgerEpoch();
        Lookup<Map<String, PoolId>> index = snapshot.memoized("pool-vrf-index", "pool VRF index",
                state -> vrfIndex(state, epoch, Set.of()));
        return index.map(byVrf -> byVrf.get(key));
    }

    /**
     * Builds the VRF index at ledger epoch {@code epoch} over every registered pool except
     * {@code excluded} (pool ids, lowercase hex). Shared with {@link TickedLedgerView}.
     */
    static Lookup<Map<String, PoolId>> vrfIndex(CanonicalSnapshotSource.Captured state, int epoch,
                                                Set<String> excluded) {
        LedgerStateSnapshotReader ledger = state.ledger();
        if (ledger == null) {
            return Lookup.unavailable("account state is disabled");
        }
        Map<String, PoolId> byVrf = new HashMap<>();
        for (Map.Entry<String, PoolRegistrationData> pool : ledger.pools().entrySet()) {
            if (excluded.contains(pool.getKey())) {
                continue;
            }
            PoolId id = new PoolId(pool.getKey());
            Lookup<String> active = activeVrf(ledger, pool.getKey(), pool.getValue(), epoch);
            if (!(active instanceof Lookup.Present<String> present)) {
                return Lookup.unavailable(((Lookup.Unavailable<String>) active).reason());
            }
            byVrf.put(HexStrings.normalize(present.value(), "vrf key hash"), id);
            String liveVrf = pool.getValue().vrfKeyHash();
            if (liveVrf != null && !liveVrf.isBlank()) {
                byVrf.put(HexStrings.normalize(liveVrf, "vrf key hash"), id);
            }
        }
        return Lookup.present(Map.copyOf(byVrf));
    }

    // ------------------------------------------------------------------ DReps

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        Objects.requireNonNull(credential, "credential");
        return read("drep " + credential, state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (ledger == null || governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            String genesisUnmodelled = genesisStateUnmodelled(state);
            if (genesisUnmodelled != null) {
                return Lookup.unavailable(genesisUnmodelled);
            }
            int type = credential.type().tag();
            Optional<BigInteger> deposit = ledger.drepDeposit(type, credential.hashHex());
            if (deposit.isEmpty()) {
                return Lookup.absent();
            }
            // Registration is the certificate record; the governance record's `active` flag is a
            // ratification concern (Haskell GOV and DELEG check only vsDReps membership,
            // Gov.hs:472/595, Deleg.hs:224-226), so an inactive registered DRep is Present.
            Optional<DRepStateRecord> record = governance.get().drepState(type, credential.hashHex());
            if (record.isEmpty()) {
                return Lookup.unavailable("registered DRep " + credential + " has no governance record");
            }
            return Lookup.present(new DRepState(credential, deposit.get(), record.get().expiryEpoch()));
        });
    }

    // ------------------------------------------------------------------ committee

    /**
     * The governance committee records a committee read uses: the stored ones in the canonical view,
     * the post-enactment ones in {@link TickedLedgerView}.
     */
    interface CommitteeRecords {
        Optional<CommitteeMemberRecord> member(int credType, String coldHash) throws Exception;

        Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> all() throws Exception;

        /**
         * @return true when the cold credential's certificate-path hot key and resignation are dropped
         *         (the boundary's committee-state pruning, {@code CommitteeStatePruning})
         */
        default boolean certificatePathDropped(GovernanceStateStore.CredentialKey cold) {
            return false;
        }
    }

    /** The stored committee, or the Conway genesis committee overlaid by stored records before the bootstrap. */
    static CommitteeRecords committeeRecords(CanonicalSnapshotSource.Captured state,
                                             GovernanceSnapshotReader governance) throws Exception {
        Optional<ConwayGenesisGovernance> genesis = genesisFallback(state);
        return genesis.isPresent() ? genesisCommittee(governance, genesis.get()) : storedCommittee(governance);
    }

    /**
     * Haskell's Conway translation installs the genesis committee ({@code Conway/Translation.hs:169-178});
     * Yano persists it only at its first Conway boundary. Stored records (hot-key authorizations,
     * resignations made before that boundary) keep their hot key and resignation; genesis members keep their
     * genesis term.
     */
    static CommitteeRecords genesisCommittee(GovernanceSnapshotReader governance, ConwayGenesisGovernance genesis) {
        return new CommitteeRecords() {
            @Override
            public Optional<CommitteeMemberRecord> member(int credType, String coldHash) throws Exception {
                Optional<CommitteeMemberRecord> stored = governance.committeeMember(credType, coldHash);
                Integer expiry = genesis.members().get(new GovernanceStateStore.CredentialKey(credType, coldHash));
                return merge(stored.orElse(null), expiry);
            }

            @Override
            public Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> all() {
                Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> all = new LinkedHashMap<>();
                genesis.members().forEach((cold, expiry) -> all.put(cold, CommitteeMemberRecord.noHotKey(expiry)));
                governance.committeeMembers().forEach((cold, stored) ->
                        all.put(cold, merge(stored, genesis.members().get(cold)).orElseThrow()));
                return all;
            }

            private Optional<CommitteeMemberRecord> merge(CommitteeMemberRecord stored, Integer expiry) {
                if (stored == null) {
                    return expiry == null ? Optional.empty() : Optional.of(CommitteeMemberRecord.noHotKey(expiry));
                }
                return Optional.of(expiry == null ? stored
                        : new CommitteeMemberRecord(stored.hotCredType(), stored.hotHash(), expiry, stored.resigned()));
            }
        };
    }

    /**
     * ADR-056 step 1d (decision 6a): the view-level Conway genesis fallback. It applies while the ledger is in
     * Conway (protocol version 9 or later) and Yano has not yet persisted its Conway genesis bootstrap (no
     * committee threshold and no constitution stored), which on a fresh devnet lasts until the first epoch
     * boundary. Nothing is persisted; canonical ledger-state mutation is unchanged (invariant 8).
     */
    static Optional<ConwayGenesisGovernance> genesisFallback(CanonicalSnapshotSource.Captured state) throws Exception {
        ConwayGenesisGovernance genesis = state.genesisGovernance();
        Optional<GovernanceSnapshotReader> governance = governance(state);
        if (genesis == null || governance.isEmpty()) {
            return Optional.empty();
        }
        ProtocolParamsSnapshot params = state.protocolParams();
        if (params == null || params.protocolMajorVer() == null || params.protocolMajorVer() < 9) {
            return Optional.empty();
        }
        GovernanceSnapshotReader gov = governance.get();
        boolean bootstrapped = gov.committeeThreshold().isPresent() || gov.constitution().isPresent();
        return bootstrapped ? Optional.empty() : Optional.of(genesis);
    }

    /**
     * @return why account and DRep reads cannot be answered before the bootstrap: the Conway genesis registers
     *         DReps or delegations ({@code initialDReps}/{@code delegs}, {@code Conway/Transition.hs:82-92}),
     *         which Yano does not model; otherwise {@code null}
     */
    static String genesisStateUnmodelled(CanonicalSnapshotSource.Captured state) throws Exception {
        Optional<ConwayGenesisGovernance> genesis = genesisFallback(state);
        if (genesis.isPresent() && (genesis.get().hasInitialDReps() || genesis.get().hasDelegations())) {
            return "the Conway genesis registers initialDReps/delegs, which are not modelled before the genesis "
                    + "bootstrap is persisted (first epoch boundary)";
        }
        return null;
    }

    static CommitteeRecords storedCommittee(GovernanceSnapshotReader governance) {
        return new CommitteeRecords() {
            @Override
            public Optional<CommitteeMemberRecord> member(int credType, String coldHash) throws Exception {
                return governance.committeeMember(credType, coldHash);
            }

            @Override
            public Map<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> all() {
                return governance.committeeMembers();
            }
        };
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        Objects.requireNonNull(cold, "cold");
        return read("committee member " + cold, state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (ledger == null || governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return committeeMemberByCold(ledger, committeeRecords(state, governance.get()), cold);
        });
    }

    static Lookup<CommitteeMemberState> committeeMemberByCold(LedgerStateSnapshotReader ledger,
                                                              CommitteeRecords records,
                                                              CredentialKey cold) throws Exception {
        int type = cold.type().tag();
        Optional<CommitteeMemberRecord> record = records.member(type, cold.hashHex());
        if (record.isPresent()) {
            return Lookup.present(toMember(cold, record.get()));
        }
        if (records.certificatePathDropped(new GovernanceStateStore.CredentialKey(type, cold.hashHex()))) {
            return Lookup.absent();
        }
        if (ledger.committeeResigned(type, cold.hashHex())) {
            return Lookup.present(new CommitteeMemberState(cold, null, true, null));
        }
        Optional<LedgerStateSnapshotReader.CommitteeHotAuthorization> hot =
                ledger.committeeHotKey(type, cold.hashHex());
        if (hot.isPresent()) {
            CredentialKey hotKey = credential(hot.get().hotCredType(), hot.get().hotHash());
            return Lookup.present(new CommitteeMemberState(cold, hotKey, false, null));
        }
        return Lookup.absent();
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        Objects.requireNonNull(hot, "hot");
        return read("committee members by hot " + hot, state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return committeeMembersByHot(committeeRecords(state, governance.get()), hot);
        });
    }

    static Lookup<List<CommitteeMemberState>> committeeMembersByHot(CommitteeRecords records, CredentialKey hot)
            throws Exception {
        List<CommitteeMemberState> members = new ArrayList<>();
        for (Map.Entry<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> entry : records.all().entrySet()) {
            CommitteeMemberState member = toMember(
                    credential(entry.getKey().credType(), entry.getKey().hash()), entry.getValue());
            if (!member.resigned() && hot.equals(member.hot())) {
                members.add(member);
            }
        }
        return Lookup.present(List.copyOf(members));
    }

    private static CommitteeMemberState toMember(CredentialKey cold, CommitteeMemberRecord record) {
        Long expiry = record.expiryEpoch() > 0 ? (long) record.expiryEpoch() : null;
        if (record.resigned()) {
            return new CommitteeMemberState(cold, null, true, expiry);
        }
        CredentialKey hot = record.hasHotKey() ? credential(record.hotCredType(), record.hotHash()) : null;
        return new CommitteeMemberState(cold, hot, false, expiry);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every governance committee record, plus cold credentials that only have a
     * certificate-path hot key or resignation (the same universe as {@link #committeeMemberByCold}).</p>
     */
    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return read("committee members", state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (ledger == null || governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return committeeMembers(ledger, committeeRecords(state, governance.get()));
        });
    }

    static Lookup<List<CommitteeMemberState>> committeeMembers(LedgerStateSnapshotReader ledger,
                                                               CommitteeRecords records) throws Exception {
        Map<String, CommitteeMemberState> byCold = new TreeMap<>();
        for (Map.Entry<GovernanceStateStore.CredentialKey, CommitteeMemberRecord> entry : records.all().entrySet()) {
            CredentialKey cold = credential(entry.getKey().credType(), entry.getKey().hash());
            byCold.put(sortKey(cold), toMember(cold, entry.getValue()));
        }
        for (GovernanceStateStore.CredentialKey resigned : ledger.committeeResignations()) {
            if (records.certificatePathDropped(resigned)) {
                continue;
            }
            CredentialKey cold = credential(resigned.credType(), resigned.hash());
            byCold.putIfAbsent(sortKey(cold), new CommitteeMemberState(cold, null, true, null));
        }
        for (Map.Entry<GovernanceStateStore.CredentialKey, LedgerStateSnapshotReader.CommitteeHotAuthorization>
                entry : ledger.committeeHotKeys().entrySet()) {
            if (records.certificatePathDropped(entry.getKey())) {
                continue;
            }
            CredentialKey cold = credential(entry.getKey().credType(), entry.getKey().hash());
            CredentialKey hot = credential(entry.getValue().hotCredType(), entry.getValue().hotHash());
            byCold.putIfAbsent(sortKey(cold), new CommitteeMemberState(cold, hot, false, null));
        }
        return Lookup.present(List.copyOf(byCold.values()));
    }

    /** Credential type, then hash: the order of {@link #committeeMembers()} in every view. */
    private static String sortKey(CredentialKey credential) {
        return credential.type().ordinal() + ":" + credential.hashHex();
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return read("committee candidates", state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return committeeCandidates(governance.get().proposals());
        });
    }

    /** Cold credentials added by the UpdateCommittee proposals among {@code proposals}. */
    static Lookup<Set<CredentialKey>> committeeCandidates(List<StoredProposal> proposals) throws Exception {
        Set<CredentialKey> candidates = new HashSet<>();
        for (StoredProposal proposal : proposals) {
            if (proposal.record().actionType() != GovActionType.UPDATE_COMMITTEE) {
                continue;
            }
            GovAction action = decodeAction(proposal);
            if (!(action instanceof UpdateCommittee update)) {
                return Lookup.unavailable("UpdateCommittee proposal " + proposal.txHash() + "#"
                        + proposal.index() + " has no stored payload");
            }
            if (update.getNewMembersAndTerms() != null) {
                update.getNewMembersAndTerms().keySet().forEach(c -> candidates.add(CredentialKey.of(c)));
            }
        }
        return Lookup.present(Set.copyOf(candidates));
    }

    // ------------------------------------------------------------------ governance

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        Objects.requireNonNull(id, "id");
        return read("proposal " + id, state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            Optional<StoredProposal> stored = governance.get().proposal(id.txHashHex(), id.index());
            if (stored.isEmpty()) {
                return Lookup.absent();
            }
            return Lookup.present(toProposalState(stored.get()));
        });
    }

    /** {@inheritDoc} Ordered by proposal slot, then transaction id, then index (see the class doc). */
    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return read("active proposals", state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return activeProposals(governance.get().proposals());
        });
    }

    /** Orders {@code stored} as {@link #activeProposals()} does and maps them to the view model. */
    static Lookup<List<ProposalState>> activeProposals(List<StoredProposal> stored) throws Exception {
        List<StoredProposal> sorted = new ArrayList<>(stored);
        sorted.sort(Comparator.comparingLong((StoredProposal p) -> p.record().proposalSlot())
                .thenComparing(StoredProposal::txHash)
                .thenComparingInt(StoredProposal::index));
        List<ProposalState> proposals = new ArrayList<>(sorted.size());
        for (StoredProposal proposal : sorted) {
            proposals.add(toProposalState(proposal));
        }
        return Lookup.present(List.copyOf(proposals));
    }

    static ProposalState toProposalState(StoredProposal stored) throws Exception {
        GovActionRecord record = stored.record();
        GovActionId prev = record.prevActionTxHash() != null
                ? new GovActionId(record.prevActionTxHash(), record.prevActionIndex())
                : null;
        // Fully qualified: the CCL action type has the same simple name as yaci's, imported here.
        var type = com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType
                .valueOf(record.actionType().name());
        return new ProposalState(new GovActionId(stored.txHash(), stored.index()), type, decodeAction(stored), prev,
                record.proposedInEpoch(), record.expiresAfterEpoch(), record.deposit(), record.returnAddress(),
                paramUpdateKeys(stored));
    }

    /**
     * The {@code protocol_param_update} keys of a stored ParameterChange (ADR-057 Phase B deviation 4), read
     * straight from the stored action CBOR with {@link ProposalParamUpdateKeys}, not from a decoded CCL
     * action (CCL has no fields for the Conway keys 25-33). The stored bytes are yaci's re-serialisation of
     * its parsed action; yaci's {@code ProtocolParamUpdate} carries every Conway key and its serializer
     * writes each set field under its CDDL key, so the key set survives the round trip
     * ({@code CanonicalParamUpdateKeysTest}). A stored proposal without a payload keeps {@code null}, so
     * engines that need the keys fail closed.
     *
     * @return the keys for a ParameterChange with a stored payload, otherwise {@code null}
     */
    static Set<Integer> paramUpdateKeys(StoredProposal stored) {
        if (stored.record().actionType() != GovActionType.PARAMETER_CHANGE_ACTION) {
            return null;
        }
        byte[] cbor = stored.govActionCbor();
        return cbor == null ? null : ProposalParamUpdateKeys.fromGovAction(cbor);
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return read("enacted roots", state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            GovernanceSnapshotReader gov = governance.get();
            return Lookup.present(new EnactedRoots(
                    root(gov, GovActionType.PARAMETER_CHANGE_ACTION),
                    root(gov, GovActionType.HARD_FORK_INITIATION_ACTION),
                    root(gov, GovActionType.UPDATE_COMMITTEE),
                    root(gov, GovActionType.NEW_CONSTITUTION)));
        });
    }

    static GovActionId root(GovernanceSnapshotReader gov, GovActionType purposeType) throws Exception {
        Optional<LastEnactedAction> last = gov.lastEnacted(purposeType);
        return last.map(l -> new GovActionId(l.txHash(), l.govActionIndex())).orElse(null);
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return read("guardrail script hash", state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            Optional<ConstitutionRecord> stored = governance.get().constitution();
            if (stored.isEmpty()) {
                Optional<ConwayGenesisGovernance> genesis = genesisFallback(state);
                if (genesis.isPresent()) {
                    // A genesis without a constitution means Haskell's default constitution: no guardrail.
                    ConstitutionRecord fromGenesis = genesis.get().constitution();
                    return fromGenesis == null ? Lookup.absent() : guardrail(Optional.of(fromGenesis));
                }
            }
            return guardrail(stored);
        });
    }

    static Lookup<String> guardrail(Optional<ConstitutionRecord> constitution) {
        if (constitution.isEmpty()) {
            return Lookup.unavailable("no constitution is stored (governance not bootstrapped)");
        }
        String hash = constitution.get().scriptHash();
        return hash == null || hash.isBlank() ? Lookup.absent() : Lookup.present(hash.toLowerCase(Locale.ROOT));
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return read("dormant epochs", state -> {
            Optional<GovernanceSnapshotReader> governance = governance(state);
            if (governance.isEmpty()) {
                return Lookup.unavailable("governance tracking is disabled");
            }
            return Lookup.present((long) governance.get().numDormantEpochs());
        });
    }

    // ------------------------------------------------------------------ pots and parameters

    @Override
    public Lookup<BigInteger> treasury() {
        return read("treasury", state -> {
            LedgerStateSnapshotReader ledger = state.ledger();
            if (ledger == null) {
                return Lookup.unavailable("account state is disabled");
            }
            int epoch = snapshot.tip().ledgerEpoch();
            if (epoch < 0) {
                return Lookup.unavailable("ledger epoch is unknown");
            }
            Optional<BigInteger> treasury = ledger.treasury(epoch);
            if (treasury.isEmpty()) {
                // Before the first boundary no AdaPot exists; Yano's first AdaPot stores this treasury
                // (EpochBoundaryProcessor.bootstrapAdaPotIfNeeded; Haskell createInitialState starts at 0).
                Optional<ConwayGenesisGovernance> genesis = genesisFallback(state);
                if (genesis.isPresent()) {
                    return Lookup.present(genesis.get().initialTreasury());
                }
            }
            return treasury.<Lookup<BigInteger>>map(Lookup::present)
                    .orElseGet(() -> Lookup.unavailable("no AdaPot is stored for epoch " + epoch));
        });
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return read("protocol parameters", state -> {
            ProtocolParamsSnapshot params = state.protocolParams();
            if (params == null) {
                return Lookup.unavailable("no effective protocol parameters for epoch " + snapshot.tip().ledgerEpoch());
            }
            // A fresh CCL object per read: ProtocolParams is a mutable bean.
            return Lookup.present(ProtocolParamsMapper.fromSnapshot(params));
        });
    }

    // ------------------------------------------------------------------ helpers

    static Optional<GovernanceSnapshotReader> governance(CanonicalSnapshotSource.Captured state) {
        LedgerStateSnapshotReader ledger = state.ledger();
        return ledger != null ? ledger.governance() : Optional.empty();
    }

    static CredentialKey credential(int credType, String hashHex) {
        return new CredentialKey(CredentialType.fromTag(credType), hashHex);
    }

    private static GovAction decodeAction(StoredProposal proposal) throws Exception {
        byte[] cbor = proposal.govActionCbor();
        if (cbor == null) {
            return null;
        }
        List<DataItem> items = new CborDecoder(new ByteArrayInputStream(cbor)).decode();
        if (items.size() != 1 || !(items.getFirst() instanceof Array array)) {
            throw new IllegalStateException("Stored governance action of " + proposal.txHash() + "#"
                    + proposal.index() + " is not a CBOR array");
        }
        return GovAction.deserialize(array);
    }
}
