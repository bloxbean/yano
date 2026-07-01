package com.bloxbean.cardano.yano.p2p.tx.diffusion;

import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;

import java.util.List;

public interface TxDiffusion extends AutoCloseable {
    boolean isEnabled();

    TxDiffusionMode mode();

    void onPeerConnected(String peerId, PeerClass peerClass);

    void onPeerDisconnected(String peerId);

    void onTransactionAccepted(MemPoolTransaction transaction);

    boolean reserveLocalSubmitForward(String peerId, PeerClass peerClass, String txHash, int size);

    void onLocalSubmitForwarded(String peerId, PeerClass peerClass, String txHash, int size);

    TxRequestPlan onPeerTxIds(String peerId, PeerClass peerClass, List<TxIdAndSize> announced);

    boolean shouldRequestTransaction(String peerId, PeerClass peerClass, String txHash);

    TxBodyIngressResult onPeerTxBodies(String peerId,
                                       PeerClass peerClass,
                                       List<byte[]> txBodies,
                                       TxAdmissionPort txAdmission);

    TxBodyServeResult onPeerRequestedTxs(String peerId, PeerClass peerClass, List<String> txHashes);

    TxDiffusionStats stats();

    @Override
    default void close() {
    }

    static TxDiffusion disabled() {
        return DisabledTxDiffusion.INSTANCE;
    }
}
