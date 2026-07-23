package com.bloxbean.cardano.yano.appchain.spring;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.StdlibAppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.util.Optional;

/** Spring-friendly typed operations for Yano's stock state machines. */
public final class StdlibAppChainTemplate {
    private final StdlibAppChainClient client;

    public StdlibAppChainTemplate(StdlibAppChainClient client) {
        this.client = client;
    }

    public StdlibAppChainClient client() { return client; }

    public String put(byte[] key, byte[] value) { return client.kvPut(key, value).messageId(); }
    public String delete(byte[] key) { return client.kvDelete(key).messageId(); }
    public Optional<StdlibAppChainClient.VerifiedState<KvRegistryContract.Entry>> entry(byte[] key) {
        return client.kvEntry(key);
    }

    public String propose(String itemId, byte[] payload, int required, long deadlineMillis) {
        return client.propose(itemId, payload, required, deadlineMillis).messageId();
    }
    public String approve(String itemId) { return client.approve(itemId).messageId(); }
    public String reject(String itemId) { return client.reject(itemId).messageId(); }
    public Optional<StdlibAppChainClient.VerifiedState<ApprovalsContract.Item>> approval(
            String itemId) {
        return client.approval(itemId);
    }

    public String mint(String account, BigInteger amount) {
        return client.mint(account, amount).messageId();
    }
    public String transfer(String account, BigInteger amount) {
        return client.transfer(account, amount).messageId();
    }
    public Optional<StdlibAppChainClient.VerifiedState<BigInteger>> balance(String account) {
        return client.balance(account);
    }

    public String appendDocument(String entityId, byte[] entryHash, String reference) {
        return client.appendDocument(entityId, entryHash, reference).messageId();
    }
    public Optional<StdlibAppChainClient.VerifiedState<DocTrailContract.Head>> documentTrail(
            String entityId) {
        return client.documentTrail(entityId);
    }

    public AppChainClient rawClient() { return client.client(); }
}
