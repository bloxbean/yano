package com.bloxbean.cardano.yano.runtime.tx.diffusion;

import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;

import java.util.List;

enum DisabledTxDiffusion implements TxDiffusion {
    INSTANCE;

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public TxDiffusionMode mode() {
        return TxDiffusionMode.DISABLED;
    }

    @Override
    public void onPeerConnected(String peerId, PeerClass peerClass) {
    }

    @Override
    public void onPeerDisconnected(String peerId) {
    }

    @Override
    public void onTransactionAccepted(MemPoolTransaction transaction) {
    }

    @Override
    public boolean reserveLocalSubmitForward(String peerId, PeerClass peerClass, String txHash, int size) {
        return true;
    }

    @Override
    public void onLocalSubmitForwarded(String peerId, PeerClass peerClass, String txHash, int size) {
    }

    @Override
    public TxRequestPlan onPeerTxIds(String peerId, PeerClass peerClass, List<TxIdAndSize> announced) {
        int rejected = announced != null ? announced.size() : 0;
        return new TxRequestPlan(List.of(), 0, rejected, 0);
    }

    @Override
    public boolean shouldRequestTransaction(String peerId, PeerClass peerClass, String txHash) {
        return false;
    }

    @Override
    public TxBodyIngressResult onPeerTxBodies(String peerId,
                                             PeerClass peerClass,
                                             List<byte[]> txBodies,
                                             TransactionAdmission transactionAdmission) {
        int ignored = txBodies != null ? txBodies.size() : 0;
        return new TxBodyIngressResult(0, 0, ignored, 0);
    }

    @Override
    public TxBodyServeResult onPeerRequestedTxs(String peerId, PeerClass peerClass, List<String> txHashes) {
        return TxBodyServeResult.empty();
    }

    @Override
    public TxDiffusionStats stats() {
        return TxDiffusionStats.disabled();
    }
}
