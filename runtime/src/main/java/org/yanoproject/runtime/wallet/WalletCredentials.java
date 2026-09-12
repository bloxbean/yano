package org.yanoproject.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.util.AddressEncoderDecoderUtil;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import org.yanoproject.api.wallet.WalletCredential;
import org.yanoproject.api.utxo.index.UtxoChanges.Subject;
import org.yanoproject.api.utxo.index.UtxoChanges.CredentialSubject;
import org.yanoproject.api.utxo.index.UtxoChanges.RewardAccountSubject;
import org.yanoproject.runtime.utxo.index.TransactionSubjects;

import java.util.Collection;
import java.util.HexFormat;

/** Shared effective-event extraction for filter construction and exact scan confirmation. */
public final class WalletCredentials {
    private WalletCredentials() { }

    public static void address(String address, Collection<WalletCredential> sink) {
        byte[] raw = WalletIndexStore.addressBytes(address);
        // Byron has no Shelley credentials.
        if (AddressEncoderDecoderUtil.readAddressType(raw) == AddressType.Byron) return;
        address(new Address(raw), sink);
    }

    private static void address(Address address, Collection<WalletCredential> sink) {
        address.getPaymentCredentialHash().ifPresent(hash ->
                add(sink, "payment", address.isScriptHashInPaymentPart(), hash));
        // A pointer is not a stake credential; CCL's delegation-hash API also exposes pointers.
        if (address.getAddressType() == AddressType.Ptr) return;
        address.getDelegationCredentialHash().ifPresent(hash ->
                add(sink, "stake", address.isScriptHashInDelegationPart(), hash));
    }

    public static void events(TransactionBody tx, Collection<WalletCredential> sink) {
        var result = TransactionSubjects.extract(tx);
        subjects(result.subjects(), sink);
        if (result.error() != null) throw new IllegalArgumentException(result.error());
    }

    public static void subjects(Collection<Subject> subjects, Collection<WalletCredential> sink) {
        for (Subject subject : subjects) {
            switch (subject) {
                case RewardAccountSubject reward -> rewardAccount(reward.address(), sink);
                case CredentialSubject credential -> sink.add(new WalletCredential(credential.role(), credential.type(), credential.hash()));
            }
        }
    }

    /** Yaci decodes reward-account fields as raw hex; APIs may supply Bech32. */
    private static void rewardAccount(String account, Collection<WalletCredential> sink) {
        byte[] raw = account != null && account.length() == 58
                ? HexFormat.of().parseHex(account) : WalletIndexStore.addressBytes(account);
        Address reward = new Address(raw);
        if (reward.getAddressType() != AddressType.Reward) {
            throw new IllegalArgumentException("Invalid reward account");
        }
        address(reward, sink);
    }

    private static void add(Collection<WalletCredential> sink, String role, boolean script, byte[] hash) {
        sink.add(new WalletCredential(role, script ? "script" : "key", HexFormat.of().formatHex(hash)));
    }
}
