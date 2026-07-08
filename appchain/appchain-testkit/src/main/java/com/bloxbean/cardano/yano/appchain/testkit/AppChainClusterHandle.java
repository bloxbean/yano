package com.bloxbean.cardano.yano.appchain.testkit;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Handle to the embedded cluster booted by {@link AppChainCluster}.
 */
public final class AppChainClusterHandle {

    private final List<AppChainGateway> nodes;
    private final List<String> memberPublicKeysHex;

    AppChainClusterHandle(List<AppChainGateway> nodes, List<String> memberPublicKeysHex) {
        this.nodes = List.copyOf(nodes);
        this.memberPublicKeysHex = List.copyOf(memberPublicKeysHex);
    }

    public int size() {
        return nodes.size();
    }

    public AppChainGateway node(int index) {
        return nodes.get(index);
    }

    /** Node 0 — the sequencer. */
    public AppChainGateway proposer() {
        return nodes.get(0);
    }

    public List<AppChainGateway> nodes() {
        return nodes;
    }

    /** Ed25519 public key (hex) of each member, by node index. */
    public String memberPublicKeyHex(int index) {
        return memberPublicKeysHex.get(index);
    }

    /** Wait until the message id is finalized on EVERY node. */
    public void awaitFinalized(String messageIdHex) throws InterruptedException {
        awaitFinalized(messageIdHex, 30_000);
    }

    public void awaitFinalized(String messageIdHex, long timeoutMillis) throws InterruptedException {
        byte[] messageId = HexUtil.decodeHexString(messageIdHex);
        await("message " + messageIdHex + " finalized on all nodes", timeoutMillis,
                () -> nodes.stream().allMatch(n -> n.messageHeight(messageId).isPresent()));
    }

    /** Wait until every node's tip reaches at least the given height. */
    public void awaitTip(long height) throws InterruptedException {
        await("all tips >= " + height, 30_000,
                () -> nodes.stream().allMatch(n -> n.tipHeight() >= height));
    }

    /** Generic condition helper with the cluster's polling cadence. */
    public void await(String description, long timeoutMillis, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
}
