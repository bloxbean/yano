package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A view that answers some keys differently from its base (to make them Absent or Unavailable). */
final class OverridingView implements LedgerView {

    private final LedgerView base;
    private final Map<Object, Lookup<?>> overrides = new HashMap<>();
    private List<ProposalState> proposals;

    OverridingView(LedgerView base) {
        this.base = base;
    }

    OverridingView override(Object key, Lookup<?> answer) {
        overrides.put(key instanceof Outpoint o ? Outpoints.normalize(o) : key, answer);
        return this;
    }

    /** Replaces the proposal set (both {@link #activeProposals()} and {@link #proposal(GovActionId)}). */
    OverridingView proposals(List<ProposalState> replacement) {
        this.proposals = List.copyOf(replacement);
        return this;
    }

    @SuppressWarnings("unchecked")
    private <T> Lookup<T> answer(Object key, Lookup<T> fallback) {
        Lookup<?> override = overrides.get(key);
        return override != null ? (Lookup<T>) override : fallback;
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        return answer(Outpoints.normalize(outpoint), base.utxo(outpoint));
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return answer(credential, base.account(credential));
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return answer(poolId, base.pool(poolId));
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        return base.poolByVrfKeyHash(vrfKeyHashHex);
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return answer(credential, base.drep(credential));
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        return base.committeeMemberByCold(cold);
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        return base.committeeMembersByHot(hot);
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return base.committeeCandidates();
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        if (proposals != null) {
            return Lookup.ofNullable(proposals.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null));
        }
        return base.proposal(id);
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return base.committeeMembers();
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return proposals != null ? Lookup.present(proposals) : base.activeProposals();
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
}
