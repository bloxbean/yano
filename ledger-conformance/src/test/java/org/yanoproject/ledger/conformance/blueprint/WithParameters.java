package org.yanoproject.ledger.conformance.blueprint;

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
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A view that answers every read from {@code base} except the protocol parameters. */
public record WithParameters(LedgerView base, ProtocolParams params) implements LedgerView {

    public WithParameters {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(params, "params");
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return Lookup.present(params);
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        return base.utxo(outpoint);
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return base.account(credential);
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return base.pool(poolId);
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        return base.poolByVrfKeyHash(vrfKeyHashHex);
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return base.drep(credential);
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
        return base.proposal(id);
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return base.committeeMembers();
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return base.activeProposals();
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
}
