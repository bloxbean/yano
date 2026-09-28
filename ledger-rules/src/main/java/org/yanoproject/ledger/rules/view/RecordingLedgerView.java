package org.yanoproject.ledger.rules.view;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.yanoproject.api.utxo.model.Outpoint;
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
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@link LedgerView} decorator that records every read and its outcome, so a validation can be replayed
 * against exactly the state it saw (ADR-056 §7, shadow-dump bundles). Reads are delegated unchanged.
 *
 * <p>Thread-safe; recording is lock-free. The recorded {@link Lookup}s hold the delegate's values, which are
 * immutable except for CCL beans inside them ({@code TransactionOutput}, {@code ProtocolParams}, …) that
 * callers must treat as read-only.</p>
 */
public final class RecordingLedgerView implements LedgerView {

    /** Method names, as recorded in {@link Read#method()}. */
    public static final String UTXO = "utxo";
    public static final String ACCOUNT = "account";
    public static final String POOL = "pool";
    public static final String POOL_BY_VRF = "poolByVrfKeyHash";
    public static final String DREP = "drep";
    public static final String COMMITTEE_BY_COLD = "committeeMemberByCold";
    public static final String COMMITTEE_BY_HOT = "committeeMembersByHot";
    public static final String COMMITTEE_CANDIDATES = "committeeCandidates";
    public static final String PROPOSAL = "proposal";
    public static final String COMMITTEE_MEMBERS = "committeeMembers";
    public static final String ACTIVE_PROPOSALS = "activeProposals";
    public static final String ENACTED_ROOTS = "enactedRoots";
    public static final String GUARDRAIL = "guardrailScriptHash";
    public static final String DORMANT_EPOCHS = "dormantEpochs";
    public static final String TREASURY = "treasury";
    public static final String PROTOCOL_PARAMS = "protocolParams";

    /**
     * One recorded read.
     *
     * @param method the {@link LedgerView} method (one of the constants above)
     * @param key    the argument rendered as text ({@code txhash#index}, {@code key:hash}, a pool or VRF hash,
     *               a {@code txhash#index} action id), empty for methods without an argument
     * @param result the outcome returned to the caller
     */
    public record Read(String method, String key, Lookup<?> result) {
        public Read {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(result, "result");
        }
    }

    private final LedgerView delegate;
    private final Queue<Read> reads = new ConcurrentLinkedQueue<>();

    public RecordingLedgerView(LedgerView delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** @return the wrapped view */
    public LedgerView delegate() {
        return delegate;
    }

    /** @return the reads so far, in the order they completed */
    public List<Read> reads() {
        return List.copyOf(reads);
    }

    private <T> Lookup<T> record(String method, String key, Lookup<T> result) {
        reads.add(new Read(method, key, result));
        return result;
    }

    @Override
    public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
        Outpoint normalized = Outpoints.normalize(outpoint);
        return record(UTXO, normalized.txHash() + "#" + normalized.index(), delegate.utxo(outpoint));
    }

    @Override
    public Lookup<AccountState> account(CredentialKey credential) {
        return record(ACCOUNT, credential.toString(), delegate.account(credential));
    }

    @Override
    public Lookup<PoolState> pool(PoolId poolId) {
        return record(POOL, poolId.hashHex(), delegate.pool(poolId));
    }

    @Override
    public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
        return record(POOL_BY_VRF, vrfKeyHashHex, delegate.poolByVrfKeyHash(vrfKeyHashHex));
    }

    @Override
    public Lookup<DRepState> drep(CredentialKey credential) {
        return record(DREP, credential.toString(), delegate.drep(credential));
    }

    @Override
    public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
        return record(COMMITTEE_BY_COLD, cold.toString(), delegate.committeeMemberByCold(cold));
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
        return record(COMMITTEE_BY_HOT, hot.toString(), delegate.committeeMembersByHot(hot));
    }

    @Override
    public Lookup<Set<CredentialKey>> committeeCandidates() {
        return record(COMMITTEE_CANDIDATES, "", delegate.committeeCandidates());
    }

    @Override
    public Lookup<ProposalState> proposal(GovActionId id) {
        return record(PROPOSAL, id.toString(), delegate.proposal(id));
    }

    @Override
    public Lookup<List<CommitteeMemberState>> committeeMembers() {
        return record(COMMITTEE_MEMBERS, "", delegate.committeeMembers());
    }

    @Override
    public Lookup<List<ProposalState>> activeProposals() {
        return record(ACTIVE_PROPOSALS, "", delegate.activeProposals());
    }

    @Override
    public Lookup<EnactedRoots> enactedRoots() {
        return record(ENACTED_ROOTS, "", delegate.enactedRoots());
    }

    @Override
    public Lookup<String> guardrailScriptHash() {
        return record(GUARDRAIL, "", delegate.guardrailScriptHash());
    }

    @Override
    public Lookup<Long> dormantEpochs() {
        return record(DORMANT_EPOCHS, "", delegate.dormantEpochs());
    }

    @Override
    public Lookup<BigInteger> treasury() {
        return record(TREASURY, "", delegate.treasury());
    }

    @Override
    public Lookup<ProtocolParams> protocolParams() {
        return record(PROTOCOL_PARAMS, "", delegate.protocolParams());
    }
}
