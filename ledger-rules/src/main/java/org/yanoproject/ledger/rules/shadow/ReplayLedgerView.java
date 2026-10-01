package org.yanoproject.ledger.rules.shadow;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;
import org.yanoproject.ledger.rules.view.RecordingLedgerView.Read;
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

/**
 * Answers exactly the reads recorded in a {@link ShadowDumpBundle}; any other read is
 * {@link Lookup.Unavailable} ("not recorded"), so an engine replayed against it fails closed rather than
 * seeing invented state.
 */
public final class ReplayLedgerView implements LedgerView {

    private final Map<String, Lookup<?>> reads = new HashMap<>();

    ReplayLedgerView(List<Read> recorded) {
        for (Read read : recorded) {
            reads.putIfAbsent(read.method() + "\u0000" + read.key(), read.result());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Lookup<T> answer(String method, String key) {
        Lookup<?> result = reads.get(method + "\u0000" + key);
        return result != null ? (Lookup<T>) result
                : Lookup.unavailable("not recorded in the shadow dump: " + method + "(" + key + ")");
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint normalized = Outpoints.normalize(outpoint);
        return answer(RecordingLedgerView.UTXO, normalized.txHash() + "#" + normalized.index());
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return answer(RecordingLedgerView.ACCOUNT, credential.toString());
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return answer(RecordingLedgerView.POOL, poolId.hashHex());
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        return answer(RecordingLedgerView.POOL_BY_VRF, vrfKeyHashHex);
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return answer(RecordingLedgerView.DREP, credential.toString());
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        return answer(RecordingLedgerView.COMMITTEE_BY_COLD, cold.toString());
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        return answer(RecordingLedgerView.COMMITTEE_BY_HOT, hot.toString());
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return answer(RecordingLedgerView.COMMITTEE_CANDIDATES, "");
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        return answer(RecordingLedgerView.PROPOSAL, id.toString());
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return answer(RecordingLedgerView.COMMITTEE_MEMBERS, "");
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return answer(RecordingLedgerView.ACTIVE_PROPOSALS, "");
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return answer(RecordingLedgerView.ENACTED_ROOTS, "");
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return answer(RecordingLedgerView.GUARDRAIL, "");
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return answer(RecordingLedgerView.DORMANT_EPOCHS, "");
    }

    @Override
    public Lookup<BigInteger> treasury() {
        return answer(RecordingLedgerView.TREASURY, "");
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return answer(RecordingLedgerView.PROTOCOL_PARAMS, "");
    }
}
