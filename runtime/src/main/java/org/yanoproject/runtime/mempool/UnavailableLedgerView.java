package org.yanoproject.runtime.mempool;

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
import java.util.Set;

/** A view whose every read is unavailable (invariant 2: fail closed, never "absent"). */
final class UnavailableLedgerView implements LedgerView {

    private final String reason;

    UnavailableLedgerView(String reason) {
        this.reason = reason;
    }

    private <T> Lookup<T> no() {
        return Lookup.unavailable(reason);
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        return no();
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return no();
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return no();
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        return no();
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return no();
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        return no();
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        return no();
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return no();
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return no();
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return no();
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        return no();
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return no();
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return no();
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return no();
    }

    @Override
    public Lookup<BigInteger> treasury() {
        return no();
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return no();
    }
}
