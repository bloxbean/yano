package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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
    private final MemberGroup group;
    private final SequencerMode sequencerMode;
    private final long roundTimeoutMs;
    /** Partial rounds observed competing with our lock (008.2 §0 residual case). */
    private final java.util.concurrent.atomic.AtomicLong splitVotesObserved =
            new java.util.concurrent.atomic.AtomicLong();
    private final int maxBlockMessages;
    private final long maxBlockBytes;
    /** Sends a body on a system topic to the group (via the subsystem's diffusion). */
    private final BiFunction<String, byte[], AppMessage> broadcast;
    private final Logger log;

    private final ScheduledExecutorService executor;

    /** In-flight round at height tip+1 (proposer and follower views). */
    private PendingRound pendingRound;

    private volatile BiConsumer<AppBlock, byte[]> onBlockFinalized;
    /** Stable L1 reference for proposals; null supplier or null value = no L1 ref (zeros). */
    private volatile java.util.function.Supplier<L1Ref> l1RefSupplier;
    /** Follower-side check of a proposed L1 ref against this node's own L1 view (I1.3). */
    private volatile L1RefValidator l1RefValidator;
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

    void setL1RefSupplier(java.util.function.Supplier<L1Ref> supplier) {
        this.l1RefSupplier = supplier;
    }

    void setL1RefValidator(L1RefValidator validator) {
        this.l1RefValidator = validator;
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
        this.broadcast = broadcast;
        this.log = log;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-chain-engine-" + config.chainId());
            t.setDaemon(true);
            return t;
        });
        stateMachine.init(new CommittedStateReader(), new AppChainInfo(
                config.chainId(), signer.publicKeyHex(), group.size()));
    }

    void setOnBlockFinalized(BiConsumer<AppBlock, byte[]> callback) {
        this.onBlockFinalized = callback;
    }

    /** Mode-specific observability (window/proposer/etc.). */
    Map<String, Object> sequencerStatus() {
        return sequencerMode.status();
    }

    long splitVotesObserved() {
        return splitVotesObserved.get();
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
        for (AppMessage message : block.messages()) {
            // No TTL check here: these messages were finalized before expiry
            if (!message.hasValidMessageId() || !verifyMemberSignature(message, block.height())) {
                log.warn("Catch-up block contains invalid message {} — rejecting",
                        message.getMessageIdHex());
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

        AppliedBlock applied = applyBlock(block);
        if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
            log.warn("Catch-up block state-root mismatch at height {} — rejecting", block.height());
            applied.close();
            return false;
        }
        ledger.commitBlock(block, blockHash, block.stateRoot(), applied.batch,
                governanceWrites(block));
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
            // finalizes, and never propose a competing block.
            Optional<byte[]> existingLock = ledger.voteLock(height);
            if (existingLock.isPresent()) {
                regossipLockedProposal(height, existingLock.get());
                return;
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
            ledger.putVoteLock(height, blockHash);

            byte[] selfSignature = signer.sign(blockHash);
            PendingRound round = new PendingRound(block, blockHash, applied);
            round.votes.put(signer.publicKeyHex(), selfSignature);
            pendingRound = round;

            AppMessage proposalEnvelope =
                    broadcast.apply(ConsensusCodec.TOPIC_PROPOSE, AppBlockCodec.serialize(block));
            if (proposalEnvelope != null) {
                // Enables re-gossip of the partial round across timeouts/restarts
                ledger.putVoteLockEnvelope(height, ConsensusCodec.encodeEnvelope(proposalEnvelope));
            }
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
        AppBlock block = AppBlockCodec.deserialize(envelope.getBody());
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
        // Every message inside the block must be a valid, member-signed envelope
        long now = System.currentTimeMillis() / 1000;
        for (AppMessage message : block.messages()) {
            if (!message.hasValidMessageId() || message.isExpired(now)
                    || !verifyMemberSignature(message, block.height())) {
                log.warn("Proposal contains invalid message {} — rejecting block",
                        message.getMessageIdHex());
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
            log.warn("Proposal state-root mismatch at height {} (local {} vs proposed {}) — rejecting",
                    block.height(),
                    HexUtil.encodeHexString(applied.block.stateRoot()),
                    HexUtil.encodeHexString(block.stateRoot()));
            applied.close();
            return;
        }

        ledger.putVoteLock(block.height(), blockHash);
        // Persist the original proposer-signed envelope for partial-round
        // re-gossip (ADR 008.2 §2.3)
        ledger.putVoteLockEnvelope(block.height(), ConsensusCodec.encodeEnvelope(envelope));
        pendingRound = new PendingRound(block, blockHash, applied);

        byte[] signature = signer.sign(blockHash);
        // Record our own vote locally too — any member holding the round may
        // aggregate to a cert (dead proposers can't sink collected votes)
        pendingRound.votes.put(signer.publicKeyHex(), signature);
        broadcast.apply(ConsensusCodec.TOPIC_VOTE,
                ConsensusCodec.encodeVote(block.height(), blockHash, signature));
        log.info("Voted for app block: height={}, hash={}", block.height(),
                HexUtil.encodeHexString(blockHash));
        maybeFinalize();
    }

    /**
     * Re-gossip the locked original proposal (+ our vote) until the height
     * finalizes (ADR 008.2 §2.3) — rate-limited to once per round timeout.
     * Only works while the original envelope is TTL-valid; past that the
     * partial round is a runbook case (documented residual).
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
        FinalityCert cert = AppBlockCodec.deserializeCert(notice.certBytes());
        if (!verifyCert(cert, pendingRound.blockHash, pendingRound.block.height())) {
            log.warn("Cert verification FAILED for height {} — rejecting", notice.height());
            return;
        }
        commitRound(cert);
    }

    /** Verifies threshold, member uniqueness and every signature. Never trust-by-mode. */
    private boolean verifyCert(FinalityCert cert, byte[] blockHash, long height) {
        if (cert.scheme() != FinalityCert.SCHEME_ED25519) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (FinalityCert.Signature signature : cert.signatures()) {
            String signerHex = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            if (!group.containsAt(signerHex, height) || !seen.add(signerHex)) {
                continue;
            }
            if (AppMessageSigner.verify(signature.signature(), blockHash, signature.signer())) {
                valid++;
            }
        }
        return valid >= group.thresholdAt(height);
    }

    private void commitRound(FinalityCert cert) {
        PendingRound round = pendingRound;
        pendingRound = null;
        deferredProposals.clear(); // height advances — held l1-ref deferrals are moot
        AppBlock finalBlock = round.block.withCert(cert);
        ledger.commitBlock(finalBlock, round.blockHash, finalBlock.stateRoot(), round.applied.batch,
                governanceWrites(finalBlock));
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

    private boolean verifyMemberSignature(AppMessage message, long height) {
        String senderHex = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
        return group.containsAt(senderHex, height)
                && message.getAuthProof() != null
                && AppMessageSigner.verify(message.getAuthProof(), message.signedBodyBytes(), message.getSender());
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
