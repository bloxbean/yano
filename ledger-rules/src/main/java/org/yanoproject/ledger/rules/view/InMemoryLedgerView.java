package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.UpdateCommittee;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.HexStrings;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An immutable, map-backed {@link LedgerView} for tests and fixtures.
 *
 * <p>Unknown keys are {@link Lookup.Absent}. Tests can make a whole {@link Area} or a single key
 * {@link Lookup.Unavailable} to exercise the fail-closed path.</p>
 *
 * <p>The VRF index is derived from the pools (active and future parameters), and committee
 * candidates are the explicitly added ones plus the new members of every {@code UpdateCommittee}
 * proposal whose action payload is known.</p>
 */
public final class InMemoryLedgerView implements LedgerView {

    /** Groups of reads that can be made unavailable together. */
    public enum Area {
        UTXO, ACCOUNTS, POOLS, DREPS, COMMITTEE, PROPOSALS, GOVERNANCE, POTS, PARAMS
    }

    private final Map<Outpoint, UtxoEntry> utxos;
    private final Map<CredentialKey, AccountState> accounts;
    private final Map<PoolId, PoolState> pools;
    private final Map<String, PoolId> poolsByVrf;
    private final Map<CredentialKey, DRepState> dreps;
    private final Map<CredentialKey, CommitteeMemberState> committee;
    private final Map<GovActionId, ProposalState> proposals;
    private final Set<CredentialKey> candidates;
    private final EnactedRoots enactedRoots;
    private final String guardrailScriptHash;
    private final long dormantEpochs;
    private final BigInteger treasury;
    private final ProtocolParams protocolParams;
    private final Set<Area> unavailableAreas;
    private final Set<Object> unavailableKeys;

