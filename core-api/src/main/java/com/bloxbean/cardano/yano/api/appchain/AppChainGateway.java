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

    /**
     * Build a portable, offline-verifiable evidence bundle for a finalized
     * message (ADR app-layer/006 E3.4): its block(s), the member key set, and —
     * when anchored — the L1 anchor reference and prev-hash chain to it. Empty
     * if the message id is unknown/not finalized.
     */
    java.util.Optional<com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle> evidence(byte[] messageId);

    /**
     * Create an atomic ledger snapshot for fast member onboarding
     * (ADR app-layer/006 E5.3). Copy the resulting directory to a new node's
     * app-chain ledger path to restore full state without replay.
     *
     * @param snapshotPath a fresh (non-existent) directory
     * @return the tip height captured in the snapshot
     */
    long snapshot(String snapshotPath);

    // ------------------------------------------------------------------
    // Query surface (ADR app-layer/006 E3.3)
    // ------------------------------------------------------------------

    /**
     * Finalized messages on a topic, ascending by (height, index), starting at
     * {@code fromHeight}. Indexed from the moment the node runs a build with the
     * query index (blocks finalized before the upgrade are not indexed).
     */
    List<MessageRef> messagesByTopic(String topic, long fromHeight, int limit);

    /** Finalized messages from a sender public key, ascending by (height, index). */
    List<MessageRef> messagesBySender(byte[] sender, long fromHeight, int limit);

    // ------------------------------------------------------------------
    // Admin operations (ADR app-layer/006 E5.4) — node-local operability
    // controls; they do not change consensus rules.
    // ------------------------------------------------------------------

    /** Pause LOCAL submissions (REST/submit()); finalized blocks still apply. */
    void pauseSubmissions();

    /** Resume local submissions after {@link #pauseSubmissions()}. */
    void resumeSubmissions();

    /** True when local submissions are paused. */
    boolean submissionsPaused();

    /**
     * Drop all pending (unfinalized) messages from this node's pool.
     * @return number of messages dropped
     */
    int drainPool();

    /**
     * Ask the anchor service to anchor the current tip now, ignoring the
     * every-blocks/interval schedule. No-op when anchoring is disabled or an
     * anchor tx is already pending.
     * @return true if an anchor submission was triggered
     */
    boolean forceAnchor();

    /**
     * Bootstrap the script anchor (ADR app-layer/008.4, admin action): mint
     * the one-shot state-thread NFT and lock the initial datum at the anchor
     * validator. Only valid on the anchor leader with {@code anchor.mode:
     * script}. Idempotent once bootstrapped.
     * @return identity/tx info (threadPolicyId, scriptHash, scriptAddress, txHash)
     */
    default java.util.Map<String, Object> bootstrapScriptAnchor() {
        throw new IllegalStateException("Script anchoring is not supported by this node");
    }

    // ------------------------------------------------------------------
    // Key rotation (ADR app-layer/006 E4.5) — staged member-key rotation.
    // Operator-coordinated: apply the SAME steps on EVERY node, in the runbook
    // order (add everywhere → switch signer → re-threshold → retire everywhere).
    // Rotated state persists and overrides the static config across restarts.
    // ------------------------------------------------------------------

    /** The effective member set (config or rotated override). */
    java.util.Set<String> members();

    /** The effective finality threshold (config or rotated override). */
    int effectiveThreshold();

    /** Stage 1: accept a new member key (idempotent). */
    void addMember(String publicKeyHex);

    /** Stage 3: retire a key. Rejects removing the proposer or dropping below threshold. */
    void removeMember(String publicKeyHex);

    /** Stage 2: adjust the finality threshold within [1, members]. */
    void setThreshold(int threshold);

    /**
     * Re-adopt the static config as a new membership epoch (escape hatch when a
     * persisted rotation must yield to a config change). History is preserved,
     * so previously finalized blocks keep verifying.
     */
    void resetMembers();

    @FunctionalInterface
    interface FinalizedBlockListener {
        void onFinalized(AppBlock block, byte[] blockHash);
    }
}
