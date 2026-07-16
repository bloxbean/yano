package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** Bound plugin-controlled exception metadata before it reaches operator logs. */
    private static final int MAX_CALLBACK_FAILURE_TYPE_CHARS = 256;

    private final AppChainConfig config;
    private final AppLedgerStore ledger;
    private final AppMsgPool pool;
    private final AppStateMachine stateMachine;
    private final com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer;
    private final MemberGroup group;
    private final SequencerMode sequencerMode;
    private final long roundTimeoutMs;
    /** Partial rounds observed competing with our lock (008.2 §0 residual case). */
    private final java.util.concurrent.atomic.AtomicLong splitVotesObserved =
            new java.util.concurrent.atomic.AtomicLong();
    /** Proposals rejected for exceeding block.max-bytes (block-bytes fix). */
    private final java.util.concurrent.atomic.AtomicLong oversizedProposalsRejected =
            new java.util.concurrent.atomic.AtomicLong();
    private final int maxBlockMessages;
    private final long maxBlockBytes;
    private final long proposalMaxBytes;
    private final EffectsSettings effectsSettings;
    private final FxKernel fxKernel;
    private final FxKernel.FxReader fxReader;
    private final Supplier<WriteBatch> writeBatchFactory;
    /** Sends a body on a system topic to the group (via the subsystem's diffusion). */
    private final BiFunction<String, byte[], AppMessage> broadcast;
    private final Logger log;

    private final ScheduledExecutorService executor;
    /**
     * Terminal engine-lifetime signal. Unlike {@link #close()}, this does not
     * complete until the serial event loop has stopped and its staged round
     * has been discarded. Callers that own the ledger/plugin classloader use
     * this as the safe teardown fence.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final Object closeLock = new Object();
    private boolean closeStarted;

    /** In-flight round at height tip+1 (proposer and follower views). */
    private PendingRound pendingRound;

    private volatile BiConsumer<AppBlock, byte[]> onBlockFinalized;
    /** Stable L1 reference for proposals; null supplier or null value = no L1 ref (zeros). */
    private volatile java.util.function.Supplier<L1Ref> l1RefSupplier;
    /** Follower-side check of a proposed L1 ref against this node's own L1 view (I1.3). */
    private volatile L1RefValidator l1RefValidator;
    /** Follower-side check of proposed ~l1/* observations (008.4 I3.2); null = accept. */
    private volatile ObservationValidator observationValidator;
    /** Proposals deferred because their l1-ref is ahead of the local L1 view: id → first deferral time. */
    private final Map<String, Long> deferredProposals = new HashMap<>();

    record L1Ref(long slot, byte[] blockHash) {
    }

    /** Verdict of checking a proposed (slot, hash) against the local L1 view. */
    enum L1RefVerdict {
        /** Present in the local stable window with the same hash. */
        OK,
        /** Slot known locally with a DIFFERENT hash, or in-window slot absent — fabricated/rolled-back ref. */
        MISMATCH,
        /** Beyond the local view (or not yet deep enough) — retry after the local L1 advances. */
        AHEAD,
        /** Older than the local window (restart) — fall back to monotonicity only. */
        UNKNOWN
    }

    interface L1RefValidator {
        L1RefVerdict check(long slot, byte[] blockHash);
    }

    /**
     * Follower-side check of one proposed {@code ~l1/*} observation message
     * against this node's own recomputed observations (008.4 I3.2). Same
     * verdict semantics as {@link L1RefValidator}.
     */
    interface ObservationValidator {
        L1RefVerdict check(AppMessage message);
    }

    void setL1RefSupplier(java.util.function.Supplier<L1Ref> supplier) {
        this.l1RefSupplier = supplier;
    }

    void setL1RefValidator(L1RefValidator validator) {
        this.l1RefValidator = validator;
    }

    void setObservationValidator(ObservationValidator validator) {
        this.observationValidator = validator;
    }

    AppChainEngine(AppChainConfig config,
                   AppLedgerStore ledger,
                   AppMsgPool pool,
                   AppStateMachine stateMachine,
                   com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer,
                   MemberGroup group,
                   SequencerMode sequencerMode,
                   long roundTimeoutMs,
                   int maxBlockMessages,
                   long maxBlockBytes,
                   BiFunction<String, byte[], AppMessage> broadcast,
                   Logger log) {
        this(config, ledger, pool, stateMachine, signer, group, sequencerMode,
                roundTimeoutMs, maxBlockMessages, maxBlockBytes, broadcast, log,
                WriteBatch::new);
    }

    /** Package-private batch factory seam for native-resource ownership regressions. */
    AppChainEngine(AppChainConfig config,
                   AppLedgerStore ledger,
                   AppMsgPool pool,
                   AppStateMachine stateMachine,
                   com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer,
                   MemberGroup group,
                   SequencerMode sequencerMode,
                   long roundTimeoutMs,
                   int maxBlockMessages,
                   long maxBlockBytes,
                   BiFunction<String, byte[], AppMessage> broadcast,
                   Logger log,
                   Supplier<WriteBatch> writeBatchFactory) {
        this.config = config;
        this.ledger = ledger;
        this.pool = pool;
        this.stateMachine = stateMachine;
        this.signer = signer;
        this.group = group;
        this.sequencerMode = sequencerMode;
        this.roundTimeoutMs = roundTimeoutMs;
        this.maxBlockMessages = maxBlockMessages;
        this.maxBlockBytes = maxBlockBytes;
        this.proposalMaxBytes = maxBlockBytes - config.finalityCertHeadroomBytes();
        if (proposalMaxBytes <= 0) {
            throw new IllegalArgumentException("block.max-bytes leaves no room for a v1 finality cert");
        }
        this.broadcast = broadcast;
        this.log = log;
        this.writeBatchFactory = Objects.requireNonNull(writeBatchFactory, "writeBatchFactory");
        this.effectsSettings = EffectsSettings.from(config);
        this.fxKernel = new FxKernel(effectsSettings);
        this.fxReader = ledger.fxReader();
        if (!effectsSettings.enabled() && ledger.fxOpenCount() > 0) {
            // One-way switch (ADR-010 F12): the expiry sweep only runs while
            // effects are enabled, so disabling with open effects would strand
            // their buckets forever (sweep reads only bucket(height)).
            throw new IllegalStateException("App-chain '" + config.chainId() + "' has "
                    + ledger.fxOpenCount() + " open effect(s) but effects.enabled=false — "
                    + "effects cannot be disabled once in use");
        }
        stateMachine.init(new CommittedStateReader(), new AppChainInfo(
                config.chainId(), signer.publicKeyHex(), group.size()));
        // Initialize the configured state-machine before acquiring the engine
        // executor. A failed plugin init must not leak an unpublished engine
        // thread from this constructor.
        this.executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "app-chain-engine-" + config.chainId());
            t.setDaemon(true);
            return t;
        }) {
            @Override
            protected void afterExecute(Runnable task, Throwable failure) {
                super.afterExecute(task, failure);
                // ScheduledThreadPoolExecutor wraps even execute(Runnable) in a
                // FutureTask. Without inspecting it, a VM-fatal plugin failure
                // is retained by a discarded Future and never reaches the
                // worker's uncaught-exception path.
                rethrowIfJvmFatal(completedTaskFailure(task, failure));
            }

            @Override
            protected void terminated() {
                try {
                    AppChainEngine.this.finishCloseAfterExecutorTermination();
                } finally {
                    super.terminated();
                }
            }
        };
        if (effectsSettings.enabled()) {
            log.info("App-chain '{}': effects enabled (max-per-block={}, max-payload-bytes={}, "
                    + "default-gate={}, outcome-commitment={})", config.chainId(),
                    effectsSettings.maxPerBlock(), effectsSettings.maxPayloadBytes(),
                    effectsSettings.defaultGate(), effectsSettings.outcomeCommitment());
        }
    }

    void setOnBlockFinalized(BiConsumer<AppBlock, byte[]> callback) {
        this.onBlockFinalized = callback;
    }

    /** Mode-specific observability (window/proposer/etc.). */
    Map<String, Object> sequencerStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>(sequencerMode.status());
        // Platform-owned canonical identity. A plugin cannot omit or spoof the
        // selected mode in operational status with its auxiliary status map.
        status.put("mode", sequencerMode.id());
        long stale = staleLockedHeight;
        if (stale > ledger.tipHeight()) {
            // Wedge visibility (I4.2): this member's vote at tip+1 is spent on
            // an unrecoverable proposal — see the stale-lock runbook
            status.put("staleLockedHeight", stale);
        }
        return status;
    }

    long splitVotesObserved() {
        return splitVotesObserved.get();
    }

    long oversizedProposalsRejected() {
        return oversizedProposalsRejected.get();
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
                    if (blockCbor == null || blockCbor.length == 0
                            || blockCbor.length > config.blockMaxBytes()) {
                        log.warn("Catch-up block exceeds the configured v1 byte profile — "
                                + "stopping batch");
                        return;
                    }
                    AppBlock block = AppBlockCodec.deserializeCanonical(
                            blockCbor, config.blockMaxBytes());
                    if (block.height() <= ledger.tipHeight()) {
                        continue; // already have it
                    }
                    if (!applyCertifiedBlock(block)) {
                        return;
                    }
                } catch (Throwable e) {
                    log.warn("Catch-up block rejected (errorType={})",
                            callbackFailureType(e));
                    rethrowIfJvmFatal(e);
                    return;
                }
            }
        });
    }

    private boolean applyCertifiedBlock(AppBlock block) {
        if (!validBlockProfile(block, "Catch-up block", false)) {
            return false;
        }
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
        // Certified history is mode-independent (ADR 008.2 §2.4): the threshold
        // cert is the legitimacy proof; the proposer just has to be a member at
        // that height. Live window rules were enforced by the voters back then.
        String catchUpProposerHex = HexUtil.encodeHexString(block.proposer()).toLowerCase(Locale.ROOT);
        if (!group.containsAt(catchUpProposerHex, block.height())) {
            log.warn("Catch-up block proposer is not a member at height {} — rejecting", block.height());
            return false;
        }
        if (!Arrays.equals(block.messagesRoot(), AppBlockCodec.messagesRoot(block.messages()))) {
            log.warn("Catch-up block messages-root mismatch — rejecting");
            return false;
        }
        if (!verifyCatchUpL1Ref(block)) {
            return false;
        }
        if (!verifyCatchUpObservations(block)) {
            return false;
        }
        for (AppMessage message : block.messages()) {
            // No TTL check here: these messages were finalized before expiry
            if (!validFinalizedMessageProfile(message)
                    || !message.hasValidMessageId()
                    || !verifyMemberSignature(message, block.height())
                    || !authorizedResultMessage(message)) {
                log.warn("Catch-up block contains an invalid message at height {} — rejecting",
                        block.height());
                return false;
            }
        }
        if (!verifySenderSeqs(block, "Catch-up block")) {
            return false;
        }
        byte[] blockHash = AppBlockCodec.blockHash(block);
        if (!verifyCert(block.cert(), blockHash, block.height())) {
            log.warn("Catch-up block cert verification FAILED at height {} — rejecting", block.height());
            return false;
        }

        // A certified block supersedes any local in-flight round at this height
        if (pendingRound != null && pendingRound.block.height() == block.height()) {
            discardRound();
        }

        try (AppliedBlock applied = applyBlock(block)) {
            if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
                log.warn("Catch-up block state-root mismatch at height {} — rejecting", block.height());
                return false;
            }
            ledger.stageFx(applied.batch, block.height(), applied.fx);
            ledger.commitBlock(block, blockHash, block.stateRoot(), applied.batch,
                    governanceWrites(block));
        }
        pool.remove(block.messages());
        BiConsumer<AppBlock, byte[]> callback = onBlockFinalized;
        if (callback != null) {
            try {
                callback.accept(block, blockHash);
            } catch (Throwable e) {
                log.warn("onBlockFinalized callback failed (errorType={})",
                        callbackFailureType(e));
                rethrowIfJvmFatal(e);
            }
        }
        log.info("Catch-up: applied certified block at height {}", block.height());
        return true;
    }

    // ------------------------------------------------------------------
    // Proposer side
    // ------------------------------------------------------------------

    private void doProposeTick() {
        try {
            if (pendingRound != null) {
                if (System.currentTimeMillis() - pendingRound.startedAt > roundTimeoutMs) {
                    log.warn("App-chain round at height {} timed out ({} of {} votes)",
                            pendingRound.block.height(), pendingRound.votes.size(), group.threshold());
                    discardRound();
                } else {
                    return; // round in flight
                }
            }

            long height = ledger.tipHeight() + 1;

            // Partial-round recovery (ANY member, any mode — ADR 008.2 §2.3):
            // once we voted at this height our one vote is spent; keep
            // re-gossiping the locked original proposal (+ our vote) until it
            // finalizes, and never VOTE for a competing block.
            Optional<byte[]> existingLock = ledger.voteLock(height);
            boolean staleLock = false;
            if (existingLock.isPresent()) {
                if (!lockedProposalUnrecoverable(height)) {
                    regossipLockedProposal(height, existingLock.get());
                    return;
                }
                // STALE lock (I4.2, found by the Iteration-3 gate): the locked
                // proposal expired (crash-restart past its TTL) — it can never
                // certify again, because every honest member rejects expired
                // messages at proposal verification. Move the chain on around
                // our spent vote: propose a FRESH block but never self-vote at
                // this height (the at-most-one-vote guarantee stands). The
                // round then needs `threshold` votes from OTHER members; if
                // the threshold is unreachable without us (e.g. 2-of-2), the
                // operator unlock runbook applies (admin/unlock-stale-round).
                staleLock = true;
                staleLockedHeight = height;
            }

            if (!sequencerMode.shouldProposeNow(height)) {
                return;
            }

            List<AppMessage> candidates = selectMessages();
            if (candidates.isEmpty()) {
                return;
            }
            java.util.function.Supplier<L1Ref> refSupplier = l1RefSupplier;
            L1Ref l1Ref = refSupplier != null ? refSupplier.get() : null;
            byte[] prevHash = ledger.tipHash();
            long timestamp = System.currentTimeMillis();

            AppBlock candidate = buildCandidateBlock(height, prevHash, l1Ref, timestamp, candidates);
            // Trim so the serialized block (which a proposal carries whole over
            // the app-message transport) fits block.max-bytes — otherwise the
            // proposal exceeds the transport limit and followers silently drop
            // it, stalling the height. Deferred messages stay pooled for the
            // next block (block-bytes fix).
            if (AppBlockCodec.serialize(candidate).length > proposalMaxBytes) {
                candidates = fitToBlockBytes(height, prevHash, l1Ref, timestamp, candidates);
                if (candidates.isEmpty()) {
                    log.warn("App-chain '{}': cannot fit even one message under block.max-bytes ({}) "
                            + "at height {} — skipping this round", config.chainId(),
                            proposalMaxBytes, height);
                    return;
                }
                candidate = buildCandidateBlock(height, prevHash, l1Ref, timestamp, candidates);
            }
            if (AppBlockCodec.serialize(candidate).length > proposalMaxBytes) {
                log.error("App-chain '{}' produced a proposal above its v1 byte budget at height {}",
                        config.chainId(), height);
                return;
            }

            AppliedBlock applied = applyBlock(candidate);
            AppBlock block = applied.block;
            byte[] blockHash;
            try {
                blockHash = AppBlockCodec.blockHash(block);
            } catch (Throwable failure) {
                throw closeAppliedAfterFailure(applied, failure);
            }

            PendingRound round = publishPendingRound(block, blockHash, applied);
            if (!staleLock) {
                // Vote lock for our own proposal, then self-vote
                ledger.putVoteLock(height, blockHash);
                round.votes.put(signer.publicKeyHex(), signer.sign(blockHash));
            }

            AppMessage proposalEnvelope =
                    broadcast.apply(ConsensusCodec.TOPIC_PROPOSE, AppBlockCodec.serialize(block));
            if (!staleLock && proposalEnvelope != null) {
                // Enables re-gossip of the partial round across timeouts/restarts.
                // A stale-locked proposer must NOT clobber the stored envelope —
                // the lock still documents the block we actually voted for.
                ledger.putVoteLockEnvelope(height, ConsensusCodec.encodeEnvelope(proposalEnvelope));
            }
            if (staleLock) {
                log.warn("Proposed app block AROUND a stale vote lock: height={}, msgs={}, hash={} — "
                        + "not self-voting (needs {} votes from other members; "
                        + "see the stale-lock runbook if the threshold is unreachable)",
                        height, block.messages().size(), HexUtil.encodeHexString(blockHash),
                        group.thresholdAt(height));
            } else {
                log.info("Proposed app block: height={}, msgs={}, hash={}",
                        height, block.messages().size(), HexUtil.encodeHexString(blockHash));
            }
            maybeFinalize();
        } catch (Throwable e) {
            Throwable outcome = discardRoundAfterFailure(e);
            log.error("App-chain propose tick failed (errorType={})",
                    callbackFailureType(outcome));
            rethrowIfJvmFatal(outcome);
        }
    }

    private List<AppMessage> selectMessages() {
        List<AppMessage> candidates = pool.drainCandidates(maxBlockMessages, proposalMaxBytes);
        // Exclude anything already finalized (re-gossip after restart)
        candidates.removeIf(m -> ledger.messageHeight(m.getMessageId()).isPresent());
        // Sender-seq replay floor (I1.2): drop stale seqs; with enforcement on,
        // also keep per-sender seqs strictly increasing WITHIN the block so an
        // honest proposer never builds a block enforcing followers would reject
        Map<String, Long> senderFloor = new HashMap<>();
        candidates.removeIf(m -> {
            if (m.getSenderSeq() <= 0) {
                return false;
            }
            String senderHex = HexUtil.encodeHexString(m.getSender());
            long floor = senderFloor.computeIfAbsent(senderHex,
                    h -> ledger.senderSeq(m.getSender()));
            if (m.getSenderSeq() <= floor) {
                log.info("Message {} dropped: stale sender-seq {} (floor {})",
                        m.getMessageIdHex(), m.getSenderSeq(), floor);
                pool.remove(List.of(m));
                return true;
            }
            if (config.enforceSenderSeq()) {
                senderFloor.put(senderHex, m.getSenderSeq());
            }
            return false;
        });
        // Designated result signers are a consensus-affecting chain policy.
        // Drop unauthorized results before an honest proposer spends block
        // capacity on a message the kernel will deterministically ignore.
        candidates.removeIf(m -> {
            if (authorizedResultMessage(m)) {
                return false;
            }
            log.info("Effect result {} dropped: sender is not designated by effects.result.signers",
                    m.getMessageIdHex());
            pool.remove(List.of(m));
            return true;
        });
        // Application-level admission — framework system topics (~governance/*)
        // bypass it: state machines must not veto governance commands (008.3);
        // they skip these opaque bodies deterministically in apply()
        candidates.removeIf(m -> {
            String topic = m.getTopic() != null ? m.getTopic() : "";
            if (topic.startsWith("~")) {
                return false;
            }
            AppStateMachine.AdmissionResult result = stateMachine.validate(m);
            if (!result.isAccepted()) {
                // Admission reasons are plugin-controlled free text. Do not
                // copy a possibly secret-bearing reason into the default node
                // log; the message id is sufficient to correlate rejection.
                log.info("Message {} rejected by state machine", m.getMessageIdHex());
                pool.remove(List.of(m));
                return true;
            }
            return false;
        });
        return candidates;
    }

    /** Build an unapplied candidate block (state-root placeholder) for the given messages. */
    private AppBlock buildCandidateBlock(long height, byte[] prevHash, L1Ref l1Ref,
                                         long timestamp, List<AppMessage> messages) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                config.chainId(),
                height,
                prevHash,
                l1Ref != null ? l1Ref.slot() : 0L,
                l1Ref != null ? l1Ref.blockHash() : new byte[0],
                timestamp,
                AppBlockCodec.messagesRoot(messages),
                new byte[32],                    // placeholder until applied
                messages,
                signer.publicKey(),
                FinalityCert.empty());
    }

    /**
     * Drop trailing messages until the serialized block fits {@code maxBlockBytes}.
     * Drops proportionally to the overflow so it converges in a couple of passes;
     * the removed messages remain in the pool for the next block.
     */
    private List<AppMessage> fitToBlockBytes(long height, byte[] prevHash, L1Ref l1Ref,
                                             long timestamp, List<AppMessage> candidates) {
        List<AppMessage> list = new ArrayList<>(candidates);
        while (list.size() > 1) {
            int size = AppBlockCodec.serialize(
                    buildCandidateBlock(height, prevHash, l1Ref, timestamp, list)).length;
            if (size <= proposalMaxBytes) {
                break;
            }
            int drop = Math.max(1, (int) ((long) list.size()
                    * (size - proposalMaxBytes) / size));
            list = new ArrayList<>(list.subList(0, list.size() - drop));
        }
        if (!list.isEmpty() && AppBlockCodec.serialize(
                buildCandidateBlock(height, prevHash, l1Ref, timestamp, list)).length
                > proposalMaxBytes) {
            AppMessage impossible = list.getFirst();
            pool.remove(List.of(impossible));
            log.warn("App-chain '{}': message {} cannot fit in an otherwise empty v1 block "
                            + "under the proposal byte budget ({}) — dropping it",
                    config.chainId(), impossible.getMessageIdHex(), proposalMaxBytes);
            return List.of();
        }
        if (list.size() < candidates.size()) {
            log.info("App-chain '{}': proposal trimmed to fit block.max-bytes — {} of {} messages "
                    + "(the rest stay pooled for the next block)",
                    config.chainId(), list.size(), candidates.size());
        }
        return list;
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
        } catch (Throwable e) {
            log.error("Error handling consensus message on {} (errorType={})",
                    message.getTopic(), callbackFailureType(e));
            rethrowIfJvmFatal(e);
        }
    }

    private void handleProposal(AppMessage envelope) {
        if (envelope.getBody() == null || envelope.getBody().length == 0
                || envelope.getBody().length > proposalMaxBytes) {
            oversizedProposalsRejected.incrementAndGet();
            log.warn("Proposal exceeds the v1 proposal byte budget ({} > {}) — rejecting",
                    envelope.getBody() == null ? 0 : envelope.getBody().length,
                    proposalMaxBytes);
            return;
        }
        AppBlock block = AppBlockCodec.deserializeCanonical(
                envelope.getBody(), proposalMaxBytes);
        if (!validBlockProfile(block, "Proposal", true)) {
            return;
        }
        long expectedHeight = ledger.tipHeight() + 1;

        if (block.height() <= ledger.tipHeight()) {
            return; // already finalized
        }
        if (block.height() != expectedHeight) {
            // Ahead of our tip: our commit of height-1 may simply be in flight.
            // The transport never re-delivers an acked message id in a session,
            // so dropping here would lose the proposal PERMANENTLY (catch-up
            // cannot fetch unfinalized heights) — defer and retry instead.
            deferProposal(envelope, block, "ahead of local tip (expected " + expectedHeight + ")");
            return;
        }
        if (pendingRound != null
                && Arrays.equals(pendingRound.blockHash, AppBlockCodec.blockHash(block))) {
            return; // already tracking this exact round (own proposal / re-gossip echo)
        }
        // Authenticity: the envelope must be signed by the block's claimed
        // proposer — nobody can inject a block in another member's name
        if (!Arrays.equals(block.proposer(), envelope.getSender())) {
            log.warn("Proposal envelope sender does not match the block proposer — rejecting");
            return;
        }
        // Sequencer-mode eligibility (ADR 008.2): fixed = the configured key;
        // rotating = scheduled within the lookback window range
        switch (sequencerMode.checkProposal(block.proposer(), block.height())) {
            case REJECT -> {
                log.warn("Proposal at height {} from a proposer not eligible under sequencer mode "
                        + "'{}' — rejecting", block.height(), sequencerMode.id());
                return;
            }
            case DEFER -> {
                deferProposal(envelope, block, "sequencer clock not ready");
                return;
            }
            case ACCEPT -> { /* continue */ }
        }
        if (!Arrays.equals(block.prevHash(), ledger.tipHash())) {
            log.warn("Proposal prev-hash mismatch at height {} — rejecting", block.height());
            return;
        }
        if (!Arrays.equals(block.messagesRoot(), AppBlockCodec.messagesRoot(block.messages()))) {
            log.warn("Proposal messages-root mismatch — rejecting");
            return;
        }
        if (!verifyProposalL1Ref(envelope, block)) {
            return;
        }
        if (!verifyProposalObservations(envelope, block)) {
            return;
        }
        // Every message inside the block must be a valid, member-signed envelope
        // whose body is within the per-message limit (the DoS guard now lives
        // here so the transport frame limit can be relaxed for whole-block
        // proposals — block-bytes fix).
        long now = System.currentTimeMillis() / 1000;
        for (AppMessage message : block.messages()) {
            if (!validFinalizedMessageProfile(message)
                    || !message.hasValidMessageId() || message.isExpired(now)
                    || !verifyMemberSignature(message, block.height())) {
                log.warn("Proposal contains an invalid message at height {} — rejecting block",
                        block.height());
                return;
            }
            if (!authorizedResultMessage(message)) {
                log.warn("Proposal contains effect result {} from a non-designated signer — "
                        + "rejecting block", message.getMessageIdHex());
                return;
            }
        }
        if (!verifySenderSeqs(block, "Proposal")) {
            return;
        }

        byte[] blockHash = AppBlockCodec.blockHash(block);
        Optional<byte[]> lock = ledger.voteLock(block.height());
        if (lock.isPresent() && !Arrays.equals(lock.get(), blockHash)) {
            // Competing valid proposal at a height we already voted — the split
            // is visible and counted (ADR 008.2 §0 residual case + runbook)
            splitVotesObserved.incrementAndGet();
            log.warn("Refusing to vote: already voted for a different block at height {} (vote lock). "
                    + "Competing proposal observed — split votes possible, see the rotation runbook",
                    block.height());
            return;
        }

        // Independent re-execution: state root must match byte-for-byte
        if (pendingRound != null) {
            discardRound();
        }
        AppliedBlock applied = applyBlock(block.withCert(FinalityCert.empty()));
        if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
            try (applied) {
                log.warn("Proposal state-root mismatch at height {} (local {} vs proposed {}) — rejecting",
                        block.height(),
                        HexUtil.encodeHexString(applied.block.stateRoot()),
                        HexUtil.encodeHexString(block.stateRoot()));
            }
            return;
        }

        PendingRound round = publishPendingRound(block, blockHash, applied);
        try {
            ledger.putVoteLock(block.height(), blockHash);
            // Persist the original proposer-signed envelope for partial-round
            // re-gossip (ADR 008.2 §2.3)
            ledger.putVoteLockEnvelope(block.height(), ConsensusCodec.encodeEnvelope(envelope));

            byte[] signature = signer.sign(blockHash);
            // Record our own vote locally too — any member holding the round may
            // aggregate to a cert (dead proposers can't sink collected votes)
            round.votes.put(signer.publicKeyHex(), signature);
            broadcast.apply(ConsensusCodec.TOPIC_VOTE,
                    ConsensusCodec.encodeVote(block.height(), blockHash, signature));
            log.info("Voted for app block: height={}, hash={}", block.height(),
                    HexUtil.encodeHexString(blockHash));
            maybeFinalize();
        } catch (Throwable failure) {
            if (pendingRound == round) {
                failure = discardRoundAfterFailure(failure);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Failed to handle app-chain proposal", failure);
        }
    }

    /**
     * A locked round that can never complete: the stored proposal envelope is
     * missing (legacy lock) or its messages EXPIRED (crash-restart past the
     * TTL) — every honest member rejects expired messages at proposal
     * verification, so the locked block is permanently un-certifiable and the
     * proposer may move the chain on around its spent vote (I4.2).
     */
    private boolean lockedProposalUnrecoverable(long height) {
        Optional<byte[]> stored = ledger.voteLockEnvelope(height);
        if (stored.isEmpty()) {
            return true; // pre-008.2 lock — nothing to re-gossip
        }
        return ConsensusCodec.decodeEnvelope(stored.get())
                .isExpired(System.currentTimeMillis() / 1000);
    }

    /** Wedge visibility (I4.2): tip+1 locked on an unrecoverable proposal. */
    private volatile long staleLockedHeight;

    /**
     * Operator escape hatch (stale-lock runbook, I4.2): clear THIS member's
     * vote lock at tip+1 so it may vote once more there. Refused while the
     * locked round is still recoverable — this consciously trades the
     * at-most-one-vote guarantee for liveness, so it must only run after the
     * operator confirmed no conflicting certificate exists on any member.
     *
     * @return true if a stale lock was cleared
     */
    boolean clearStaleVoteLock() throws Exception {
        return executor.submit(() -> {
            long height = ledger.tipHeight() + 1;
            Optional<byte[]> lock = ledger.voteLock(height);
            if (lock.isEmpty()) {
                return false;
            }
            if (!lockedProposalUnrecoverable(height)) {
                log.warn("Refusing operator unlock at height {} — the locked round is still "
                        + "recoverable (proposal not expired)", height);
                return false;
            }
            ledger.removeVoteLock(height);
            if (pendingRound != null && pendingRound.block.height() == height) {
                discardRound();
            }
            staleLockedHeight = 0;
            log.warn("Vote lock at height {} CLEARED by operator (stale-lock runbook) — this "
                    + "member may vote once more at this height", height);
            return true;
        }).get(5, TimeUnit.SECONDS);
    }

    /**
     * Re-gossip the locked original proposal (+ our vote) until the height
     * finalizes (ADR 008.2 §2.3) — rate-limited to once per round timeout.
     * Only works while the original envelope is TTL-valid; past that the
     * proposer proposes around the stale lock instead (I4.2).
     */
    private long lastRegossipAt;

    private void regossipLockedProposal(long height, byte[] lockedHash) {
        if (System.currentTimeMillis() - lastRegossipAt < roundTimeoutMs) {
            return;
        }
        lastRegossipAt = System.currentTimeMillis();
        Optional<byte[]> stored = ledger.voteLockEnvelope(height);
        if (stored.isEmpty()) {
            return; // pre-008.2 lock — nothing to re-gossip (legacy restart artifact)
        }
        AppMessage envelope = ConsensusCodec.decodeEnvelope(stored.get());
        if (envelope.isExpired(System.currentTimeMillis() / 1000)) {
            log.warn("Locked proposal at height {} has expired — partial round cannot be "
                    + "re-gossiped (see the rotation runbook)", height);
            return;
        }
        Consumer<AppMessage> relay = envelopeRelay;
        if (relay != null) {
            relay.accept(envelope);
        }
        // Re-broadcast our vote (votes are ephemeral) and rebuild our own
        // pending round so we can aggregate replies
        broadcast.apply(ConsensusCodec.TOPIC_VOTE,
                ConsensusCodec.encodeVote(height, lockedHash, signer.sign(lockedHash)));
        doHandleConsensusMessage(envelope);
        log.info("Re-gossiped locked proposal at height {} (partial-round recovery)", height);
    }

    private volatile Consumer<AppMessage> envelopeRelay;

    void setEnvelopeRelay(Consumer<AppMessage> relay) {
        this.envelopeRelay = relay;
    }

    /** Chain-governed membership handler (ADR 008.3); null = static mode. */
    private volatile GovernedMembership governance;

    void setGovernance(GovernedMembership governance) {
        this.governance = governance;
    }

    /** Deterministic governance processing, atomic with the block commit. */
    private List<GovernedMembership.MetaWrite> governanceWrites(AppBlock block) {
        GovernedMembership current = governance;
        if (current == null) {
            return List.of();
        }
        GovernedMembership.Result result = current.processBlock(block);
        for (GovernedMembership.EpochEffect effect : result.effects()) {
            log.info("Governed membership epoch: from height {}, {} member(s), threshold {}",
                    effect.fromHeight(), effect.members().size(), effect.threshold());
        }
        return result.writes();
    }

    /** Bounded retry for proposals we cannot judge yet (tip/clock/l1 not ready). */
    private void deferProposal(AppMessage envelope, AppBlock block, String reason) {
        long waitMs = Math.max(config.blockIntervalMs() * 4, 15_000);
        long firstDeferred = deferredProposals.computeIfAbsent(
                envelope.getMessageIdHex(), id -> System.currentTimeMillis());
        if (System.currentTimeMillis() - firstDeferred < waitMs) {
            log.debug("Proposal at height {} deferred: {}", block.height(), reason);
            executor.schedule(() -> doHandleConsensusMessage(envelope), 500, TimeUnit.MILLISECONDS);
        } else {
            log.warn("Proposal at height {} still undecidable after {} ms ({}) — dropping",
                    block.height(), waitMs, reason);
            deferredProposals.remove(envelope.getMessageIdHex());
        }
    }

    private void handleVote(AppMessage envelope) {
        // ANY member holding the round aggregates votes and may form the cert —
        // a dead proposer can no longer sink already-collected votes (008.2)
        if (pendingRound == null) {
            return;
        }
        ConsensusCodec.Vote vote = ConsensusCodec.decodeVote(envelope.getBody());
        if (vote.height() != pendingRound.block.height()
                || !Arrays.equals(vote.blockHash(), pendingRound.blockHash)) {
            return; // stale/mismatched vote
        }
        String voterHex = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        if (!group.containsAt(voterHex, pendingRound.block.height())) {
            log.warn("Vote from non-member {} — ignoring", voterHex);
            return;
        }
        if (!AppMessageSigner.verify(vote.signature(), pendingRound.blockHash, envelope.getSender())) {
            log.warn("Invalid vote signature from {} — ignoring", voterHex);
            return;
        }
        pendingRound.votes.put(voterHex, vote.signature());
        log.info("Vote received for height {} from {} ({}/{})",
                vote.height(), voterHex, pendingRound.votes.size(), group.threshold());
        maybeFinalize();
    }

    private void maybeFinalize() {
        if (pendingRound == null) {
            return;
        }
        // Count only votes from members of the epoch at this block's height —
        // a mid-round rotation must not let a removed member's vote finalize.
        long height = pendingRound.block.height();
        pendingRound.votes.keySet().removeIf(voter -> !group.containsAt(voter, height));
        if (pendingRound.votes.size() < group.thresholdAt(height)) {
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
        broadcast.apply(ConsensusCodec.TOPIC_CERT,
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
        FinalityCert cert = AppBlockCodec.deserializeCertCanonical(notice.certBytes());
        if (!verifyCert(cert, pendingRound.blockHash, pendingRound.block.height())) {
            log.warn("Cert verification FAILED for height {} — rejecting", notice.height());
            return;
        }
        commitRound(cert);
    }

    /** Verifies threshold, member uniqueness and every signature. Never trust-by-mode. */
    private boolean verifyCert(FinalityCert cert, byte[] blockHash, long height) {
        if (cert == null || cert.scheme() != FinalityCert.SCHEME_ED25519
                || cert.signatures().isEmpty()
                || cert.signatures().size() > AppChainConfig.MAX_MEMBERS) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (FinalityCert.Signature signature : cert.signatures()) {
            if (signature == null || signature.signer() == null
                    || signature.signer().length != 32
                    || signature.signature() == null
                    || signature.signature().length
                    != AppChainConfig.ED25519_SIGNATURE_BYTES) {
                return false;
            }
            String signerHex = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            if (!group.containsAt(signerHex, height) || !seen.add(signerHex)) {
                return false;
            }
            if (!AppMessageSigner.verify(signature.signature(), blockHash, signature.signer())) {
                return false;
            }
            valid++;
        }
        return valid >= group.thresholdAt(height);
    }

    private void commitRound(FinalityCert cert) {
        PendingRound round = pendingRound;
        AppBlock finalBlock = round.block.withCert(cert);
        if (AppBlockCodec.serialize(finalBlock).length > maxBlockBytes) {
            throw new IllegalStateException("Finalized app block exceeds block.max-bytes after cert");
        }
        pendingRound = null;
        deferredProposals.clear(); // height advances — held l1-ref deferrals are moot
        try (AppliedBlock applied = round.applied) {
            ledger.stageFx(applied.batch, finalBlock.height(), applied.fx);
            ledger.commitBlock(finalBlock, round.blockHash, finalBlock.stateRoot(), applied.batch,
                    governanceWrites(finalBlock));
        }
        pool.remove(finalBlock.messages());
        BiConsumer<AppBlock, byte[]> callback = onBlockFinalized;
        if (callback != null) {
            try {
                callback.accept(finalBlock, round.blockHash);
            } catch (Throwable e) {
                log.warn("onBlockFinalized callback failed (errorType={})",
                        callbackFailureType(e));
                rethrowIfJvmFatal(e);
            }
        }
    }

    private void discardRound() {
        PendingRound round = pendingRound;
        // Relinquish ownership before cleanup so a failing native close cannot
        // make a later error path close the same WriteBatch twice.
        pendingRound = null;
        if (round != null) {
            round.applied.close();
        }
    }

    /** Transfer the staged batch to {@link #pendingRound} or close it on publication failure. */
    private PendingRound publishPendingRound(AppBlock block, byte[] blockHash, AppliedBlock applied) {
        try {
            PendingRound round = new PendingRound(block, blockHash, applied);
            pendingRound = round;
            return round;
        } catch (Throwable failure) {
            throw closeAppliedAfterFailure(applied, failure);
        }
    }

    /** Preserve the primary failure while guaranteeing native batch release. */
    private static RuntimeException closeAppliedAfterFailure(
            AppliedBlock applied,
            Throwable primary
    ) {
        Throwable outcome = primary;
        try {
            applied.close();
        } catch (Throwable cleanupFailure) {
            outcome = mergeCleanupFailure(outcome, cleanupFailure);
        }
        if (outcome instanceof Error error) {
            throw error;
        }
        if (outcome instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Failed to release staged app-chain block", outcome);
    }

    /** Close an already-published round after a failed event-loop operation. */
    private Throwable discardRoundAfterFailure(Throwable primary) {
        try {
            discardRound();
            return primary;
        } catch (Throwable cleanupFailure) {
            return mergeCleanupFailure(primary, cleanupFailure);
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
        WriteBatch batch = Objects.requireNonNull(
                writeBatchFactory.get(), "writeBatchFactory returned null");
        try {
            byte[] committedRoot = ledger.stateRoot();
            FxKernel.Result[] fxResult = new FxKernel.Result[1];
            byte[] newRoot = ledger.mpfNodeStore().withBatch(batch, () -> {
                MpfTrie trie = committedRoot != null
                        ? new MpfTrie(ledger.mpfNodeStore(), committedRoot)
                        : new MpfTrie(ledger.mpfNodeStore());
                fxResult[0] = fxKernel.apply(stateMachine, block, trie, fxReader);
                return trie.getRootHash();
            });
            byte[] effectiveRoot = newRoot != null ? newRoot : new byte[32];
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.cert());
            return new AppliedBlock(applied, batch, fxResult[0]);
        } catch (Throwable failure) {
            Throwable outcome = failure;
            try {
                batch.close();
            } catch (Throwable cleanupFailure) {
                outcome = mergeCleanupFailure(outcome, cleanupFailure);
            }
            if (outcome instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Failed to apply app block " + block.height(), outcome);
        }
    }


    private boolean verifyMemberSignature(AppMessage message, long height) {
        if (message == null || !config.chainId().equals(message.getChainId())
                || message.getSender() == null || message.getSender().length != 32) {
            return false;
        }
        String senderHex = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        return group.containsAt(senderHex, height)
                && message.getAuthProof() != null
                && AppMessageSigner.verify(message.getAuthProof(), message.signedBodyBytes(), message.getSender());
    }

    private boolean validBlockProfile(AppBlock block, String source, boolean proposal) {
        if (block == null || block.version() != AppBlock.BLOCK_VERSION) {
            log.warn("{} has unsupported app-block version — rejecting", source);
            return false;
        }
        if (!config.chainId().equals(block.chainId())) {
            log.warn("{} chain identity does not match local app chain '{}' — rejecting",
                    source, config.chainId());
            return false;
        }
        if (block.height() < 1 || block.l1Slot() < 0 || block.timestamp() < 0
                || block.prevHash() == null || block.prevHash().length != 32
                || block.l1BlockHash() == null
                || block.l1Slot() == 0 && block.l1BlockHash().length != 0
                || block.l1Slot() > 0 && block.l1BlockHash().length != 32
                || block.messagesRoot() == null || block.messagesRoot().length != 32
                || block.stateRoot() == null || block.stateRoot().length != 32
                || block.proposer() == null || block.proposer().length != 32
                || block.messages() == null
                || block.messages().size() > config.maxBlockMessages()
                || block.messages().size() > AppChainConfig.MAX_BLOCK_MESSAGES
                || block.cert() == null
                || proposal && (block.cert().scheme() != FinalityCert.SCHEME_ED25519
                || !block.cert().signatures().isEmpty())) {
            log.warn("{} is outside the app-block v1 structural profile — rejecting", source);
            return false;
        }
        Set<String> messageIds = new HashSet<>();
        for (AppMessage message : block.messages()) {
            if (message == null || message.getMessageId() == null
                    || message.getMessageId().length != 32
                    || !messageIds.add(HexUtil.encodeHexString(message.getMessageId()))) {
                log.warn("{} has duplicate or malformed message identities — rejecting", source);
                return false;
            }
            if (ledger.messageHeight(message.getMessageId()).isPresent()) {
                log.warn("{} replays an already-finalized message identity — rejecting", source);
                return false;
            }
        }
        return true;
    }

    private boolean validFinalizedMessageProfile(AppMessage message) {
        if (message == null || message.getVersion() != AppMessage.ENVELOPE_VERSION
                || message.getMessageId() == null || message.getMessageId().length != 32
                || !config.chainId().equals(message.getChainId())
                || message.getTopic() == null || message.getTopic().indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(message.getTopic())
                || message.getTopic().getBytes(StandardCharsets.UTF_8).length
                > AppChainConfig.MAX_TOPIC_BYTES
                || AppChainSystemTopics.isDiffusionOnly(message.getTopic())
                || message.getSender() == null || message.getSender().length != 32
                || message.getSenderSeq() < 0 || message.getExpiresAt() < 0
                || message.getBody() == null
                || message.getBody().length > config.maxMessageBytes()
                || message.getBody().length > AppChainConfig.MAX_MESSAGE_BYTES
                || message.getAuthScheme() != FinalityCert.SCHEME_ED25519
                || message.getAuthProof() == null
                || message.getAuthProof().length
                != AppChainConfig.ED25519_SIGNATURE_BYTES) {
            return false;
        }
        return true;
    }

    private boolean authorizedResultMessage(AppMessage message) {
        return !com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.TOPIC
                .equals(message.getTopic())
                || effectsSettings.resultSignerAllowed(message.getSender());
    }

    /**
     * Catch-up variant of the L1 ref check: certified blocks are already final,
     * so only monotonicity (always) and local hash consistency (when the slot
     * is within our observed window) are enforced. A ref ahead of the local L1
     * view stops the batch — the next catch-up tick retries after L1 advances.
     */
    private boolean verifyCatchUpL1Ref(AppBlock block) {
        if (config.l1StabilityDepth() <= 0 || block.l1Slot() <= 0) {
            return true; // refs unused, or a block from a pre-l1-ref era
        }
        long prevSlot = ledger.block(ledger.tipHeight()).map(AppBlock::l1Slot).orElse(0L);
        if (block.l1Slot() < prevSlot) {
            log.warn("Catch-up block L1 ref moves backwards ({} < {}) at height {} — rejecting",
                    block.l1Slot(), prevSlot, block.height());
            return false;
        }
        L1RefValidator validator = l1RefValidator;
        if (validator == null) {
            return true;
        }
        L1RefVerdict verdict = validator.check(block.l1Slot(), block.l1BlockHash());
        if (verdict == L1RefVerdict.MISMATCH) {
            log.warn("Catch-up block L1 ref (slot {}) does not match our own L1 view at height {} — rejecting",
                    block.l1Slot(), block.height());
            return false;
        }
        if (verdict == L1RefVerdict.AHEAD) {
            log.info("Catch-up block L1 ref (slot {}) ahead of local L1 view — pausing catch-up at height {}",
                    block.l1Slot(), block.height());
            return false;
        }
        return true;
    }

    /**
     * Follower-side verification of proposed {@code ~l1/*} observation
     * messages (008.4 I3.2), consensus-critical and fail-closed: each
     * observation must match this node's OWN recomputation from its L1
     * stream. MISMATCH rejects the proposal; an observation ahead of the
     * local L1 view defers it (same machinery as l1-refs); observations
     * older than the local window are accepted (the certified chain
     * vouches). Without a validator (no observers configured) any {@code
     * ~l1/*} message is rejected — a chain that doesn't observe cannot
     * verify, so it must not finalize observations.
     *
     * @return true to continue proposal processing; false = rejected or deferred
     */
    private boolean verifyProposalObservations(AppMessage envelope, AppBlock block) {
        ObservationValidator validator = observationValidator;
        for (AppMessage message : block.messages()) {
            String topic = message.getTopic() != null ? message.getTopic() : "";
            if (!topic.startsWith(com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation.TOPIC_PREFIX)) {
                continue;
            }
            if (validator == null) {
                log.warn("Proposal at height {} contains observation {} but this node has no "
                        + "observers configured — rejecting (configure the same observers on "
                        + "every member)", block.height(), message.getMessageIdHex());
                return false;
            }
            // ADR 008.4 §3.1 REQUIRED: observation slot <= the block's stable
            // l1-ref slot — a fact may only finalize once it is stability-deep
            // (the app chain never rolls back). Fail-closed on undecodable.
            var observation = com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation
                    .decode(message.getBody());
            if (observation == null || observation.slot() > block.l1Slot()) {
                log.warn("Proposal observation {} at height {} is undecodable or not yet "
                        + "stability-deep (obs slot {} > block l1-ref {}) — rejecting",
                        message.getMessageIdHex(), block.height(),
                        observation != null ? observation.slot() : -1, block.l1Slot());
                deferredProposals.remove(envelope.getMessageIdHex());
                return false;
            }
            L1RefVerdict verdict = validator.check(message);
            switch (verdict) {
                case OK, UNKNOWN -> { /* verified, or below window: chain vouches */ }
                case MISMATCH -> {
                    log.warn("Proposal observation {} does not match our own L1 recomputation at "
                            + "height {} — rejecting (fail-closed)",
                            message.getMessageIdHex(), block.height());
                    deferredProposals.remove(envelope.getMessageIdHex());
                    return false;
                }
                case AHEAD -> {
                    long waitMs = Math.max(config.blockIntervalMs() * 2, 5_000);
                    long firstDeferred = deferredProposals.computeIfAbsent(
                            envelope.getMessageIdHex(), id -> System.currentTimeMillis());
                    if (System.currentTimeMillis() - firstDeferred < waitMs) {
                        log.debug("Proposal observation ahead of local L1 view — deferring height {}",
                                block.height());
                        executor.schedule(() -> doHandleConsensusMessage(envelope), 500,
                                TimeUnit.MILLISECONDS);
                    } else {
                        log.warn("Proposal observation still ahead of local L1 view after {} ms — "
                                + "giving up at height {} (proposer will re-propose)",
                                waitMs, block.height());
                        deferredProposals.remove(envelope.getMessageIdHex());
                    }
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Catch-up variant of the observation check: MISMATCH still rejects
     * (fail-closed); AHEAD pauses the batch until the local L1 advances;
     * UNKNOWN (older than the window — the common case during catch-up)
     * accepts on the certificate.
     */
    private boolean verifyCatchUpObservations(AppBlock block) {
        ObservationValidator validator = observationValidator;
        for (AppMessage message : block.messages()) {
            String topic = message.getTopic() != null ? message.getTopic() : "";
            if (!topic.startsWith(com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation.TOPIC_PREFIX)) {
                continue;
            }
            if (validator == null) {
                log.warn("Catch-up block at height {} contains observation {} but this node has no "
                        + "observers configured — rejecting", block.height(), message.getMessageIdHex());
                return false;
            }
            var observation = com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation
                    .decode(message.getBody());
            if (observation == null || observation.slot() > block.l1Slot()) {
                log.warn("Catch-up observation {} at height {} is undecodable or exceeds the block "
                        + "l1-ref — rejecting", message.getMessageIdHex(), block.height());
                return false;
            }
            L1RefVerdict verdict = validator.check(message);
            if (verdict == L1RefVerdict.MISMATCH) {
                log.warn("Catch-up observation {} does not match our own L1 recomputation at height {} "
                        + "— rejecting", message.getMessageIdHex(), block.height());
                return false;
            }
            if (verdict == L1RefVerdict.AHEAD) {
                log.info("Catch-up observation ahead of local L1 view — pausing catch-up at height {}",
                        block.height());
                return false;
            }
        }
        return true;
    }

    /**
     * Follower-side L1 reference verification (ADR 008.1 I1.3), active when
     * {@code l1.stability-depth > 0}: the proposed {@code (l1Slot, l1BlockHash)}
     * must be monotonic vs the previous block and present in this node's OWN
     * stable L1 window. A ref ahead of the local view defers the proposal
     * (bounded retry — the proposer may be slightly ahead); a hash mismatch or
     * an in-window absent slot is a fabricated/rolled-back ref and is rejected.
     *
     * @return true to continue proposal processing; false = rejected or deferred
     */
    private boolean verifyProposalL1Ref(AppMessage envelope, AppBlock block) {
        if (config.l1StabilityDepth() <= 0) {
            return true; // chain runs without L1 refs
        }
        if (block.l1Slot() <= 0) {
            log.warn("Proposal at height {} carries no L1 ref while l1.stability-depth={} — rejecting",
                    block.height(), config.l1StabilityDepth());
            return false;
        }
        long prevSlot = ledger.block(ledger.tipHeight()).map(AppBlock::l1Slot).orElse(0L);
        if (block.l1Slot() < prevSlot) {
            log.warn("Proposal L1 ref moves backwards ({} < {}) at height {} — rejecting",
                    block.l1Slot(), prevSlot, block.height());
            return false;
        }
        L1RefValidator validator = l1RefValidator;
        L1RefVerdict verdict = validator != null
                ? validator.check(block.l1Slot(), block.l1BlockHash())
                : L1RefVerdict.UNKNOWN;
        switch (verdict) {
            case OK -> deferredProposals.remove(envelope.getMessageIdHex());
            case MISMATCH -> {
                log.warn("Proposal L1 ref (slot {}) does not match our own L1 view at height {} — "
                        + "rejecting (fabricated or rolled-back reference)", block.l1Slot(), block.height());
                deferredProposals.remove(envelope.getMessageIdHex());
                return false;
            }
            case AHEAD -> {
                long waitMs = Math.max(config.blockIntervalMs() * 2, 5_000);
                long firstDeferred = deferredProposals.computeIfAbsent(
                        envelope.getMessageIdHex(), id -> System.currentTimeMillis());
                if (System.currentTimeMillis() - firstDeferred < waitMs) {
                    log.debug("Proposal L1 ref (slot {}) ahead of local L1 view — deferring height {}",
                            block.l1Slot(), block.height());
                    executor.schedule(() -> doHandleConsensusMessage(envelope), 500, TimeUnit.MILLISECONDS);
                } else {
                    log.warn("Proposal L1 ref (slot {}) still ahead of local L1 view after {} ms — "
                            + "giving up at height {} (proposer will re-propose)",
                            block.l1Slot(), waitMs, block.height());
                    deferredProposals.remove(envelope.getMessageIdHex());
                }
                return false;
            }
            case UNKNOWN -> {
                log.warn("Proposal L1 ref (slot {}) is older than our observed L1 window — accepting on "
                        + "monotonicity only (restart window caveat)", block.l1Slot());
                deferredProposals.remove(envelope.getMessageIdHex());
            }
        }
        return true;
    }

    /**
     * Consensus-visible sender-seq rule (ADR 008.1 I1.2, behind
     * {@code message.enforce-sender-seq}): within a block, each sender's seqs
     * must be strictly increasing and stay above the sender's finalized floor
     * as of the parent block. Deterministic — every honest member re-derives
     * the same floors from its own ledger.
     */
    private boolean verifySenderSeqs(AppBlock block, String context) {
        if (!config.enforceSenderSeq()) {
            return true;
        }
        Map<String, Long> senderFloor = new HashMap<>();
        for (AppMessage message : block.messages()) {
            if (message.getSenderSeq() <= 0) {
                log.warn("{} contains message {} without a sender-seq — rejecting (enforcement on)",
                        context, message.getMessageIdHex());
                return false;
            }
            String senderHex = HexUtil.encodeHexString(message.getSender());
            long floor = senderFloor.computeIfAbsent(senderHex,
                    h -> ledger.senderSeq(message.getSender()));
            if (message.getSenderSeq() <= floor) {
                log.warn("{} contains stale/duplicate sender-seq {} from {} (floor {}) — rejecting",
                        context, message.getSenderSeq(), senderHex, floor);
                return false;
            }
            senderFloor.put(senderHex, message.getSenderSeq());
        }
        return true;
    }

    @Override
    public void close() {
        // shutdownNow only closes admission/interrupts the loop; it never
        // waits for interrupt-resistant plugin code. Concurrent callers are
        // serialized so every returning caller has observed the one shutdown
        // request, while the call itself remains bounded.
        ShutdownRequest shutdownRequest;
        synchronized (closeLock) {
            if (closeStarted) {
                return;
            }
            closeStarted = true;
            shutdownRequest = requestExecutorShutdown(executor);
            if (!shutdownRequest.accepted()) {
                // No shutdown request reached the executor. Permit a later
                // owner to retry instead of publishing a false closed state.
                closeStarted = false;
            }
        }
        if (shutdownRequest.failure() != null) {
            log.warn("App-chain engine '{}' shutdown request failed (errorType={})",
                    config.chainId(), callbackFailureType(shutdownRequest.failure()));
            throw propagateLifecycleFailure(
                    shutdownRequest.failure(), "App-chain engine shutdown failed");
        }
    }

    /**
     * Request event-loop shutdown without losing ownership when the preferred
     * interrupting path fails. A successful graceful fallback still fences
     * admission and lets {@link #closeCompletion()} reflect real termination.
     */
    static ShutdownRequest requestExecutorShutdown(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        try {
            executor.shutdownNow();
            return new ShutdownRequest(true, null);
        } catch (Throwable forceFailure) {
            try {
                executor.shutdown();
                return new ShutdownRequest(true, forceFailure);
            } catch (Throwable gracefulFailure) {
                return new ShutdownRequest(false,
                        LifecycleFailures.merge(forceFailure, gracefulFailure));
            }
        }
    }

    record ShutdownRequest(boolean accepted, Throwable failure) {
    }

    private static RuntimeException propagateLifecycleFailure(
            Throwable failure,
            String message
    ) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message, failure);
    }

    /**
     * Completes after every admitted engine task has ended and the final
     * in-flight round has been discarded. It may remain pending after bounded
     * {@link #close()} returns when a plugin callback ignores interruption.
     */
    CompletionStage<Void> closeCompletion() {
        return closeCompletion.minimalCompletionStage();
    }

    private void finishCloseAfterExecutorTermination() {
        Throwable failure = null;
        try {
            // ScheduledThreadPoolExecutor invokes terminated() only after no
            // worker can still touch pendingRound, so cleanup cannot overlap
            // state-machine/sequencer/broadcast callbacks on the event loop.
            discardRound();
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
            log.error("App-chain engine '{}' cleanup failed (errorType={})",
                    config.chainId(), callbackFailureType(cleanupFailure));
        }

        if (failure == null) {
            closeCompletion.complete(null);
            return;
        }

        closeCompletion.completeExceptionally(failure);
        // Preserve process-fatal semantics after publishing the terminal
        // signal. Ordinary cleanup failures are reported through the stage.
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private static String callbackFailureType(Throwable failure) {
        String type = failure.getClass().getName();
        return type.length() <= MAX_CALLBACK_FAILURE_TYPE_CHARS
                ? type : type.substring(0, MAX_CALLBACK_FAILURE_TYPE_CHARS);
    }

    /**
     * Merge a cleanup failure without allowing a containable {@link Error}
     * (for example an assertion or linkage failure from plugin code) to mask
     * an actual JVM termination signal raised while releasing native state.
     */
    private static Throwable mergeCleanupFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    /** Extract the failure retained by ScheduledThreadPoolExecutor's Future wrapper. */
    static Throwable completedTaskFailure(Runnable task, Throwable directFailure) {
        if (directFailure != null) {
            return directFailure;
        }
        if (!(task instanceof Future<?> future) || !future.isDone()) {
            return null;
        }
        try {
            future.get();
            return null;
        } catch (CancellationException ignored) {
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Preserve actual JVM termination signals after publishing a class-only diagnostic. */
    @SuppressWarnings("removal")
    private static void rethrowIfJvmFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
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
        final FxKernel.Result fx;
        private boolean closed;

        AppliedBlock(AppBlock block, WriteBatch batch, FxKernel.Result fx) {
            this.block = block;
            this.batch = batch;
            this.fx = fx;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                batch.close();
            }
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
