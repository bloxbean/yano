package org.yanoproject.runtime.utxo.index;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.UpdateDrepCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.certs.VoteRegDelegCert;
import org.yanoproject.api.utxo.index.UtxoChanges.Subject;
import org.yanoproject.api.utxo.index.UtxoChanges.CredentialSubject;
import org.yanoproject.api.utxo.index.UtxoChanges.RewardAccountSubject;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

/** Focused immutable ledger subjects; raw transaction CBOR remains available for other indexes. */
public final class TransactionSubjects {
    private TransactionSubjects() { }
    public record Result(List<Subject> subjects, String error) {
        public Result { subjects = List.copyOf(subjects); }
    }
    public static Result extract(TransactionBody tx) {
        List<Subject> subjects = new ArrayList<>();
        try { events(tx, subjects); return new Result(subjects, null); }
        catch (RuntimeException failure) { return new Result(subjects, failure.toString()); }
    }

    public static void events(TransactionBody tx, Collection<Subject> sink) {
        if (tx.getWithdrawals() != null) tx.getWithdrawals().keySet().forEach(a -> rewardAccount(a, sink));
        if (tx.getCertificates() != null) {
            for (Certificate certificate : tx.getCertificates()) {
                switch (certificate) {
                    case StakeRegistration c -> stake(c.getStakeCredential(), sink);
                    case StakeDeregistration c -> stake(c.getStakeCredential(), sink);
                    case StakeDelegation c -> stake(c.getStakeCredential(), sink);
                    case RegCert c -> stake(c.getStakeCredential(), sink);
                    case UnregCert c -> stake(c.getStakeCredential(), sink);
                    case StakeRegDelegCert c -> stake(c.getStakeCredential(), sink);
                    case StakeVoteDelegCert c -> stake(c.getStakeCredential(), sink);
                    case StakeVoteRegDelegCert c -> stake(c.getStakeCredential(), sink);
                    case VoteDelegCert c -> stake(c.getStakeCredential(), sink);
                    case VoteRegDelegCert c -> stake(c.getStakeCredential(), sink);
                    case RegDrepCert c -> credential("drep", c.getDrepCredential(), sink);
                    case UnregDrepCert c -> credential("drep", c.getDrepCredential(), sink);
                    case UpdateDrepCert c -> credential("drep", c.getDrepCredential(), sink);
                    case MoveInstataneous c -> {
                        if (c.getStakeCredentialCoinMap() != null) {
                            c.getStakeCredentialCoinMap().keySet().forEach(s -> stake(s, sink));
                        }
                    }
                    case PoolRegistration c -> {
                        var params = c.getPoolParams();
                        if (params.getRewardAccount() != null) rewardAccount(params.getRewardAccount(), sink);
                        if (params.getPoolOwners() != null) {
                            for (String owner : params.getPoolOwners()) sink.add(new CredentialSubject("stake", "key", owner));
                        }
                    }
                    default -> { /* Pool retirement, genesis delegation and committee roles are outside this contract. */ }
                }
            }
        }
        if (tx.getProposalProcedures() != null) {
            tx.getProposalProcedures().forEach(p -> rewardAccount(p.getRewardAccount(), sink));
        }
    }

    private static void rewardAccount(String account, Collection<Subject> sink) {
        sink.add(new RewardAccountSubject(account));
    }

    private static void stake(StakeCredential credential, Collection<Subject> sink) {
        credential("stake", credential, sink);
    }

    private static void credential(String role, StakeCredential credential, Collection<Subject> sink) {
        if (credential == null || credential.getType() == null) throw new IllegalArgumentException("Missing credential");
        sink.add(new CredentialSubject(role, credential.getType() == StakeCredType.ADDR_KEYHASH ? "key" : "script", credential.getHash()));
    }

    private static void credential(String role, Credential credential, Collection<Subject> sink) {
        if (credential == null || credential.getType() == null) throw new IllegalArgumentException("Missing credential");
        sink.add(new CredentialSubject(role, credential.getType() == StakeCredType.ADDR_KEYHASH ? "key" : "script", credential.getHash()));
    }

}