    private InMemoryLedgerView(Builder b) {
        this.utxos = Map.copyOf(b.utxos);
        this.accounts = Map.copyOf(b.accounts);
        this.pools = Map.copyOf(b.pools);
        this.dreps = Map.copyOf(b.dreps);
        this.committee = Map.copyOf(b.committee);
        this.proposals = Map.copyOf(b.proposals);
        this.enactedRoots = b.enactedRoots;
        this.guardrailScriptHash = b.guardrailScriptHash;
        this.dormantEpochs = b.dormantEpochs;
        this.treasury = b.treasury;
        this.protocolParams = b.protocolParams;
        this.unavailableAreas = b.unavailableAreas.isEmpty() ? Set.of() : EnumSet.copyOf(b.unavailableAreas);
        this.unavailableKeys = Set.copyOf(b.unavailableKeys);

        Map<String, PoolId> vrf = new HashMap<>();
        for (PoolState pool : pools.values()) {
            vrf.put(pool.vrfKeyHashHex(), pool.id());
            if (pool.futureParams() != null && pool.futureParams().getVrfKeyHash() != null) {
                vrf.put(HexUtil.encodeHexString(pool.futureParams().getVrfKeyHash()), pool.id());
            }
        }
        this.poolsByVrf = Map.copyOf(vrf);

        Set<CredentialKey> cands = new HashSet<>(b.candidates);
        for (ProposalState proposal : proposals.values()) {
            cands.addAll(updateCommitteeCandidates(proposal));
        }
        this.candidates = Set.copyOf(cands);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the cold credentials an {@code UpdateCommittee} proposal would add; empty for other
     *         actions or when the payload is unknown
     */
    static Set<CredentialKey> updateCommitteeCandidates(ProposalState proposal) {
        if (proposal.action() instanceof UpdateCommittee update && update.getNewMembersAndTerms() != null) {
            Set<CredentialKey> result = new HashSet<>();
            update.getNewMembersAndTerms().keySet().forEach(c -> result.add(CredentialKey.of(c)));
            return result;
        }
        return Set.of();
    }

    private <T> Lookup<T> read(Area area, Object key, Supplier<T> value) {
        if (unavailableAreas.contains(area)) {
            return Lookup.unavailable("in-memory view: " + area + " marked unavailable");
        }
        if (key != null && unavailableKeys.contains(key)) {
            return Lookup.unavailable("in-memory view: " + key + " marked unavailable");
        }
        return Lookup.ofNullable(value.get());
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint key = Outpoints.normalize(outpoint);
        return read(Area.UTXO, key, () -> utxos.get(key));
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return read(Area.ACCOUNTS, credential, () -> accounts.get(credential));
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return read(Area.POOLS, poolId, () -> pools.get(poolId));
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        String key = HexStrings.normalize(vrfKeyHashHex, "vrf key hash", HexStrings.HASH32);
        return read(Area.POOLS, key, () -> poolsByVrf.get(key));
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return read(Area.DREPS, credential, () -> dreps.get(credential));
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        return read(Area.COMMITTEE, cold, () -> committee.get(cold));
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        return read(Area.COMMITTEE, hot, () -> committee.values().stream()
                .filter(m -> !m.resigned() && hot.equals(m.hot()))
                .toList());
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return read(Area.PROPOSALS, null, () -> candidates);
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        return read(Area.PROPOSALS, id, () -> proposals.get(id));
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return read(Area.GOVERNANCE, null, () -> enactedRoots);
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return read(Area.GOVERNANCE, null, () -> guardrailScriptHash);
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return read(Area.GOVERNANCE, null, () -> dormantEpochs);
    }

    @Override
    public Lookup<BigInteger> treasury() {
        return read(Area.POTS, null, () -> treasury);
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        if (protocolParams == null) {
            return Lookup.unavailable("in-memory view: no protocol parameters configured");
        }
        return read(Area.PARAMS, null, () -> protocolParams);
    }

    /** Mutable builder; {@link #build()} takes an immutable copy. */
    public static final class Builder {
        private final Map<Outpoint, UtxoEntry> utxos = new LinkedHashMap<>();
        private final Map<CredentialKey, AccountState> accounts = new LinkedHashMap<>();
        private final Map<PoolId, PoolState> pools = new LinkedHashMap<>();
        private final Map<CredentialKey, DRepState> dreps = new LinkedHashMap<>();
        private final Map<CredentialKey, CommitteeMemberState> committee = new LinkedHashMap<>();
        private final Map<GovActionId, ProposalState> proposals = new LinkedHashMap<>();
        private final Set<CredentialKey> candidates = new HashSet<>();
        private final Set<Area> unavailableAreas = EnumSet.noneOf(Area.class);
        private final Set<Object> unavailableKeys = new HashSet<>();
        private EnactedRoots enactedRoots = EnactedRoots.NONE;
        private String guardrailScriptHash;
        private long dormantEpochs;
        private BigInteger treasury = BigInteger.ZERO;
        private ProtocolParams protocolParams;

        private Builder() {
        }

        public Builder utxo(UtxoEntry entry) {
            utxos.put(entry.outpoint(), entry);
            return this;
        }

        public Builder utxo(String txHashHex, int index, TransactionOutput output) {
            return utxo(new UtxoEntry(Outpoints.of(txHashHex, index), output));
        }

        public Builder account(AccountState account) {
            accounts.put(account.credential(), account);
            return this;
        }

        public Builder pool(PoolState pool) {
            pools.put(pool.id(), pool);
            return this;
        }

        /** Registers a pool from its CCL registration certificate, with the given deposit. */
        public Builder pool(PoolRegistration registration, BigInteger deposit) {
            PoolId id = PoolId.of(registration.getOperator());
            return pool(new PoolState(id, deposit, HexUtil.encodeHexString(registration.getVrfKeyHash()),
                    null, registration, null));
        }

        public Builder drep(DRepState drep) {
            dreps.put(drep.credential(), drep);
            return this;
        }

        public Builder committeeMember(CommitteeMemberState member) {
            committee.put(member.cold(), member);
            return this;
        }

        public Builder proposal(ProposalState proposal) {
            proposals.put(proposal.id(), proposal);
            return this;
        }

        public Builder committeeCandidate(CredentialKey cold) {
            candidates.add(Objects.requireNonNull(cold, "cold"));
            return this;
        }

        public Builder enactedRoots(EnactedRoots roots) {
            this.enactedRoots = Objects.requireNonNull(roots, "roots");
            return this;
        }

        public Builder guardrailScriptHash(String hashHex) {
            this.guardrailScriptHash = hashHex == null
                    ? null
                    : HexStrings.normalize(hashHex, "guardrail hash", HexStrings.HASH28);
            return this;
        }

        public Builder dormantEpochs(long epochs) {
            this.dormantEpochs = epochs;
            return this;
        }

        public Builder treasury(BigInteger amount) {
            this.treasury = Objects.requireNonNull(amount, "treasury");
            return this;
        }

        public Builder protocolParams(ProtocolParams params) {
            this.protocolParams = params;
            return this;
        }

        /** Makes every read in {@code area} return {@link Lookup.Unavailable}. */
        public Builder unavailable(Area area) {
            unavailableAreas.add(Objects.requireNonNull(area, "area"));
            return this;
        }

        /**
         * Makes reads of one key return {@link Lookup.Unavailable}: an {@link Outpoint},
         * {@link CredentialKey}, {@link PoolId}, {@link GovActionId} or VRF key hash string.
         */
        public Builder unavailableKey(Object key) {
            Objects.requireNonNull(key, "key");
            Object normalized = switch (key) {
                case Outpoint o -> Outpoints.normalize(o);
                case String s -> HexStrings.normalize(s, "key");
                default -> key;
            };
            unavailableKeys.add(normalized);
            return this;
        }

        public InMemoryLedgerView build() {
            return new InMemoryLedgerView(this);
        }
    }
}
