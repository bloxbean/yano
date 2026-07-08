package com.bloxbean.cardano.yano.api.appchain;

import java.util.List;
import java.util.Map;

/**
 * Role interface for interacting with this node's app chain: submit opaque
 * application messages for diffusion across the app group and observe what
 * has been received. Sequencing/ledger APIs are added by the app-chain
 * milestones that follow the transport layer (M2+ of ADR app-layer/005).
 */
public interface AppChainGateway {

    /** The chain this node participates in. */
    String chainId();

    /**
     * Sign and submit an opaque message body for diffusion to the app group.
     *
     * @param topic sub-stream within the chain; null or "" = default topic
     * @param body  opaque application payload
     * @return the content-derived message id (hex)
     */
    String submit(String topic, byte[] body);

    /** Most recently accepted messages (local + peer), newest last. */
    List<ReceivedAppMessage> recentMessages(int limit);

    /** Counters for status/diagnostics (received, relayed, rejected, peers-connected...). */
    Map<String, Object> status();

    // ------------------------------------------------------------------
    // Sequenced ledger (available when sequencing is enabled — M2+)
    // ------------------------------------------------------------------

    /** Height of the last finalized app block (0 = none yet). */
    long tipHeight();

    /** Finalized block at the given height, if present. */
    java.util.Optional<AppBlock> block(long height);

    /** State commitment root after the last finalized block (32 zero bytes if none). */
    byte[] stateRoot();

    /** Committed state value for a key, if present. */
    java.util.Optional<byte[]> stateValue(byte[] key);

    /**
     * MPF inclusion proof (wire format) for a key against the committed root;
     * verifiable off-chain and on-chain (Aiken MPF validator).
     */
    java.util.Optional<byte[]> stateProof(byte[] key);

    /** Height at which a message id was finalized, if it was. */
    java.util.Optional<Long> messageHeight(byte[] messageId);

    /**
     * Subscribe to finalized blocks (APP_FINAL). The listener is invoked once
     * per block, in height order, on a framework thread — keep it fast and
     * never block; offload heavy work. Close the returned handle to
     * unsubscribe. Used by SSE streaming, webhook sinks and connectors
     * (ADR app-layer/006 E3.1).
     */
    AutoCloseable subscribeFinalized(FinalizedBlockListener listener);

    @FunctionalInterface
    interface FinalizedBlockListener {
        void onFinalized(AppBlock block, byte[] blockHash);
    }
}
