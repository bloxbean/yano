package com.bloxbean.cardano.yano.runtime.wallet;

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
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;

import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;

/** Shared effective-event extraction for filter construction and exact scan confirmation. */
public final class WalletCredentials {
    private WalletCredentials() { }

    public static void address(String address, Collection<WalletCredential> sink) {
        byte[] raw = WalletIndexStore.addressBytes(address);
        int type = (raw[0] & 255) >>> 4;
        if (type <= 7) {
            if (raw.length < 29) throw new IllegalArgumentException("Truncated payment address");
            add(sink, "payment", type == 1 || type == 3 || type == 5 || type == 7,
                    Arrays.copyOfRange(raw, 1, 29));
        }
        if (type <= 3) {
            if (raw.length != 57) throw new IllegalArgumentException("Invalid base address length");
            add(sink, "stake", type == 2 || type == 3, Arrays.copyOfRange(raw, 29, 57));
        } else if (type == 14 || type == 15) {
            if (raw.length != 29) throw new IllegalArgumentException("Invalid reward address length");
            add(sink, "stake", type == 15, Arrays.copyOfRange(raw, 1, 29));
        }
        // Byron has no Shelley credentials. Pointer addresses expose payment only.
    }

    public static void events(TransactionBody tx, Collection<WalletCredential> sink) {
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
                            for (String owner : params.getPoolOwners()) sink.add(new WalletCredential("stake", "key", owner));
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

    /** Yaci decodes reward-account fields as raw hex; APIs may supply Bech32. */
    private static void rewardAccount(String account, Collection<WalletCredential> sink) {
        byte[] raw = account != null && account.length() == 58
                ? HexFormat.of().parseHex(account) : WalletIndexStore.addressBytes(account);
        if (raw.length != 29 || (raw[0] & 0xe0) != 0xe0) {
            throw new IllegalArgumentException("Invalid reward account");
        }
        add(sink, "stake", (raw[0] & 0x10) != 0, Arrays.copyOfRange(raw, 1, 29));
    }

    private static void stake(StakeCredential credential, Collection<WalletCredential> sink) {
        credential("stake", credential, sink);
    }

    private static void credential(String role, StakeCredential credential, Collection<WalletCredential> sink) {
        if (credential == null || credential.getType() == null) throw new IllegalArgumentException("Missing credential");
        sink.add(new WalletCredential(role, credential.getType() == StakeCredType.ADDR_KEYHASH ? "key" : "script", credential.getHash()));
    }

    private static void credential(String role, Credential credential, Collection<WalletCredential> sink) {
        if (credential == null || credential.getType() == null) throw new IllegalArgumentException("Missing credential");
        sink.add(new WalletCredential(role, credential.getType() == StakeCredType.ADDR_KEYHASH ? "key" : "script", credential.getHash()));
    }

    private static void add(Collection<WalletCredential> sink, String role, boolean script, byte[] hash) {
        sink.add(new WalletCredential(role, script ? "script" : "key", HexFormat.of().formatHex(hash)));
    }
}
