package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * S1 fixed-sequencer engine (ADR app-layer/005 D2): a single configured
 * proposer batches pooled messages into blocks; every member independently
 * re-applies the block, verifies the state root byte-for-byte, and co-signs.
 * A block becomes APP_FINAL only with a threshold finality certificate whose
 * signatures are all verified against the membership registry.
 *
 * <p>Safety rules (fixing the PoC's gaps):
 * <ul>
 *   <li>Vote locks are persisted — at most one vote per height, across restarts.</li>
 *   <li>A timed-out round never orphans a height: the proposer re-proposes.</li>
 *   <li>Every signature (votes, certs) is cryptographically verified. Always.</li>
 *   <li>Block + state writes + trie nodes + root commit in ONE atomic batch.</li>
 * </ul>
 *
 * <p>All state transitions run on a single-threaded executor — the engine is
 * a serial event loop; inbound consensus messages and proposer ticks are
 * submitted onto it.
 */
final class AppChainEngine implements AutoCloseable {

    private final AppChainConfig config;
    private final AppLedgerStore ledger;
    private final AppMsgPool pool;
    private final AppStateMachine stateMachine;
    private final com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer;
    private final Set<String> memberKeys;
    private final int threshold;
    private final byte[] proposerKey;
    private final boolean isProposer;
    private final long roundTimeoutMs;
    private final int maxBlockMessages;
    private final long maxBlockBytes;
    /** Sends a body on a system topic to the group (via the subsystem's diffusion). */
    private final BiConsumer<String, byte[]> broadcast;
    private final Logger log;

    private final ExecutorService executor;

    /** In-flight round at height tip+1 (proposer and follower views). */
    private PendingRound pendingRound;

    private volatile BiConsumer<AppBlock, byte[]> onBlockFinalized;
    /** Stable L1 reference for proposals; null supplier or null value = no L1 ref (zeros). */
    private volatile java.util.function.Supplier<L1Ref> l1RefSupplier;

    record L1Ref(long slot, byte[] blockHash) {
    }

    void setL1RefSupplier(java.util.function.Supplier<L1Ref> supplier) {
        this.l1RefSupplier = supplier;
    }

    AppChainEngine(AppChainConfig config,
                   AppLedgerStore ledger,
                   AppMsgPool pool,
                   AppStateMachine stateMachine,
                   com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer,
                   Set<String> memberKeys,
                   int threshold,
                   byte[] proposerKey,
                   long roundTimeoutMs,
                   int maxBlockMessages,
                   long maxBlockBytes,
                   BiConsumer<String, byte[]> broadcast,
                   Logger log) {
        this.config = config;
        this.ledger = ledger;
        this.pool = pool;
        this.stateMachine = stateMachine;
        this.signer = signer;
        this.memberKeys = memberKeys;
        this.threshold = Math.max(1, threshold);
        this.proposerKey = proposerKey;
        this.isProposer = Arrays.equals(proposerKey, signer.publicKey());
        this.roundTimeoutMs = roundTimeoutMs;
        this.maxBlockMessages = maxBlockMessages;
        this.maxBlockBytes = maxBlockBytes;
        this.broadcast = broadcast;
        this.log = log;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-chain-engine-" + config.chainId());
            t.setDaemon(true);
            return t;
        });
        stateMachine.init(new CommittedStateReader(), new AppChainInfo(
                config.chainId(), signer.publicKeyHex(), memberKeys.size()));
    }

    void setOnBlockFinalized(BiConsumer<AppBlock, byte[]> callback) {
        this.onBlockFinalized = callback;
    }

    boolean isProposer() {
        return isProposer;
    }

    long tipHeight() {
        return ledger.tipHeight();
    }

    // ------------------------------------------------------------------
    // Entry points (thread-safe: hop onto the engine loop)
    // ------------------------------------------------------------------

    /** Proposer tick: propose the next block if messages are pending and no round is open. */
    void proposeTick() {
        executor.execute(this::doProposeTick);
    }

    /** Inbound consensus message (already envelope-verified + membership-checked). */
    void onConsensusMessage(AppMessage message) {
        executor.execute(() -> doHandleConsensusMessage(message));
    }

    /**
     * Catch-up path (protocol 103): apply already-finalized blocks fetched from
     * a peer. Each block is fully verified — hash chain, proposer, message
     * envelopes, re-executed state root, and the finality certificate — before
     * committing. Invalid blocks stop the batch (fail closed).
     */
    void onCertifiedBlocks(List<byte[]> blockCbors) {
        executor.execute(() -> {
            for (byte[] blockCbor : blockCbors) {
                try {
                    AppBlock block = AppBlockCodec.deserialize(blockCbor);
                    if (block.height() <= ledger.tipHeight()) {
                        continue; // already have it
                    }
                    if (!applyCertifiedBlock(block)) {
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Catch-up block rejected: {}", e.toString());
                    return;
                }
            }
        });
    }

    private boolean applyCertifiedBlock(AppBlock block) {
        long expectedHeight = ledger.tipHeight() + 1;
        if (block.height() != expectedHeight) {
            log.warn("Catch-up block height {} but expected {} — stopping batch",
                    block.height(), expectedHeight);
            return false;
        }
        if (!Arrays.equals(block.prevHash(), ledger.tipHash())) {
            log.warn("Catch-up block prev-hash mismatch at height {} — rejecting", block.height());
            return false;
        }
        if (!Arrays.equals(block.proposer(), proposerKey)) {
            log.warn("Catch-up block not from the configured proposer — rejecting");
            return false;
        }
        if (!Arrays.equals(block.messagesRoot(), AppBlockCodec.messagesRoot(block.messages()))) {
            log.warn("Catch-up block messages-root mismatch — rejecting");
            return false;
        }
        for (AppMessage message : block.messages()) {
            // No TTL check here: these messages were finalized before expiry
            if (!message.hasValidMessageId() || !verifyMemberSignature(message)) {
                log.warn("Catch-up block contains invalid message {} — rejecting",
                        message.getMessageIdHex());
                return false;
            }
        }
        byte[] blockHash = AppBlockCodec.blockHash(block);
        if (!verifyCert(block.cert(), blockHash)) {
            log.warn("Catch-up block cert verification FAILED at height {} — rejecting", block.height());
            return false;
        }

        // A certified block supersedes any local in-flight round at this height
        if (pendingRound != null && pendingRound.block.height() == block.height()) {
            discardRound();
        }

        AppliedBlock applied = applyBlock(block);
        if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
            log.warn("Catch-up block state-root mismatch at height {} — rejecting", block.height());
            applied.close();
            return false;
        }
        ledger.commitBlock(block, blockHash, block.stateRoot(), applied.batch);
        applied.closeBatchOnly();
        pool.remove(block.messages());
        BiConsumer<AppBlock, byte[]> callback = onBlockFinalized;
        if (callback != null) {
            try {
                callback.accept(block, blockHash);
            } catch (Exception e) {
                log.warn("onBlockFinalized callback failed", e);
            }
        }
        log.info("Catch-up: applied certified block at height {}", block.height());
        return true;
    }

    // ------------------------------------------------------------------
    // Proposer side
    // ------------------------------------------------------------------

    private void doProposeTick() {
        if (!isProposer) {
            return;
        }
        try {
            if (pendingRound != null) {
                if (System.currentTimeMillis() - pendingRound.startedAt > roundTimeoutMs) {
                    log.warn("App-chain round at height {} timed out ({} of {} votes) — re-proposing",
                            pendingRound.block.height(), pendingRound.votes.size(), threshold);
                    discardRound();
                } else {
                    return; // round in flight
                }
            }

            List<AppMessage> candidates = selectMessages();
            if (candidates.isEmpty()) {
                return;
            }

            long height = ledger.tipHeight() + 1;
            java.util.function.Supplier<L1Ref> refSupplier = l1RefSupplier;
            L1Ref l1Ref = refSupplier != null ? refSupplier.get() : null;
            AppBlock candidate = new AppBlock(
                    AppBlock.BLOCK_VERSION,
                    config.chainId(),
                    height,
                    ledger.tipHash(),
                    l1Ref != null ? l1Ref.slot() : 0L,
                    l1Ref != null ? l1Ref.blockHash() : new byte[0],
                    System.currentTimeMillis(),
                    AppBlockCodec.messagesRoot(candidates),
                    new byte[32],                    // placeholder until applied
                    candidates,
                    signer.publicKey(),
                    FinalityCert.empty());

            AppliedBlock applied = applyBlock(candidate);
            AppBlock block = applied.block;
            byte[] blockHash = AppBlockCodec.blockHash(block);

            // Vote lock for our own proposal, then self-vote
            Optional<byte[]> lock = ledger.voteLock(height);
            if (lock.isPresent() && !Arrays.equals(lock.get(), blockHash)) {
                log.warn("Vote lock exists for height {} with a different hash — not re-proposing differently; "
                        + "re-broadcasting locked proposal is required (restart artifact)", height);
                applied.close();
                return;
            }
            ledger.putVoteLock(height, blockHash);

            byte[] selfSignature = signer.sign(blockHash);
            PendingRound round = new PendingRound(block, blockHash, applied);
            round.votes.put(signer.publicKeyHex(), selfSignature);
            pendingRound = round;

            broadcast.accept(ConsensusCodec.TOPIC_PROPOSE, AppBlockCodec.serialize(block));
            log.info("Proposed app block: height={}, msgs={}, hash={}",
                    height, block.messages().size(), HexUtil.encodeHexString(blockHash));

            maybeFinalize();
        } catch (Exception e) {
            log.error("App-chain propose tick failed", e);
            discardRound();
        }
    }

    private List<AppMessage> selectMessages() {
        List<AppMessage> candidates = pool.drainCandidates(maxBlockMessages, maxBlockBytes);
        // Exclude anything already finalized (re-gossip after restart)
        candidates.removeIf(m -> ledger.messageHeight(m.getMessageId()).isPresent());
        // Application-level admission
        candidates.removeIf(m -> {
            AppStateMachine.AdmissionResult result = stateMachine.validate(m);
            if (!result.isAccepted()) {
                log.info("Message {} rejected by state machine: {}", m.getMessageIdHex(), result.reason());
                pool.remove(List.of(m));
                return true;
            }
            return false;
        });
        return candidates;
    }

    // ------------------------------------------------------------------
    // Message handling (proposer + follower)
    // ------------------------------------------------------------------

    private void doHandleConsensusMessage(AppMessage message) {
        try {
            switch (message.getTopic()) {
                case ConsensusCodec.TOPIC_PROPOSE -> handleProposal(message);
                case ConsensusCodec.TOPIC_VOTE -> handleVote(message);
                case ConsensusCodec.TOPIC_CERT -> handleCertNotice(message);
                default -> log.debug("Ignoring unknown consensus topic: {}", message.getTopic());
            }
        } catch (Exception e) {
            log.error("Error handling consensus message on {}", message.getTopic(), e);
        }
    }

    private void handleProposal(AppMessage envelope) {
        if (isProposer) {
            return; // we propose, we don't follow proposals
        }
        AppBlock block = AppBlockCodec.deserialize(envelope.getBody());
        long expectedHeight = ledger.tipHeight() + 1;

        if (block.height() <= ledger.tipHeight()) {
            return; // already finalized
        }
        if (block.height() != expectedHeight) {
            log.warn("Proposal at height {} but expected {} — ignoring (catch-up arrives in M4)",
                    block.height(), expectedHeight);
            return;
        }
        if (!Arrays.equals(block.proposer(), proposerKey)
                || !Arrays.equals(envelope.getSender(), proposerKey)) {
            log.warn("Proposal not from the configured proposer — rejecting");
            return;
        }
        if (!Arrays.equals(block.prevHash(), ledger.tipHash())) {
            log.warn("Proposal prev-hash mismatch at height {} — rejecting", block.height());
            return;
        }
        if (!Arrays.equals(block.messagesRoot(), AppBlockCodec.messagesRoot(block.messages()))) {
            log.warn("Proposal messages-root mismatch — rejecting");
            return;
        }
        // Every message inside the block must be a valid, member-signed envelope
        long now = System.currentTimeMillis() / 1000;
        for (AppMessage message : block.messages()) {
            if (!message.hasValidMessageId() || message.isExpired(now)
                    || !verifyMemberSignature(message)) {
                log.warn("Proposal contains invalid message {} — rejecting block",
                        message.getMessageIdHex());
                return;
            }
        }

        byte[] blockHash = AppBlockCodec.blockHash(block);
        Optional<byte[]> lock = ledger.voteLock(block.height());
        if (lock.isPresent() && !Arrays.equals(lock.get(), blockHash)) {
            log.warn("Refusing to vote: already voted for a different block at height {} (vote lock)",
                    block.height());
            return;
        }

        // Independent re-execution: state root must match byte-for-byte
        if (pendingRound != null) {
            discardRound();
        }
        AppliedBlock applied = applyBlock(block.withCert(FinalityCert.empty()));
        if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
            log.warn("Proposal state-root mismatch at height {} (local {} vs proposed {}) — rejecting",
                    block.height(),
                    HexUtil.encodeHexString(applied.block.stateRoot()),
                    HexUtil.encodeHexString(block.stateRoot()));
            applied.close();
            return;
        }

        ledger.putVoteLock(block.height(), blockHash);
        pendingRound = new PendingRound(block, blockHash, applied);

        byte[] signature = signer.sign(blockHash);
        broadcast.accept(ConsensusCodec.TOPIC_VOTE,
                ConsensusCodec.encodeVote(block.height(), blockHash, signature));
        log.info("Voted for app block: height={}, hash={}", block.height(),
                HexUtil.encodeHexString(blockHash));
    }

    private void handleVote(AppMessage envelope) {
        if (!isProposer || pendingRound == null) {
            return;
        }
        ConsensusCodec.Vote vote = ConsensusCodec.decodeVote(envelope.getBody());
        if (vote.height() != pendingRound.block.height()
                || !Arrays.equals(vote.blockHash(), pendingRound.blockHash)) {
            return; // stale/mismatched vote
        }
        String voterHex = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        if (!memberKeys.contains(voterHex)) {
            log.warn("Vote from non-member {} — ignoring", voterHex);
            return;
        }
        if (!AppMessageSigner.verify(vote.signature(), pendingRound.blockHash, envelope.getSender())) {
            log.warn("Invalid vote signature from {} — ignoring", voterHex);
            return;
        }
        pendingRound.votes.put(voterHex, vote.signature());
        log.info("Vote received for height {} from {} ({}/{})",
                vote.height(), voterHex, pendingRound.votes.size(), threshold);
        maybeFinalize();
    }

    private void maybeFinalize() {
        if (pendingRound == null || pendingRound.votes.size() < threshold) {
            return;
        }
        List<FinalityCert.Signature> signatures = new ArrayList<>();
        for (Map.Entry<String, byte[]> vote : pendingRound.votes.entrySet()) {
            signatures.add(new FinalityCert.Signature(
                    HexUtil.decodeHexString(vote.getKey()), vote.getValue()));
        }
        FinalityCert cert = new FinalityCert(FinalityCert.SCHEME_ED25519, signatures);
        commitRound(cert);
        AppBlock committed = ledger.block(ledger.tipHeight()).orElseThrow();
        broadcast.accept(ConsensusCodec.TOPIC_CERT,
                ConsensusCodec.encodeCertNotice(committed.height(),
                        AppBlockCodec.blockHash(committed),
                        AppBlockCodec.serializeCert(cert)));
    }

    private void handleCertNotice(AppMessage envelope) {
        ConsensusCodec.CertNotice notice = ConsensusCodec.decodeCertNotice(envelope.getBody());
        if (notice.height() <= ledger.tipHeight()) {
            return; // already committed
        }
        if (pendingRound == null || notice.height() != pendingRound.block.height()
                || !Arrays.equals(notice.blockHash(), pendingRound.blockHash)) {
            log.warn("Cert notice for height {} but no matching pending round (catch-up arrives in M4)",
                    notice.height());
            return;
        }
        FinalityCert cert = AppBlockCodec.deserializeCert(notice.certBytes());
        if (!verifyCert(cert, pendingRound.blockHash)) {
            log.warn("Cert verification FAILED for height {} — rejecting", notice.height());
            return;
        }
        commitRound(cert);
    }

    /** Verifies threshold, member uniqueness and every signature. Never trust-by-mode. */
    private boolean verifyCert(FinalityCert cert, byte[] blockHash) {
        if (cert.scheme() != FinalityCert.SCHEME_ED25519) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (FinalityCert.Signature signature : cert.signatures()) {
            String signerHex = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            if (!memberKeys.contains(signerHex) || !seen.add(signerHex)) {
                continue;
            }
            if (AppMessageSigner.verify(signature.signature(), blockHash, signature.signer())) {
                valid++;
            }
        }
        return valid >= threshold;
    }

    private void commitRound(FinalityCert cert) {
        PendingRound round = pendingRound;
        pendingRound = null;
        AppBlock finalBlock = round.block.withCert(cert);
        ledger.commitBlock(finalBlock, round.blockHash, finalBlock.stateRoot(), round.applied.batch);
        round.applied.closeBatchOnly();
        pool.remove(finalBlock.messages());
        BiConsumer<AppBlock, byte[]> callback = onBlockFinalized;
        if (callback != null) {
            try {
                callback.accept(finalBlock, round.blockHash);
            } catch (Exception e) {
                log.warn("onBlockFinalized callback failed", e);
            }
        }
    }

    private void discardRound() {
        if (pendingRound != null) {
            pendingRound.applied.close();
            pendingRound = null;
        }
    }

    // ------------------------------------------------------------------
    // Deterministic apply (shared by proposer and followers)
    // ------------------------------------------------------------------

    /**
     * Runs the state machine over the block, staging all writes (state trie
     * nodes) into a WriteBatch, and returns the block with its post-state root
     * filled in. Nothing touches the DB until {@code commitBlock} writes the
     * batch — discarding the batch discards the round.
     */
    private AppliedBlock applyBlock(AppBlock block) {
        WriteBatch batch = new WriteBatch();
        byte[] committedRoot = ledger.stateRoot();
        try {
            byte[] newRoot = ledger.mpfNodeStore().withBatch(batch, () -> {
                MpfTrie trie = committedRoot != null
                        ? new MpfTrie(ledger.mpfNodeStore(), committedRoot)
                        : new MpfTrie(ledger.mpfNodeStore());
                BatchStateWriter writer = new BatchStateWriter(trie);
                stateMachine.apply(block, writer);
                return trie.getRootHash();
            });
            byte[] effectiveRoot = newRoot != null ? newRoot : new byte[32];
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.cert());
            return new AppliedBlock(applied, batch);
        } catch (Exception e) {
            batch.close();
            throw new RuntimeException("Failed to apply app block " + block.height(), e);
        }
    }

    private boolean verifyMemberSignature(AppMessage message) {
        String senderHex = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        return memberKeys.contains(senderHex)
                && message.getAuthProof() != null
                && AppMessageSigner.verify(message.getAuthProof(), message.signedBodyBytes(), message.getSender());
    }

    @Override
    public void close() {
        executor.shutdownNow();
        discardRound();
    }

    // ------------------------------------------------------------------
    // Helper types
    // ------------------------------------------------------------------

    private static final class PendingRound {
        final AppBlock block;
        final byte[] blockHash;
        final AppliedBlock applied;
        final Map<String, byte[]> votes = new LinkedHashMap<>();
        final long startedAt = System.currentTimeMillis();

        PendingRound(AppBlock block, byte[] blockHash, AppliedBlock applied) {
            this.block = block;
            this.blockHash = blockHash;
            this.applied = applied;
        }
    }

    private static final class AppliedBlock implements AutoCloseable {
        final AppBlock block;
        final WriteBatch batch;

        AppliedBlock(AppBlock block, WriteBatch batch) {
            this.block = block;
            this.batch = batch;
        }

        void closeBatchOnly() {
            batch.close();
        }

        @Override
        public void close() {
            batch.close();
        }
    }

    /** Writer used during apply — reads see committed state, writes go to the trie. */
    private final class BatchStateWriter implements AppStateWriter {
        private final MpfTrie trie;

        BatchStateWriter(MpfTrie trie) {
            this.trie = trie;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            trie.put(key, value);
        }

        @Override
        public void delete(byte[] key) {
            trie.delete(key);
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(trie.get(key));
        }

        @Override
        public byte[] stateRoot() {
            return trie.getRootHash();
        }
    }

    /** Reader over the committed ledger state, handed to the state machine at init. */
    private final class CommittedStateReader implements AppStateReader {
        @Override
        public Optional<byte[]> get(byte[] key) {
            return ledger.stateGet(key);
        }

        @Override
        public byte[] stateRoot() {
            byte[] root = ledger.stateRoot();
            return root != null ? root : new byte[32];
        }
    }
}
