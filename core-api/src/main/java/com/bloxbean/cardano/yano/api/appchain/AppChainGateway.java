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

    /**
     * Validate and member-sign a state-machine-owned reserved-topic command.
     * Exposed only through privileged operator surfaces; implementations fail
     * closed unless the selected machine explicitly accepts the exact topic
     * and body.
     */
    default String submitPrivilegedSystemMessage(String topic, byte[] body) {
        throw new IllegalStateException("Privileged state-machine commands are unavailable");
    }

    /** Dry-run the same local validation performed before privileged submission. */
    default void validatePrivilegedSystemMessage(String topic, byte[] body) {
        throw new IllegalStateException("Privileged state-machine commands are unavailable");
    }

    /** Cached state-machine operational diagnostics, excluding secret detail. */
    default Map<String, Object> stateMachineStatus() {
        return Map.of();
    }

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
     * MPF inclusion or exclusion proof (wire format) for a key against the
     * committed root; verifiable off-chain and on-chain (Aiken MPF validator).
     */
    java.util.Optional<byte[]> stateProof(byte[] key);

    /**
     * Atomic inclusion or exclusion proof for one key, bound to the exact
     * committed height and state root used to read its value. Implementations
     * predating this capability must override this method before exposing the
     * proof endpoint. The default fails explicitly: composing {@link #stateRoot()},
     * {@link #stateValue(byte[])}, and {@link #stateProof(byte[])} can race a
     * concurrently finalized block and must never be presented as one proof
     * snapshot.
     *
     * @throws UnsupportedOperationException when the gateway has not implemented
     *                                       atomic proof snapshots
     */
    default java.util.Optional<AppStateProofSnapshot> stateProofSnapshot(byte[] key) {
        throw new UnsupportedOperationException(
                "Atomic app-chain state proof snapshots are unavailable");
    }

    /**
     * Build an inclusion or exclusion proof against the exact post-state root
     * of a retained finalized height.
     *
     * @throws UnsupportedOperationException when historical proof snapshots
     *                                       are unavailable
     */
    default java.util.Optional<AppStateProofSnapshot> stateProofSnapshotAtHeight(
            long height, byte[] key) {
        throw new UnsupportedOperationException(
                "Historical app-chain state proof snapshots are unavailable");
    }

    /**
     * Newest anchor this node has confirmed on L1, bound back to the exact
     * finalized app block and state root held in its ledger.
     */
    default java.util.Optional<AppAnchorCommitment> latestAnchorCommitment() {
        return java.util.Optional.empty();
    }

    /**
     * Run the configured state machine's bounded read hook against one
     * root-fixed committed-state snapshot (ADR app-layer/011.3 gate A).
     * Implementations execute plugin code away from the consensus executor.
     */
    default AppQueryResult query(String path, byte[] request) {
        throw new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                "App-chain query service is unavailable");
    }

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
     * Build portable verification material for a finalized message (ADR
     * app-layer/006 E3.4): its block(s), the bundle-claimed member key set, and
     * — when anchored — the L1 anchor reference and prev-hash chain to it.
     * Authenticity requires an independently trusted chain/member/threshold
     * context and independent verification of the exact Cardano anchor output.
     * Empty if the message id is unknown/not finalized.
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
    // Effects (ADR app-layer/010 F12) — consensus-tier read surface
    // ------------------------------------------------------------------

    /**
     * Emitted effect records, ascending by (height, ordinal), starting at
     * {@code fromHeight} (ADR-010 F12). Consensus view — identical on every
     * node; runtime execution status joins in the Effect Runtime surface.
     */
    default List<com.bloxbean.cardano.yano.api.appchain.effects.EffectView> effects(
            long fromHeight, int limit) {
        return List.of();
    }

    /** One emitted effect record, if it exists. */
    default java.util.Optional<com.bloxbean.cardano.yano.api.appchain.effects.EffectView> effect(
            long height, int ordinal) {
        return java.util.Optional.empty();
    }

    /**
     * Composed proof of one emission against the historical state root at its
     * block height. Distinguishes an unknown effect from retained commitment
     * metadata whose record/path material has been pruned.
     */
    default com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup effectProof(
            long height, int ordinal) {
        return com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup.notFound(0);
    }

    /**
     * Effect consensus/runtime observability. Consensus open/expiry values are
     * present even without a local executor; runtime gauges then read zero.
     */
    default Map<String, Object> effectStats() {
        return Map.of();
    }

    /** This node's execution-plane status of one effect; empty when no runtime / not tracked. */
    default java.util.Optional<Map<String, Object>> effectRuntimeStatus(long height, int ordinal) {
        return java.util.Optional.empty();
    }

    /** Operator requeue: PARKED/QUARANTINED → PENDING (ADR-010 F9). False when not applicable. */
    default boolean requeueEffect(long height, int ordinal) {
        return false;
    }

    /**
     * External-executor claim (ADR-010 F5): lease eligible effects to a named
     * external worker over REST. Node-local work-dispatch, never consensus;
     * an expired lease re-opens the effect. Empty unless this node runs the
     * Effect Runtime with {@code effects.external.enabled=true}.
     */
    default List<com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect> claimEffects(
            String executorId, java.util.Set<String> types, int max, long leaseSeconds) {
        return List.of();
    }

    /** External-executor report: definitive outcome for a claimed effect. */
    default boolean reportEffect(String executorId, long height, int ordinal, boolean success,
                                 byte[] externalRef, String reason) {
        return false;
    }

    /**
     * Operator cancel (ADR-010 F9): injects a member-signed CANCELLED
     * {@code ~fx/result} for an OPEN CHAIN effect. Effective only while no
     * terminal exists; cancel cannot unsend an in-flight execution — a late
     * result then no-ops against the CANCELLED terminal. False when the
     * effect is unknown, already closed, or not CHAIN-policy.
     */
    default boolean cancelEffect(long height, int ordinal, String reason) {
        return false;
    }

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

    /**
     * Operator escape hatch (stale-lock runbook, ADR 008.2/I4.2): clear THIS
     * member's vote lock at the pending height so it may vote once more
     * there. Refused while the locked round is still recoverable. Run only
     * after confirming no conflicting certificate exists on ANY member — the
     * at-most-one-vote guarantee is consciously overridden under operator
     * supervision.
     * @return true if a stale lock was cleared
     */
    default boolean unlockStaleRound() {
        throw new IllegalStateException("Sequencing is not enabled on this node");
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
