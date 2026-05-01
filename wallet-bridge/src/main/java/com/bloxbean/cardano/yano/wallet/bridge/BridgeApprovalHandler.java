package com.bloxbean.cardano.yano.wallet.bridge;

@FunctionalInterface
public interface BridgeApprovalHandler {
    boolean approve(BridgeApprovalRequest request);

    static BridgeApprovalHandler denyAll() {
        return request -> false;
    }
}
