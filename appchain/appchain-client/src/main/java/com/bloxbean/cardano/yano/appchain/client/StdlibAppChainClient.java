package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Typed Java facade for the four stock standard-library state machines. */
public final class StdlibAppChainClient {
    private final AppChainClient client;

    public StdlibAppChainClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AppChainClient client() {
        return client;
    }

    public AppChainClient.SubmitResult kvPut(byte[] key, byte[] value) {
        return client.submit(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.put(key, value));
    }

    public AppChainClient.SubmitResult kvDelete(byte[] key) {
        return client.submit(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.delete(key));
    }

    public Optional<VerifiedState<KvRegistryContract.Entry>> kvEntry(byte[] key) {
        return verified(key, KvRegistryContract::decodeEntry);
    }

    public AppChainClient.SubmitResult propose(
            String itemId, byte[] payload, int required, long deadlineMillis) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.propose(itemId, payload, required, deadlineMillis));
    }

    public AppChainClient.SubmitResult approve(String itemId) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.approve(itemId));
    }

    public AppChainClient.SubmitResult reject(String itemId) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.reject(itemId));
    }

    public Optional<VerifiedState<ApprovalsContract.Item>> approval(String itemId) {
        return verified(ApprovalsContract.itemKey(itemId), ApprovalsContract::decodeItem);
    }

    public Optional<VerifiedState<ApprovalsContract.EffectState>> approvalEffect(String itemId) {
        return verified(ApprovalsContract.effectStateKey(itemId),
                ApprovalsContract::decodeEffectState);
    }

    public AppChainClient.SubmitResult mint(String account, BigInteger amount) {
        return client.submit(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.mint(account, amount));
    }

    public AppChainClient.SubmitResult transfer(String account, BigInteger amount) {
        return client.submit(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.transfer(account, amount));
    }

    public Optional<VerifiedState<BigInteger>> balance(String account) {
        return verified(BalancesContract.accountKey(account), BalancesContract::decodeBalance);
    }

    public AppChainClient.SubmitResult appendDocument(
            String entityId, byte[] entryHash, String reference) {
        return client.submit(DocTrailContract.DEFAULT_TOPIC,
                DocTrailContract.append(entityId, entryHash, reference));
    }

    public Optional<VerifiedState<DocTrailContract.Head>> documentTrail(String entityId) {
        return verified(DocTrailContract.entityKey(entityId), DocTrailContract::decodeHead);
    }

    private <T> Optional<VerifiedState<T>> verified(byte[] key, Function<byte[], T> decoder) {
        Optional<AppChainClient.Proof> result = client.proof(key);
        if (result.isEmpty()) return Optional.empty();
        AppChainClient.Proof proof = result.orElseThrow();
        if (!ProofVerifier.verify(proof)) {
            throw new IllegalStateException(
                    "MPF proof verification failed for a stock state-machine value");
        }
        if (proof.valueHex() == null) return Optional.empty();
        return Optional.of(new VerifiedState<>(decoder.apply(Hex.decode(proof.valueHex())), proof));
    }

    /** A decoded state value bound to the locally verified proof envelope. */
    public record VerifiedState<T>(T value, AppChainClient.Proof proof) {
        public VerifiedState {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(proof, "proof");
        }
    }
}
