package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusContext;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusQuorum;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusDigests;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.state.AuthenticatedStateBackend;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * ADR-036 two-phase app-chain consensus engine. Members independently execute
 * proposals, persist per-view prepare locks, form PreparedQC and commit quorums,
 * and recover through quorum-certified higher views. The engine is a serial
 * event loop and commits each finalized block, state root, and framework
 * cursor update in one RocksDB batch.
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
    private final int timeoutMaxExponent;
    private final long maxFutureViewLead;
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
    private final ConsensusProfileGuard consensusProfileGuard;
    private final StateCommitmentGuard stateCommitmentGuard;
    private final AuthenticatedStateBackend stateBackend;
    private final FxKernel fxKernel;
    private final java.util.Optional<AuthenticatedSnapshotRuntime> authenticatedSnapshots;
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
    /** Mutations that must share the exact finalized-block RocksDB batch. */
    private volatile BiConsumer<AppBlock, WriteBatch> blockCommitHook = (block, batch) -> { };
    private volatile Consumer<AppBlock> blockInFlightHook = block -> { };
    private volatile Consumer<AppBlock> blockPreparedHook = block -> { };
    private volatile BiConsumer<AppBlock, AppStateWriter> frameworkStateHook =
            (block, state) -> { };
    /** Stable L1 reference for proposals; null supplier or null value = no L1 ref (zeros). */
    private volatile Supplier<L1Ref> l1RefSupplier;
    /** Follower-side check of a proposed L1 ref against this node's own L1 view (I1.3). */
    private volatile L1RefValidator l1RefValidator;
    /** Follower-side check of proposed ~l1/* observations (008.4 I3.2); null = accept. */
    private volatile ObservationValidator observationValidator;
    /** Durable system inputs selected ahead of the ordinary, expiring message pool. */
    private volatile MandatoryInputProvider mandatoryInputProvider = reference -> List.of();
    /** Complete-prefix check; unlike per-item validation this detects omission. */
    private volatile ObservationPrefixValidator observationPrefixValidator;
    /** Height-specific identity committed by every v3 proposal. */
    private volatile LongFunction<byte[]> consensusContextProvider = height -> new byte[32];
    private long currentView;
    private byte[] currentJustification = new byte[0];
    private long heightStartedAt = System.currentTimeMillis();
    private final Map<Long, Map<String, CertifiedConsensusCodec.SignedTimeout>> timeoutVotes =
            new HashMap<>();
    private final Set<String> finalityResponses = new HashSet<>();
    /** Fail-closed readiness gate for deterministic external inputs used while voting. */
    private volatile BooleanSupplier votingHealth = () -> true;
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
        L1RefVerdict check(SequencedL1Observation observation, boolean historicalCatchUp);
    }

    interface MandatoryInputProvider {
        List<AppMessage> inputs(L1Ref reference);
    }

    interface ObservationPrefixValidator {
        L1RefVerdict check(List<SequencedL1Observation> observations, long stableL1Slot);
    }

    void setL1RefSupplier(Supplier<L1Ref> supplier) {
        this.l1RefSupplier = supplier;
    }

    void setL1RefValidator(L1RefValidator validator) {
        this.l1RefValidator = validator;
    }

    void setObservationValidator(ObservationValidator validator) {
        this.observationValidator = validator;
    }

    void setMandatoryInputProvider(MandatoryInputProvider provider) {
        this.mandatoryInputProvider = Objects.requireNonNull(provider, "provider");
    }

    void setObservationPrefixValidator(ObservationPrefixValidator validator) {
        this.observationPrefixValidator = validator;
    }

    void setConsensusContextProvider(LongFunction<byte[]> provider) {
        this.consensusContextProvider = Objects.requireNonNull(provider, "provider");
    }

    byte[] configuredConsensusContextDigest(long height, byte[] observerProfileDigest) {
        MemberGroup.Epoch epoch = group.epochAt(height);
        List<byte[]> members = epoch.members().stream()
                .sorted()
                .map(HexUtil::decodeHexString)
                .toList();
        int maximumFaults = Integer.parseInt(config.pluginSettings().getOrDefault(
                "consensus.max-byzantine-members", "0"));
        ConsensusQuorum quorum = new ConsensusQuorum(epoch.members().size(),
                epoch.threshold(), maximumFaults);
        return new ConsensusContext(2, config.chainId(),
                stateBackend.identity().genesisId(), height, quorum, members,
                AppChainConsensusProfileCommitment.digest(
                        consensusProfileGuard.profile()),
                observerProfileDigest).digest();
    }

    void setVotingHealth(BooleanSupplier votingHealth) {
        this.votingHealth = Objects.requireNonNull(votingHealth, "votingHealth");
    }

    void setBlockCommitHook(BiConsumer<AppBlock, WriteBatch> hook) {
        this.blockCommitHook = Objects.requireNonNull(hook, "hook");
    }

    void setBlockInFlightHook(Consumer<AppBlock> hook) {
        this.blockInFlightHook = Objects.requireNonNull(hook, "hook");
    }

    void setBlockPreparedHook(Consumer<AppBlock> hook) {
        this.blockPreparedHook = Objects.requireNonNull(hook, "hook");
    }

    void setFrameworkStateHook(BiConsumer<AppBlock, AppStateWriter> hook) {
        this.frameworkStateHook = Objects.requireNonNull(hook, "hook");
    }

    com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPage
    authenticatedSnapshots(String seriesId, String cursor, int limit) {
        return authenticatedSnapshots.map(runtime -> runtime.list(
                seriesId, cursor, limit, ledger.tipHeight())).orElseThrow(() ->
                new UnsupportedOperationException("Authenticated snapshot catalog is unavailable"));
    }

    Optional<com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1>
    authenticatedSnapshot(String seriesId, long sequence) {
        return authenticatedSnapshots.flatMap(runtime -> runtime.descriptor(
                seriesId, sequence, ledger.tipHeight()));
    }

    Optional<com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1>
    authenticatedSnapshotForAdmin(String seriesId, long sequence) {
        return authenticatedSnapshots.flatMap(runtime -> runtime.descriptorForAdmin(
                seriesId, sequence, ledger.tipHeight()));
    }

    Optional<com.bloxbean.cardano.yano.api.appchain.state.StateProof>
    authenticatedSnapshotStateProof(
            com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1 descriptor,
            byte[] key) {
        return authenticatedSnapshots.flatMap(runtime -> runtime.stateProof(descriptor, key));
    }

    <T> T withAuthenticatedSnapshotProofPermit(Supplier<T> operation) {
        return authenticatedSnapshots.orElseThrow(() -> new UnsupportedOperationException(
                "Authenticated snapshots are disabled")).withProofPermit(operation);
    }

    Optional<com.bloxbean.cardano.yano.api.appchain.state.StateProof>
    authenticatedSnapshotStateProofAdmitted(
            com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1 descriptor,
            byte[] key) {
        return authenticatedSnapshots.flatMap(runtime -> runtime.stateProofAdmitted(descriptor, key));
    }

    Map<String, Object> authenticatedSnapshotStatus() {
        return authenticatedSnapshots.map(runtime -> runtime.status(ledger.tipHeight()))
                .orElse(Map.of("enabled", false));
    }

    void markAuthenticatedSnapshotsDisputed(String reason) {
        authenticatedSnapshots.ifPresent(runtime -> runtime.markDisputed(reason));
    }

    Optional<com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1>
    authenticatedSnapshotRetentionCandidate() {
        return authenticatedSnapshots.flatMap(runtime -> runtime.retentionCandidate(ledger.tipHeight()));
    }

    AuthenticatedSnapshotRuntime authenticatedSnapshotRuntime() {
        return authenticatedSnapshots.orElse(null);
    }

    java.nio.file.Path archiveAuthenticatedSnapshot(
            com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1 descriptor,
            String archivePath) {
        return authenticatedSnapshots.orElseThrow(() -> new UnsupportedOperationException(
                "Authenticated snapshots are disabled")).archive(descriptor, archivePath);
    }

    void restoreAuthenticatedSnapshot(
            com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1 descriptor,
            String archivePath) {
        authenticatedSnapshots.orElseThrow(() -> new UnsupportedOperationException(
                "Authenticated snapshots are disabled")).restore(descriptor, archivePath);
    }

    int evictAuthenticatedSnapshot(
            com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1 descriptor) {
        return authenticatedSnapshots.orElseThrow(() -> new UnsupportedOperationException(
                "Authenticated snapshots are disabled")).evict(descriptor);
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
                WriteBatch::new, resolveConsensus(config));
    }

    /** Normal runtime path verifies the provider context and kernel use one effective profile. */
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
                   EffectsSettings providerEffectsSettings,
                   AppChainConsensusProfile providerProfile) {
        this(config, ledger, pool, stateMachine, signer, group, sequencerMode,
                roundTimeoutMs, maxBlockMessages, maxBlockBytes, broadcast, log,
                WriteBatch::new, resolveConsensus(
                        config, providerEffectsSettings, providerProfile));
    }

    private static ResolvedConsensus resolveConsensus(AppChainConfig config) {
        EffectsSettings settings = EffectsSettings.from(config);
        return new ResolvedConsensus(settings, settings.consensusProfile(config));
    }

    private static ResolvedConsensus resolveConsensus(
            AppChainConfig config,
            EffectsSettings providerEffectsSettings,
            AppChainConsensusProfile providerProfile) {
        Objects.requireNonNull(providerEffectsSettings, "providerEffectsSettings");
        AppChainConsensusProfile normalized = providerEffectsSettings.consensusProfile(config);
        if (!normalized.equals(providerProfile)) {
            throw new IllegalArgumentException(
                    "state-machine context and kernel consensus profiles differ");
        }
        return new ResolvedConsensus(providerEffectsSettings, providerProfile);
    }

    private record ResolvedConsensus(
            EffectsSettings settings, AppChainConsensusProfile profile) {
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
        this(config, ledger, pool, stateMachine, signer, group, sequencerMode,
                roundTimeoutMs, maxBlockMessages, maxBlockBytes, broadcast, log,
                writeBatchFactory, resolveConsensus(config));
    }

    private AppChainEngine(AppChainConfig config,
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
                   Supplier<WriteBatch> writeBatchFactory,
                   ResolvedConsensus resolvedConsensus) {
        this.config = config;
        this.ledger = ledger;
        this.pool = pool;
        this.stateMachine = stateMachine;
        this.signer = signer;
        this.group = group;
        this.sequencerMode = sequencerMode;
        this.roundTimeoutMs = roundTimeoutMs;
        this.timeoutMaxExponent = Integer.parseInt(config.pluginSettings().getOrDefault(
                "consensus.timeout-max-exponent", "5"));
        this.maxFutureViewLead = Long.parseLong(config.pluginSettings().getOrDefault(
                "consensus.max-future-view-lead", "8"));
        if (roundTimeoutMs <= 0 || timeoutMaxExponent < 0 || timeoutMaxExponent > 20
                || maxFutureViewLead < 1 || maxFutureViewLead > 64) {
            throw new IllegalArgumentException("Invalid certified-consensus timeout bounds");
        }
        this.maxBlockMessages = maxBlockMessages;
        this.maxBlockBytes = maxBlockBytes;
        this.proposalMaxBytes = maxBlockBytes - config.finalityCertHeadroomBytes();
        if (proposalMaxBytes <= 0) {
            throw new IllegalArgumentException("block.max-bytes leaves no room for a v1 finality cert");
        }
        this.broadcast = broadcast;
        this.log = log;
        this.writeBatchFactory = Objects.requireNonNull(writeBatchFactory, "writeBatchFactory");
        this.effectsSettings = resolvedConsensus.settings();
        this.consensusProfileGuard = new ConsensusProfileGuard(
                resolvedConsensus.profile());
        this.consensusProfileGuard.verifyRetained(ledger, config.chainId());
        this.stateBackend = ledger.stateBackend();
        this.stateCommitmentGuard = new StateCommitmentGuard(stateBackend.identity());
        this.stateCommitmentGuard.verifyRetained(ledger, config.chainId());
        this.fxKernel = new FxKernel(effectsSettings, consensusProfileGuard);
        AuthenticatedSnapshotSettings snapshotSettings = AuthenticatedSnapshotSettings.from(config);
        this.authenticatedSnapshots = AuthenticatedSnapshotRuntime.create(
                ledger, stateBackend.identity(), snapshotSettings,
                stateMachine.authenticatedSnapshotSeries(),
                stateMachine.authenticatedSnapshotSourceCommitments(),
                Boolean.parseBoolean(config.pluginSettings().getOrDefault(
                        com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity
                                .L1_PROOF_REQUIRED_SETTING, "false")), config.chainId());
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
        long retainedHeight = ledger.metaLong("consensus_current_height", 0);
        if (retainedHeight == ledger.tipHeight() + 1) {
            currentView = ledger.metaLong("consensus_current_view", 0);
            byte[] justification = ledger.metaBytes("consensus_current_justification");
            currentJustification = justification != null ? justification : new byte[0];
        }
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
        status.put("consensusProtocol", "certified-v2");
        status.put("currentView", currentView);
        status.put("roundTimeoutMs", currentRoundTimeoutMs());
        status.put("leader", leaderFor(ledger.tipHeight() + 1, currentView));
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
                        log.warn("Catch-up block exceeds the configured v3 byte profile — "
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
        if (!Arrays.equals(block.consensusContextDigest(),
                consensusContextProvider.apply(block.height()))) {
            log.warn("Catch-up consensus context mismatch at height {} — rejecting",
                    block.height());
            return false;
        }
        if (!validHistoricalJustification(block)) {
            log.warn("Catch-up consensus justification is invalid at height {} — rejecting",
                    block.height());
            return false;
        }
        if (!verifyCatchUpL1Ref(block)) {
            return false;
        }
        AppBlockExecutionContext executionContext = executionContext(block, "Catch-up block");
        if (executionContext == null || !verifyCatchUpObservations(executionContext)) {
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
        if (!verifyCert(block.cert(), block)) {
            log.warn("Catch-up block cert verification FAILED at height {} — rejecting", block.height());
            return false;
        }

        // A certified block supersedes any local in-flight round at this height
        if (pendingRound != null && pendingRound.block.height() == block.height()) {
            discardRound();
        }

        try (AppliedBlock applied = applyBlock(block, executionContext)) {
            if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
                log.warn("Catch-up block state-root mismatch at height {} — rejecting", block.height());
                return false;
            }
            ledger.stageFx(applied.batch, block.height(), applied.fx);
            blockCommitHook.accept(block, applied.batch);
            ledger.commitBlock(block, blockHash, applied.stateCommit, applied.batch,
                    governanceWrites(block));
        }
        pool.remove(block.messages());
        currentView = 0;
        currentJustification = new byte[0];
        heightStartedAt = System.currentTimeMillis();
        timeoutVotes.clear();
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
            if (!votingHealthy()) {
                return;
            }
            if (pendingRound != null) {
                if (System.currentTimeMillis() - pendingRound.startedAt > currentRoundTimeoutMs()) {
                    log.warn("App-chain round at height {} view {} timed out ({} of {} prepares)",
                            pendingRound.block.height(), currentView,
                            pendingRound.prepares.size(), group.threshold());
                    broadcastTimeout(pendingRound.block.height(), currentView + 1);
                    return;
                } else {
                    return; // round in flight
                }
            }

            long height = ledger.tipHeight() + 1;

            // Partial-round recovery (ANY member, any mode — ADR 008.2 §2.3):
            // once we voted at this height our one vote is spent; keep
            // re-gossiping the locked original proposal (+ our vote) until it
            // finalizes, and never VOTE for a competing block.
            Optional<AppLedgerStore.PrepareVoteLock> existingLock =
                    ledger.prepareVoteLock(height);
            if (existingLock.isPresent() && existingLock.get().view() == currentView) {
                regossipLockedProposal(height, existingLock.get().blockHash());
                if (System.currentTimeMillis() - heightStartedAt > currentRoundTimeoutMs()) {
                    broadcastTimeout(height, currentView + 1);
                }
                return;
            }

            boolean localLeader = currentView == 0 && usesCustomSequencerMode()
                    ? sequencerMode.shouldProposeNow(height)
                    : signer.publicKeyHex().equalsIgnoreCase(leaderFor(height, currentView));
            if (!localLeader) {
                if (System.currentTimeMillis() - heightStartedAt > currentRoundTimeoutMs()) {
                    broadcastTimeout(height, currentView + 1);
                }
                return;
            }

            Supplier<L1Ref> refSupplier = l1RefSupplier;
            L1Ref l1Ref = refSupplier != null ? refSupplier.get() : null;
            // A proposer must satisfy the same mandatory L1-reference rule as
            // its followers before it applies or vote-locks a candidate. A
            // freshly restored node may have app messages ready before its
            // process-local stable-L1 window has refilled. Publishing such a
            // candidate creates a permanently invalid partial-round lock that
            // continues to re-gossip after the L1 window becomes available.
            if (config.l1StabilityDepth() > 0 && l1Ref == null) {
                return;
            }
            byte[] prevHash = ledger.tipHash();
            AppBlock candidate = recoveryCandidate(height);
            boolean recoveringPreparedValue = candidate != null;
            List<AppMessage> mandatoryInputs;
            List<AppMessage> candidates;
            long timestamp;
            if (candidate != null) {
                l1Ref = candidate.l1Slot() > 0
                        ? new L1Ref(candidate.l1Slot(), candidate.l1BlockHash()) : null;
                candidates = candidate.messages();
                mandatoryInputs = candidates.stream()
                        .takeWhile(message -> message.getTopic() != null
                        && message.getTopic().startsWith(L1Observation.TOPIC_PREFIX))
                        .toList();
                timestamp = candidate.timestamp();
            } else {
                mandatoryInputs = List.copyOf(mandatoryInputProvider.inputs(l1Ref));
                candidates = selectMessages(height, mandatoryInputs);
                if (candidates.isEmpty()) {
                    return;
                }
                timestamp = System.currentTimeMillis();
                candidate = buildCandidateBlock(height, prevHash, l1Ref, timestamp, candidates);
            }
            // Trim so the serialized block (which a proposal carries whole over
            // the app-message transport) fits block.max-bytes — otherwise the
            // proposal exceeds the transport limit and followers silently drop
            // it, stalling the height. Deferred messages stay pooled for the
            // next block (block-bytes fix).
            if (AppBlockCodec.serialize(candidate).length > proposalMaxBytes) {
                if (recoveringPreparedValue) {
                    throw new IllegalStateException(
                            "PREPARED_VALUE_WITH_NEW_VIEW_EVIDENCE_EXCEEDS_BLOCK_LIMIT");
                }
                candidates = fitToBlockBytes(height, prevHash, l1Ref, timestamp,
                        candidates, mandatoryInputs.size());
                if (candidates.isEmpty()) {
                    log.warn("App-chain '{}': cannot fit even one message under block.max-bytes ({}) "
                            + "at height {} — skipping this round", config.chainId(),
                            proposalMaxBytes, height);
                    return;
                }
                candidate = buildCandidateBlock(height, prevHash, l1Ref, timestamp, candidates);
            }
            if (AppBlockCodec.serialize(candidate).length > proposalMaxBytes) {
                log.error("App-chain '{}' produced a proposal above its v3 byte budget at height {}",
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
            blockInFlightHook.accept(block);
            // Persist before signing: at most one prepare for this height/view.
            ledger.putVoteLock(height, currentView, blockHash);
            byte[] signature = signer.sign(certifiedVoteDigest(
                    CertifiedConsensusCodec.Phase.PREPARE, block));
            round.prepares.put(signer.publicKeyHex(), signature);

            AppMessage proposalEnvelope =
                    broadcast.apply(ConsensusCodec.TOPIC_PROPOSE, AppBlockCodec.serialize(block));
            if (proposalEnvelope != null) {
                // Enables re-gossip of the partial round across timeouts/restarts.
                ledger.putVoteLockEnvelope(height, ConsensusCodec.encodeEnvelope(proposalEnvelope));
            }
            log.info("Proposed app block: height={}, view={}, msgs={}, hash={}",
                    height, currentView, block.messages().size(),
                    HexUtil.encodeHexString(blockHash));
            broadcastCertifiedVote(CertifiedConsensusCodec.Phase.PREPARE, block);
            maybePrepare();
        } catch (Throwable e) {
            Throwable outcome = discardRoundAfterFailure(e);
            log.error("App-chain propose tick failed (errorType={})",
                    callbackFailureType(outcome));
            rethrowIfJvmFatal(outcome);
        }
    }

    private List<AppMessage> selectMessages(long candidateHeight,
                                            List<AppMessage> mandatoryInputs) {
        if (mandatoryInputs.size() > maxBlockMessages) {
            throw new IllegalStateException("OBSERVATION_PREFIX_EXCEEDS_BLOCK_COUNT");
        }
        List<AppMessage> candidates = pool.drainCandidates(
                maxBlockMessages - mandatoryInputs.size(), proposalMaxBytes);
        // L1 observations have one framework-owned durable ingress. Stale
        // preview-era copies in the ordinary pool may never compete with or
        // duplicate the mandatory prefix.
        candidates.removeIf(message -> {
            if (message.getTopic() == null
                    || !message.getTopic().startsWith(L1Observation.TOPIC_PREFIX)) {
                return false;
            }
            pool.remove(List.of(message));
            return true;
        });
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
            AppStateMachine.AdmissionResult result = stateMachine.validateForBlock(
                    m, candidateHeight, new CommittedStateReader());
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
        List<AppMessage> selected = new ArrayList<>(mandatoryInputs.size() + candidates.size());
        selected.addAll(mandatoryInputs);
        selected.addAll(candidates);
        return selected;
    }

    /** Build an unapplied candidate block (state-root placeholder) for the given messages. */
    private AppBlock buildCandidateBlock(long height, byte[] prevHash, L1Ref l1Ref,
                                         long timestamp, List<AppMessage> messages) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                config.chainId(),
                height,
                consensusContextProvider.apply(height),
                currentView,
                prevHash,
                l1Ref != null ? l1Ref.slot() : 0L,
                l1Ref != null ? l1Ref.blockHash() : new byte[0],
                timestamp,
                AppBlockCodec.messagesRoot(messages),
                new byte[32],                    // placeholder until applied
                messages,
                signer.publicKey(),
                currentJustification,
                FinalityCert.empty());
    }

    private AppBlock recoveryCandidate(long height) {
        if (currentView == 0 || currentJustification.length == 0) {
            return null;
        }
        CertifiedConsensusCodec.NewViewCertificate certificate =
                CertifiedConsensusCodec.decodeNewView(currentJustification);
        CertifiedConsensusCodec.QuorumCertificate highest = highestPrepared(certificate);
        if (highest == null) {
            return null;
        }
        byte[] encoded = ledger.metaBytes("consensus_prepared_value_" + height);
        if (encoded == null) {
            return null;
        }
        AppBlock prepared = AppBlockCodec.deserializeCanonical(encoded, proposalMaxBytes);
        if (!Arrays.equals(AppBlockCodec.blockHash(prepared), highest.blockHash())) {
            throw new IllegalStateException("Persisted prepared value does not match new-view QC");
        }
        return new AppBlock(AppBlock.BLOCK_VERSION, prepared.chainId(), prepared.height(),
                prepared.consensusContextDigest(), currentView, prepared.prevHash(),
                prepared.l1Slot(), prepared.l1BlockHash(), prepared.timestamp(),
                prepared.messagesRoot(), prepared.stateRoot(), prepared.messages(),
                signer.publicKey(), currentJustification, FinalityCert.empty());
    }

    /**
     * Drop trailing messages until the serialized block fits {@code maxBlockBytes}.
     * Drops proportionally to the overflow so it converges in a couple of passes;
     * the removed messages remain in the pool for the next block.
     */
    private List<AppMessage> fitToBlockBytes(long height, byte[] prevHash, L1Ref l1Ref,
                                             long timestamp, List<AppMessage> candidates,
                                             int mandatoryCount) {
        List<AppMessage> list = new ArrayList<>(candidates);
        int minimum = Math.max(mandatoryCount, 1);
        while (list.size() > minimum) {
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
            if (mandatoryCount > 0) {
                log.error("App-chain '{}': mandatory L1 observation prefix cannot fit under "
                                + "block.max-bytes ({}) at height {} — quarantining proposal production",
                        config.chainId(), proposalMaxBytes, height);
                return List.of();
            }
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
                case ConsensusCodec.TOPIC_PREPARE -> handlePrepare(message);
                case ConsensusCodec.TOPIC_PREPARED -> handlePrepared(message);
                case ConsensusCodec.TOPIC_COMMIT -> handleCommit(message);
                case ConsensusCodec.TOPIC_TIMEOUT -> handleTimeout(message);
                case ConsensusCodec.TOPIC_NEW_VIEW -> handleNewView(message);
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
            log.warn("Proposal exceeds the v3 proposal byte budget ({} > {}) — rejecting",
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
        if (!votingHealthy()) {
            deferProposal(envelope, block, "external observation source is unhealthy");
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
        if (block.view() > currentView + maxFutureViewLead) {
            log.warn("Proposal at height {} is too far ahead of view {} — rejecting",
                    block.height(), currentView);
            return;
        }
        if (block.view() == 0 && usesCustomSequencerMode()) {
            SequencerMode.ProposalEligibility eligibility =
                    sequencerMode.checkProposal(block.proposer(), block.height());
            if (eligibility == SequencerMode.ProposalEligibility.DEFER) {
                deferProposal(envelope, block, "custom sequencer is not ready");
                return;
            }
            if (eligibility == SequencerMode.ProposalEligibility.REJECT) {
                log.warn("Proposal at height {} is not eligible under custom sequencer mode '{}' "
                        + "— rejecting", block.height(), sequencerMode.id());
                return;
            }
        } else if (!HexUtil.encodeHexString(block.proposer()).equalsIgnoreCase(
                leaderFor(block.height(), block.view()))) {
            log.warn("Proposal at height {} view {} is not from the deterministic leader — "
                    + "rejecting", block.height(), block.view());
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
        if (!Arrays.equals(block.consensusContextDigest(),
                consensusContextProvider.apply(block.height()))) {
            log.warn("Proposal consensus context mismatch at height {} — rejecting",
                    block.height());
            return;
        }
        if (block.view() > currentView && block.justification().length > 0) {
            try {
                CertifiedConsensusCodec.NewViewCertificate certificate =
                        CertifiedConsensusCodec.decodeNewView(block.justification());
                if (validateNewView(certificate)) {
                    installNewView(certificate, block.justification());
                }
            } catch (RuntimeException malformedOrConflicting) {
                log.warn("Proposal carries invalid new-view evidence at height {} — rejecting",
                        block.height());
                return;
            }
        }
        if (block.view() != currentView
                || block.view() == 0 && block.justification().length != 0
                || block.view() > 0 && block.justification().length == 0) {
            log.warn("Proposal view or justification mismatch at height {} — rejecting",
                    block.height());
            return;
        }
        if (block.view() > 0 && !safeRecoveryProposal(block)) {
            log.warn("Proposal at height {} view {} violates the prepared-value rule — rejecting",
                    block.height(), block.view());
            return;
        }
        if (!verifyProposalL1Ref(envelope, block)) {
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
        AppBlock executionBlock = block.withCert(FinalityCert.empty());
        AppBlockExecutionContext executionContext = executionContext(executionBlock, "Proposal");
        if (executionContext == null || !verifyProposalObservations(envelope, executionContext)) {
            return;
        }
        if (!verifySenderSeqs(block, "Proposal")) {
            return;
        }

        byte[] blockHash = AppBlockCodec.blockHash(block);
        Optional<AppLedgerStore.PrepareVoteLock> lock =
                ledger.prepareVoteLock(block.height());
        if (lock.isPresent() && (lock.get().view() > block.view()
                || lock.get().view() == block.view()
                && !Arrays.equals(lock.get().blockHash(), blockHash))) {
            // Never prepare two values in one view or move a durable lock
            // backwards. A certified higher view may replace an unprepared
            // lower-view value under the NewView safe-value rule.
            splitVotesObserved.incrementAndGet();
            log.warn("Refusing prepare at height {} view {} due to durable lock at view {}",
                    block.height(), block.view(), lock.get().view());
            return;
        }

        // Independent re-execution: state root must match byte-for-byte
        if (pendingRound != null) {
            discardRound();
        }
        AppliedBlock applied = applyBlock(executionBlock, executionContext);
        if (!Arrays.equals(applied.block.stateRoot(), block.stateRoot())) {
            try (applied) {
                log.warn("Proposal state-root mismatch at height {} (local {} vs proposed {}) — rejecting",
                        block.height(),
                        HexUtil.encodeHexString(applied.block.stateRoot()),
                        HexUtil.encodeHexString(block.stateRoot()));
            }
            return;
        }

        if (!votingHealthy()) {
            applied.close();
            deferProposal(envelope, block, "external observation source became unhealthy");
            return;
        }

        PendingRound round = publishPendingRound(block, blockHash, applied);
        try {
            blockInFlightHook.accept(block);
            ledger.putVoteLock(block.height(), block.view(), blockHash);
            // Persist the original proposer-signed envelope for partial-round
            // re-gossip (ADR 008.2 §2.3)
            ledger.putVoteLockEnvelope(block.height(), ConsensusCodec.encodeEnvelope(envelope));

            byte[] signature = signer.sign(certifiedVoteDigest(
                    CertifiedConsensusCodec.Phase.PREPARE, block));
            round.prepares.put(signer.publicKeyHex(), signature);
            broadcastCertifiedVote(CertifiedConsensusCodec.Phase.PREPARE, block);
            log.info("Prepared app block: height={}, view={}, hash={}", block.height(),
                    block.view(),
                    HexUtil.encodeHexString(blockHash));
            maybePrepare();
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

    private boolean votingHealthy() {
        try {
            return votingHealth.getAsBoolean();
        } catch (Throwable failure) {
            log.warn("Voting health check failed closed (errorType={})",
                    callbackFailureType(failure));
            rethrowIfJvmFatal(failure);
            return false;
        }
    }

    /**
     * Re-gossip the locked original proposal while it remains valid. Once it
     * expires, quorum timeouts move the height through certified view change.
     */
    private long lastRegossipAt;

    private void regossipLockedProposal(long height, byte[] lockedHash) {
        if (System.currentTimeMillis() - lastRegossipAt < currentRoundTimeoutMs()) {
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
        // Rebuild the pending round; proposal handling re-broadcasts our
        // domain-separated prepare after restoring the persisted lock.
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

    private void broadcastTimeout(long height, long targetView) {
        if (targetView > currentView + maxFutureViewLead) {
            return;
        }
        Map<String, CertifiedConsensusCodec.SignedTimeout> collected =
                timeoutVotes.computeIfAbsent(targetView, ignored -> new LinkedHashMap<>());
        if (collected.containsKey(signer.publicKeyHex())) {
            return;
        }
        byte[] prepared = highestPreparedEvidence(height);
        byte[] context = consensusContextProvider.apply(height);
        byte[] digest = CertifiedConsensusCodec.timeoutSigningDigest(
                height, targetView, context, prepared, new byte[0]);
        CertifiedConsensusCodec.Timeout timeout = new CertifiedConsensusCodec.Timeout(
                height, targetView, context, prepared, new byte[0], signer.sign(digest));
        CertifiedConsensusCodec.SignedTimeout signed =
                new CertifiedConsensusCodec.SignedTimeout(signer.publicKey(), timeout);
        collected.put(signer.publicKeyHex(), signed);
        ledger.metaPutBytesSync("consensus_timeout_" + height + "_" + targetView
                + "_" + signer.publicKeyHex(), CertifiedConsensusCodec.encodeTimeout(timeout));
        broadcast.apply(ConsensusCodec.TOPIC_TIMEOUT,
                CertifiedConsensusCodec.encodeTimeout(timeout));
        maybeInstallNewView(height, targetView);
    }

    private byte[] highestPreparedEvidence(long height) {
        if (pendingRound != null && pendingRound.block.height() == height
                && pendingRound.preparedQc != null) {
            return CertifiedConsensusCodec.encodeQc(pendingRound.preparedQc);
        }
        byte[] retained = ledger.metaBytes("consensus_highest_prepared_" + height);
        if (retained == null) {
            return new byte[0];
        }
        CertifiedConsensusCodec.QuorumCertificate qc =
                CertifiedConsensusCodec.decodeQc(retained);
        if (qc.phase() != CertifiedConsensusCodec.Phase.PREPARE
                || qc.height() != height
                || !Arrays.equals(qc.contextDigest(),
                consensusContextProvider.apply(height))
                || !verifyQc(qc)) {
            throw new IllegalStateException("Invalid persisted prepared certificate");
        }
        return retained;
    }

    private void handleTimeout(AppMessage envelope) {
        CertifiedConsensusCodec.Timeout timeout =
                CertifiedConsensusCodec.decodeTimeout(envelope.getBody());
        long expectedHeight = ledger.tipHeight() + 1;
        if (timeout.height() <= ledger.tipHeight()) {
            respondWithKnownFinality(envelope, timeout);
            return;
        }
        if (timeout.height() != expectedHeight || timeout.targetView() <= currentView
                || timeout.targetView() > currentView + maxFutureViewLead
                || !Arrays.equals(timeout.contextDigest(),
                consensusContextProvider.apply(expectedHeight))) {
            return;
        }
        String voter = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        byte[] digest = CertifiedConsensusCodec.timeoutSigningDigest(timeout.height(),
                timeout.targetView(), timeout.contextDigest(), timeout.preparedQc(),
                timeout.finalityCertificate());
        if (!group.containsAt(voter, timeout.height())
                || !AppMessageSigner.verify(timeout.signature(), digest, envelope.getSender())
                || !validReferencedPreparedQc(timeout)
                || !validReferencedFinality(timeout)) {
            return;
        }
        CertifiedConsensusCodec.QuorumCertificate finality = referencedFinality(timeout);
        if (finality != null && adoptFinality(finality)) {
            return;
        }
        timeoutVotes.computeIfAbsent(timeout.targetView(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(voter,
                        new CertifiedConsensusCodec.SignedTimeout(envelope.getSender(), timeout));
        maybeInstallNewView(timeout.height(), timeout.targetView());
    }

    private void respondWithKnownFinality(AppMessage envelope,
                                          CertifiedConsensusCodec.Timeout timeout) {
        AppBlock block = ledger.block(timeout.height()).orElse(null);
        if (block == null || timeout.targetView() <= block.view()
                || timeout.targetView() > block.view() + maxFutureViewLead
                || !Arrays.equals(timeout.contextDigest(), block.consensusContextDigest())) {
            return;
        }
        String voter = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        byte[] digest = CertifiedConsensusCodec.timeoutSigningDigest(timeout.height(),
                timeout.targetView(), timeout.contextDigest(), timeout.preparedQc(),
                timeout.finalityCertificate());
        if (!group.containsAt(voter, timeout.height())
                || !AppMessageSigner.verify(timeout.signature(), digest, envelope.getSender())
                || !validReferencedPreparedQc(timeout)
                || !validReferencedFinality(timeout)) {
            return;
        }
        String responseKey = timeout.height() + ":" + timeout.targetView();
        if (!finalityResponses.add(responseKey)) {
            return;
        }
        CertifiedConsensusCodec.QuorumCertificate finality = finalityQc(block);
        byte[] encodedFinality = CertifiedConsensusCodec.encodeQc(finality);
        byte[] responseDigest = CertifiedConsensusCodec.timeoutSigningDigest(
                timeout.height(), timeout.targetView(), block.consensusContextDigest(),
                new byte[0], encodedFinality);
        CertifiedConsensusCodec.Timeout response = new CertifiedConsensusCodec.Timeout(
                timeout.height(), timeout.targetView(), block.consensusContextDigest(),
                new byte[0], encodedFinality, signer.sign(responseDigest));
        broadcast.apply(ConsensusCodec.TOPIC_TIMEOUT,
                CertifiedConsensusCodec.encodeTimeout(response));
    }

    private boolean validReferencedPreparedQc(CertifiedConsensusCodec.Timeout timeout) {
        if (timeout.preparedQc().length == 0) {
            return true;
        }
        CertifiedConsensusCodec.QuorumCertificate qc =
                CertifiedConsensusCodec.decodeQc(timeout.preparedQc());
        return qc.phase() == CertifiedConsensusCodec.Phase.PREPARE
                && qc.height() == timeout.height()
                && qc.view() < timeout.targetView()
                && Arrays.equals(qc.contextDigest(), timeout.contextDigest())
                && verifyQc(qc);
    }

    private boolean validReferencedFinality(CertifiedConsensusCodec.Timeout timeout) {
        CertifiedConsensusCodec.QuorumCertificate qc = referencedFinality(timeout);
        return qc == null || qc.phase() == CertifiedConsensusCodec.Phase.COMMIT
                && qc.height() == timeout.height()
                && qc.view() < timeout.targetView()
                && Arrays.equals(qc.contextDigest(), timeout.contextDigest())
                && verifyQc(qc);
    }

    private CertifiedConsensusCodec.QuorumCertificate referencedFinality(
            CertifiedConsensusCodec.Timeout timeout) {
        return timeout.finalityCertificate().length == 0 ? null
                : CertifiedConsensusCodec.decodeQc(timeout.finalityCertificate());
    }

    private CertifiedConsensusCodec.QuorumCertificate finalityQc(AppBlock block) {
        return new CertifiedConsensusCodec.QuorumCertificate(
                CertifiedConsensusCodec.Phase.COMMIT, block.height(), block.view(),
                block.consensusContextDigest(), AppBlockCodec.blockHash(block),
                AppBlockCodec.valueHash(block), block.cert().signatures());
    }

    private boolean adoptFinality(CertifiedConsensusCodec.QuorumCertificate qc) {
        if (pendingRound != null && matchesPending(qc)) {
            commitRound(new FinalityCert(FinalityCert.SCHEME_ED25519, qc.signatures()));
            return true;
        }
        byte[] encoded = ledger.metaBytes("consensus_prepared_value_" + qc.height());
        if (encoded == null) {
            return false;
        }
        AppBlock prepared = AppBlockCodec.deserializeCanonical(encoded, proposalMaxBytes);
        if (!Arrays.equals(AppBlockCodec.blockHash(prepared), qc.blockHash())
                || !Arrays.equals(AppBlockCodec.valueHash(prepared), qc.valueHash())) {
            return false;
        }
        return applyCertifiedBlock(prepared.withCert(
                new FinalityCert(FinalityCert.SCHEME_ED25519, qc.signatures())));
    }

    private void maybeInstallNewView(long height, long targetView) {
        Map<String, CertifiedConsensusCodec.SignedTimeout> collected =
                timeoutVotes.getOrDefault(targetView, Map.of());
        if (collected.size() < group.thresholdAt(height)) {
            return;
        }
        CertifiedConsensusCodec.NewViewCertificate certificate =
                new CertifiedConsensusCodec.NewViewCertificate(height, targetView,
                        consensusContextProvider.apply(height),
                        new ArrayList<>(collected.values()));
        byte[] encoded = CertifiedConsensusCodec.encodeNewView(certificate);
        broadcast.apply(ConsensusCodec.TOPIC_NEW_VIEW, encoded);
        CertifiedConsensusCodec.QuorumCertificate finality = highestFinality(certificate);
        if (finality != null) {
            adoptFinality(finality);
            return;
        }
        installNewView(certificate, encoded);
    }

    private void handleNewView(AppMessage envelope) {
        CertifiedConsensusCodec.NewViewCertificate certificate =
                CertifiedConsensusCodec.decodeNewView(envelope.getBody());
        if (validateNewView(certificate)) {
            CertifiedConsensusCodec.QuorumCertificate finality = highestFinality(certificate);
            if (finality != null) {
                adoptFinality(finality);
                return;
            }
            installNewView(certificate, envelope.getBody());
        }
    }

    private boolean validateNewView(CertifiedConsensusCodec.NewViewCertificate certificate) {
        if (certificate.height() != ledger.tipHeight() + 1
                || certificate.targetView() < currentView
                || certificate.targetView() > currentView + maxFutureViewLead) {
            return false;
        }
        return validateNewViewEvidence(certificate);
    }

    private boolean validateNewViewEvidence(
            CertifiedConsensusCodec.NewViewCertificate certificate) {
        if (certificate.timeouts().size() < group.thresholdAt(certificate.height())
                || !Arrays.equals(certificate.contextDigest(),
                consensusContextProvider.apply(certificate.height()))) {
            return false;
        }
        for (CertifiedConsensusCodec.SignedTimeout signed : certificate.timeouts()) {
            CertifiedConsensusCodec.Timeout timeout = signed.timeout();
            String key = HexUtil.encodeHexString(signed.signer()).toLowerCase(Locale.ROOT);
            byte[] digest = CertifiedConsensusCodec.timeoutSigningDigest(timeout.height(),
                    timeout.targetView(), timeout.contextDigest(), timeout.preparedQc(),
                    timeout.finalityCertificate());
            if (!group.containsAt(key, certificate.height())
                    || timeout.height() != certificate.height()
                    || timeout.targetView() != certificate.targetView()
                    || !Arrays.equals(timeout.contextDigest(), certificate.contextDigest())
                    || !AppMessageSigner.verify(timeout.signature(), digest, signed.signer())
                    || !validReferencedPreparedQc(timeout)
                    || !validReferencedFinality(timeout)) {
                return false;
            }
        }
        return highestFinality(certificate) != null
                || highestPrepared(certificate) != null
                || certificate.timeouts().stream().allMatch(timeout ->
                timeout.timeout().preparedQc().length == 0
                        && timeout.timeout().finalityCertificate().length == 0);
    }

    private boolean validHistoricalJustification(AppBlock block) {
        if (block.view() == 0) {
            return block.justification().length == 0
                    && (usesCustomSequencerMode()
                    || HexUtil.encodeHexString(block.proposer()).equalsIgnoreCase(
                    leaderFor(block.height(), 0)));
        }
        if (block.justification().length == 0
                || !usesCustomSequencerMode()
                && !HexUtil.encodeHexString(block.proposer()).equalsIgnoreCase(
                leaderFor(block.height(), block.view()))) {
            return false;
        }
        try {
            CertifiedConsensusCodec.NewViewCertificate certificate =
                    CertifiedConsensusCodec.decodeNewView(block.justification());
            if (certificate.height() != block.height()
                    || certificate.targetView() != block.view()
                    || !Arrays.equals(certificate.contextDigest(),
                    block.consensusContextDigest())
                    || !validateNewViewEvidence(certificate)
                    || highestFinality(certificate) != null) {
                return false;
            }
            CertifiedConsensusCodec.QuorumCertificate highest = highestPrepared(certificate);
            return highest == null
                    || Arrays.equals(highest.valueHash(), AppBlockCodec.valueHash(block));
        } catch (RuntimeException malformedOrConflicting) {
            return false;
        }
    }

    private boolean safeRecoveryProposal(AppBlock block) {
        CertifiedConsensusCodec.NewViewCertificate certificate;
        try {
            certificate = CertifiedConsensusCodec.decodeNewView(block.justification());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (certificate.height() != block.height()
                || certificate.targetView() != block.view()
                || !validateNewView(certificate)
                || highestFinality(certificate) != null) {
            return false;
        }
        CertifiedConsensusCodec.QuorumCertificate highest = highestPrepared(certificate);
        if (highest == null) {
            return true;
        }
        byte[] encoded = ledger.metaBytes("consensus_prepared_value_" + block.height());
        if (encoded == null) {
            return Arrays.equals(highest.valueHash(), AppBlockCodec.valueHash(block));
        }
        AppBlock prepared = AppBlockCodec.deserializeCanonical(encoded, proposalMaxBytes);
        return Arrays.equals(AppBlockCodec.blockHash(prepared), highest.blockHash())
                && Arrays.equals(highest.valueHash(), AppBlockCodec.valueHash(block));
    }

    private CertifiedConsensusCodec.QuorumCertificate highestPrepared(
            CertifiedConsensusCodec.NewViewCertificate certificate) {
        CertifiedConsensusCodec.QuorumCertificate selected = null;
        for (CertifiedConsensusCodec.SignedTimeout signed : certificate.timeouts()) {
            if (signed.timeout().preparedQc().length == 0) continue;
            CertifiedConsensusCodec.QuorumCertificate candidate =
                    CertifiedConsensusCodec.decodeQc(signed.timeout().preparedQc());
            if (selected == null || candidate.view() > selected.view()) {
                selected = candidate;
            } else if (candidate.view() == selected.view()
                    && !Arrays.equals(candidate.blockHash(), selected.blockHash())) {
                throw new IllegalStateException("CONFLICTING_PREPARED_CERTIFICATES");
            }
        }
        return selected;
    }

    private CertifiedConsensusCodec.QuorumCertificate highestFinality(
            CertifiedConsensusCodec.NewViewCertificate certificate) {
        CertifiedConsensusCodec.QuorumCertificate selected = null;
        for (CertifiedConsensusCodec.SignedTimeout signed : certificate.timeouts()) {
            CertifiedConsensusCodec.QuorumCertificate candidate =
                    referencedFinality(signed.timeout());
            if (candidate == null) {
                continue;
            }
            if (selected == null) {
                selected = candidate;
            } else if (!Arrays.equals(candidate.blockHash(), selected.blockHash())) {
                throw new IllegalStateException("CONFLICTING_FINALITY_CERTIFICATES");
            }
        }
        return selected;
    }

    private void installNewView(CertifiedConsensusCodec.NewViewCertificate certificate,
                                byte[] encoded) {
        if (certificate.targetView() <= currentView) {
            return;
        }
        CertifiedConsensusCodec.QuorumCertificate highest = highestPrepared(certificate);
        discardRound();
        currentView = certificate.targetView();
        currentJustification = encoded.clone();
        heightStartedAt = System.currentTimeMillis();
        ledger.metaPutAll(Map.of("consensus_current_height", certificate.height(),
                        "consensus_current_view", currentView),
                Map.of("consensus_current_justification", encoded));
        log.info("Installed certified app-chain view {} at height {}; leader={}",
                currentView, certificate.height(), leaderFor(certificate.height(), currentView));
    }

    private long currentRoundTimeoutMs() {
        int exponent = Math.toIntExact(Math.min(currentView, timeoutMaxExponent));
        long multiplier = 1L << exponent;
        return roundTimeoutMs > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : roundTimeoutMs * multiplier;
    }

    private String leaderFor(long height, long view) {
        List<String> members = group.membersAt(height).stream().sorted().toList();
        int initial;
        if ("fixed".equals(sequencerMode.id()) && !config.proposerKeyHex().isBlank()) {
            initial = members.indexOf(config.proposerKeyHex().toLowerCase(Locale.ROOT));
            if (initial < 0) throw new IllegalStateException("Fixed proposer is not a member");
        } else {
            byte[] context = consensusContextProvider.apply(height);
            byte[] parent = ledger.tipHash();
            ByteBuffer seed = ByteBuffer.allocate(context.length + parent.length);
            seed.put(context).put(parent);
            byte[] digest = Blake2bUtil.blake2bHash256(seed.array());
            initial = Math.floorMod(ByteBuffer.wrap(digest).getLong(), members.size());
        }
        int viewOffset = (int) (view % members.size());
        return members.get((initial + viewOffset) % members.size());
    }

    private boolean usesCustomSequencerMode() {
        return !"fixed".equals(sequencerMode.id()) && !"rotating".equals(sequencerMode.id());
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

    private void handlePrepare(AppMessage envelope) {
        CertifiedConsensusCodec.Vote vote = CertifiedConsensusCodec.decodeVote(envelope.getBody());
        if (!acceptCertifiedVote(envelope, vote, CertifiedConsensusCodec.Phase.PREPARE)) {
            return;
        }
        String voter = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        pendingRound.prepares.put(voter, vote.signature());
        maybePrepare();
    }

    private void maybePrepare() {
        if (pendingRound == null) {
            return;
        }
        long height = pendingRound.block.height();
        pendingRound.prepares.keySet().removeIf(voter -> !group.containsAt(voter, height));
        if (pendingRound.preparedQc != null
                || pendingRound.prepares.size() < group.thresholdAt(height)) {
            return;
        }
        List<FinalityCert.Signature> signatures = new ArrayList<>();
        for (Map.Entry<String, byte[]> vote : pendingRound.prepares.entrySet()) {
            signatures.add(new FinalityCert.Signature(
                    HexUtil.decodeHexString(vote.getKey()), vote.getValue()));
        }
        CertifiedConsensusCodec.QuorumCertificate qc = new CertifiedConsensusCodec.QuorumCertificate(
                CertifiedConsensusCodec.Phase.PREPARE, height, pendingRound.block.view(),
                pendingRound.block.consensusContextDigest(), pendingRound.blockHash,
                AppBlockCodec.valueHash(pendingRound.block), signatures);
        pendingRound.preparedQc = qc;
        ledger.metaPutBytesSync("consensus_highest_prepared_" + height,
                CertifiedConsensusCodec.encodeQc(qc));
        ledger.metaPutBytesSync("consensus_prepared_value_" + height,
                AppBlockCodec.serialize(pendingRound.block));
        blockPreparedHook.accept(pendingRound.block);
        broadcast.apply(ConsensusCodec.TOPIC_PREPARED, CertifiedConsensusCodec.encodeQc(qc));
        enterCommit(qc);
    }

    private void handlePrepared(AppMessage envelope) {
        if (pendingRound == null) {
            return;
        }
        CertifiedConsensusCodec.QuorumCertificate qc =
                CertifiedConsensusCodec.decodeQc(envelope.getBody());
        if (!matchesPending(qc) || qc.phase() != CertifiedConsensusCodec.Phase.PREPARE
                || !verifyQc(qc)) {
            return;
        }
        pendingRound.preparedQc = qc;
        ledger.metaPutBytesSync("consensus_highest_prepared_" + qc.height(),
                CertifiedConsensusCodec.encodeQc(qc));
        ledger.metaPutBytesSync("consensus_prepared_value_" + qc.height(),
                AppBlockCodec.serialize(pendingRound.block));
        blockPreparedHook.accept(pendingRound.block);
        enterCommit(qc);
    }

    private void enterCommit(CertifiedConsensusCodec.QuorumCertificate qc) {
        PendingRound round = pendingRound;
        if (round == null || round.commits.containsKey(signer.publicKeyHex())) {
            return;
        }
        byte[] signature = signer.sign(certifiedVoteDigest(
                CertifiedConsensusCodec.Phase.COMMIT, round.block));
        ledger.metaPutBytesSync("consensus_commit_vote_" + round.block.height()
                + "_" + round.block.view(), round.blockHash);
        round.commits.put(signer.publicKeyHex(), signature);
        broadcastCertifiedVote(CertifiedConsensusCodec.Phase.COMMIT, round.block);
        maybeFinalize();
    }

    private void handleCommit(AppMessage envelope) {
        CertifiedConsensusCodec.Vote vote = CertifiedConsensusCodec.decodeVote(envelope.getBody());
        if (!acceptCertifiedVote(envelope, vote, CertifiedConsensusCodec.Phase.COMMIT)
                || pendingRound.preparedQc == null) {
            return;
        }
        String voter = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        pendingRound.commits.put(voter, vote.signature());
        maybeFinalize();
    }

    private void maybeFinalize() {
        if (pendingRound == null || pendingRound.preparedQc == null) {
            return;
        }
        long height = pendingRound.block.height();
        pendingRound.commits.keySet().removeIf(voter -> !group.containsAt(voter, height));
        if (pendingRound.commits.size() < group.thresholdAt(height)) {
            return;
        }
        List<FinalityCert.Signature> signatures = new ArrayList<>();
        for (Map.Entry<String, byte[]> vote : pendingRound.commits.entrySet()) {
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

    private boolean acceptCertifiedVote(AppMessage envelope,
                                        CertifiedConsensusCodec.Vote vote,
                                        CertifiedConsensusCodec.Phase phase) {
        PendingRound round = pendingRound;
        if (round == null || vote.phase() != phase
                || vote.height() != round.block.height()
                || vote.view() != round.block.view()
                || !Arrays.equals(vote.contextDigest(), round.block.consensusContextDigest())
                || !Arrays.equals(vote.blockHash(), round.blockHash)) {
            return false;
        }
        String voter = HexUtil.encodeHexString(envelope.getSender()).toLowerCase(Locale.ROOT);
        return group.containsAt(voter, vote.height())
                && AppMessageSigner.verify(vote.signature(),
                certifiedVoteDigest(phase, round.block), envelope.getSender());
    }

    private boolean matchesPending(CertifiedConsensusCodec.QuorumCertificate qc) {
        PendingRound round = pendingRound;
        return round != null && qc.height() == round.block.height()
                && qc.view() == round.block.view()
                && Arrays.equals(qc.contextDigest(), round.block.consensusContextDigest())
                && Arrays.equals(qc.blockHash(), round.blockHash);
    }

    private boolean verifyQc(CertifiedConsensusCodec.QuorumCertificate qc) {
        if (qc.signatures().size() < group.thresholdAt(qc.height())) {
            return false;
        }
        byte[] digest = CertifiedConsensusCodec.signingDigest(qc.phase(), qc.height(),
                qc.view(), qc.contextDigest(), qc.blockHash());
        return qc.signatures().stream().allMatch(signature -> {
            String key = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            return group.containsAt(key, qc.height())
                    && AppMessageSigner.verify(signature.signature(), digest, signature.signer());
        });
    }

    private byte[] certifiedVoteDigest(CertifiedConsensusCodec.Phase phase, AppBlock block) {
        return CertifiedConsensusCodec.signingDigest(phase, block.height(), block.view(),
                block.consensusContextDigest(), AppBlockCodec.blockHash(block));
    }

    static byte[] commitDigest(AppBlock block) {
        return ConsensusDigests.commit(block);
    }

    private void broadcastCertifiedVote(CertifiedConsensusCodec.Phase phase, AppBlock block) {
        byte[] digest = certifiedVoteDigest(phase, block);
        byte[] body = CertifiedConsensusCodec.encodeVote(new CertifiedConsensusCodec.Vote(
                phase, block.height(), block.view(), block.consensusContextDigest(),
                AppBlockCodec.blockHash(block), signer.sign(digest)));
        broadcast.apply(phase == CertifiedConsensusCodec.Phase.PREPARE
                ? ConsensusCodec.TOPIC_PREPARE : ConsensusCodec.TOPIC_COMMIT, body);
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
        if (!verifyCert(cert, pendingRound.block)) {
            log.warn("Cert verification FAILED for height {} — rejecting", notice.height());
            return;
        }
        commitRound(cert);
    }

    /** Verifies threshold, member uniqueness and every signature. Never trust-by-mode. */
    private boolean verifyCert(FinalityCert cert, AppBlock block) {
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
            if (!group.containsAt(signerHex, block.height()) || !seen.add(signerHex)) {
                return false;
            }
            if (!AppMessageSigner.verify(signature.signature(),
                    commitDigest(block), signature.signer())) {
                return false;
            }
            valid++;
        }
        return valid >= group.thresholdAt(block.height());
    }

    private void commitRound(FinalityCert cert) {
        PendingRound round = pendingRound;
        AppBlock finalBlock = round.block.withCert(cert);
        if (AppBlockCodec.serialize(finalBlock).length > maxBlockBytes) {
            throw new IllegalStateException("Finalized app block exceeds block.max-bytes after cert");
        }
        pendingRound = null;
        deferredProposals.clear(); // height advances — held l1-ref deferrals are moot
        currentView = 0;
        currentJustification = new byte[0];
        heightStartedAt = System.currentTimeMillis();
        timeoutVotes.clear();
        try (AppliedBlock applied = round.applied) {
            ledger.stageFx(applied.batch, finalBlock.height(), applied.fx);
            blockCommitHook.accept(finalBlock, applied.batch);
            ledger.commitBlock(finalBlock, round.blockHash, applied.stateCommit, applied.batch,
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
     * Runs the state machine over a side-effect-free backend candidate and
     * returns a frozen prepared commit plus the block's post-state root.
     * Nothing enters the shared WriteBatch until finality; discarding the
     * prepared commit leaves the database unchanged.
     */
    private AppliedBlock applyBlock(AppBlock block) {
        return applyBlock(block, AppBlockExecutionContext.fromValidatedBlock(block));
    }

    private AppliedBlock applyBlock(AppBlock block, AppBlockExecutionContext executionContext) {
        WriteBatch batch = Objects.requireNonNull(
                writeBatchFactory.get(), "writeBatchFactory returned null");
        CandidateState candidate = null;
        StagedStateCommit stateCommit = null;
        try {
            long committedHeight = ledger.tipHeight();
            byte[] committedRoot = ledger.stateRoot();
            byte[] baseRoot = committedRoot != null ? committedRoot : new byte[32];
            candidate = stateBackend.beginCandidate(
                    committedHeight, baseRoot, block.height());
            stateCommitmentGuard.apply(block.height(), candidate);
            FxKernel.Result[] fxResult = new FxKernel.Result[1];
            AuthenticatedSnapshotRuntime.BlockSession snapshotSession = authenticatedSnapshots.isPresent()
                    ? authenticatedSnapshots.orElseThrow().beginBlock(candidate) : null;
            AppStateWriter machineState = snapshotSession != null ? snapshotSession.writer() : candidate;
            fxResult[0] = fxKernel.apply(stateMachine, executionContext, machineState, fxReader);
            if (snapshotSession != null) {
                snapshotSession.execute(batch, block.height());
            }
            frameworkStateHook.accept(block, candidate);
            var prepared = candidate.prepare();
            if (!(prepared instanceof StagedStateCommit staged)) {
                prepared.close();
                throw new IllegalStateException(
                        "authenticated-state backend cannot join the ledger WriteBatch");
            }
            stateCommit = staged;
            byte[] effectiveRoot = stateCommit.stateRoot();
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.consensusContextDigest(), block.view(), block.prevHash(),
                    block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.justification(), block.cert());
            return new AppliedBlock(applied, stateCommit, batch, fxResult[0]);
        } catch (Throwable failure) {
            Throwable outcome = failure;
            if (stateCommit != null) {
                try {
                    stateCommit.close();
                } catch (Throwable cleanupFailure) {
                    outcome = mergeCleanupFailure(outcome, cleanupFailure);
                }
            } else if (candidate != null) {
                try {
                    candidate.discard();
                } catch (Throwable cleanupFailure) {
                    outcome = mergeCleanupFailure(outcome, cleanupFailure);
                }
            }
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
                || block.consensusContextDigest() == null
                || block.consensusContextDigest().length != 32
                || block.view() < 0
                || block.l1BlockHash() == null
                || block.l1Slot() == 0 && block.l1BlockHash().length != 0
                || block.l1Slot() > 0 && block.l1BlockHash().length != 32
                || block.messagesRoot() == null || block.messagesRoot().length != 32
                || block.stateRoot() == null || block.stateRoot().length != 32
                || block.proposer() == null || block.proposer().length != 32
                || block.justification() == null
                || block.justification().length > CertifiedConsensusCodec.MAX_NEW_VIEW_BYTES
                || block.messages() == null
                || block.messages().size() > config.maxBlockMessages()
                || block.messages().size() > AppChainConfig.MAX_BLOCK_MESSAGES
                || block.cert() == null
                || proposal && (block.cert().scheme() != FinalityCert.SCHEME_ED25519
                || !block.cert().signatures().isEmpty())) {
            log.warn("{} is outside the app-block v3 structural profile — rejecting", source);
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
    private boolean verifyProposalObservations(
            AppMessage envelope,
            AppBlockExecutionContext executionContext
    ) {
        AppBlock block = executionContext.block();
        ObservationValidator validator = observationValidator;
        List<SequencedL1Observation> observations = executionContext.l1Observations();
        for (int index = 0; index < observations.size(); index++) {
            if (observations.get(index).originalMessageIndex() != index) {
                log.warn("Proposal at height {} does not place all L1 observations in the "
                        + "mandatory leading prefix — rejecting", block.height());
                return false;
            }
        }
        ObservationPrefixValidator prefixValidator = observationPrefixValidator;
        if (prefixValidator != null) {
            L1RefVerdict prefixVerdict = prefixValidator.check(observations, block.l1Slot());
            if (prefixVerdict != L1RefVerdict.OK) {
                log.warn("Proposal at height {} has an incomplete or unverifiable mandatory "
                                + "L1 observation prefix ({}) — not voting",
                        block.height(), prefixVerdict);
                return false;
            }
        }
        Set<String> observationIdentities = new HashSet<>();
        for (SequencedL1Observation sequenced : observations) {
            AppMessage message = block.messages().get(sequenced.originalMessageIndex());
            if (validator == null) {
                log.warn("Proposal at height {} contains observation {} but this node has no "
                        + "observers configured — rejecting (configure the same observers on "
                        + "every member)", block.height(), message.getMessageIdHex());
                return false;
            }
            // ADR 008.4 §3.1 REQUIRED: observation slot <= the block's stable
            // l1-ref slot — a fact may only finalize once it is stability-deep
            // (the app chain never rolls back). Fail-closed on undecodable.
            var observation = sequenced.observation();
            String observationIdentity = HexUtil.encodeHexString(
                    com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                            observation.encode()));
            if (!observationIdentities.add(observationIdentity)) {
                log.warn("Proposal at height {} repeats the same L1 observation — rejecting",
                        block.height());
                return false;
            }
            if (observation.slot() > block.l1Slot()) {
                log.warn("Proposal observation {} at height {} is undecodable or not yet "
                        + "stability-deep (obs slot {} > block l1-ref {}) — rejecting",
                        message.getMessageIdHex(), block.height(),
                        observation != null ? observation.slot() : -1, block.l1Slot());
                deferredProposals.remove(envelope.getMessageIdHex());
                return false;
            }
            L1RefVerdict verdict = validator.check(sequenced, false);
            switch (verdict) {
                case OK -> { /* independently verified */ }
                case UNKNOWN -> {
                    log.warn("Proposal observation {} is outside the independently verified "
                                    + "live window at height {} — not voting",
                            message.getMessageIdHex(), block.height());
                    return false;
                }
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
    private boolean verifyCatchUpObservations(AppBlockExecutionContext executionContext) {
        AppBlock block = executionContext.block();
        ObservationValidator validator = observationValidator;
        for (SequencedL1Observation sequenced : executionContext.l1Observations()) {
            AppMessage message = block.messages().get(sequenced.originalMessageIndex());
            if (validator == null) {
                log.warn("Catch-up block at height {} contains observation {} but this node has no "
                        + "observers configured — rejecting", block.height(), message.getMessageIdHex());
                return false;
            }
            var observation = sequenced.observation();
            if (observation.slot() > block.l1Slot()) {
                log.warn("Catch-up observation {} at height {} is undecodable or exceeds the block "
                        + "l1-ref — rejecting", message.getMessageIdHex(), block.height());
                return false;
            }
            L1RefVerdict verdict = validator.check(sequenced, true);
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

    private AppBlockExecutionContext executionContext(AppBlock block, String source) {
        try {
            return AppBlockExecutionContext.fromValidatedBlock(block);
        } catch (IllegalArgumentException failure) {
            log.warn("{} at height {} contains an invalid L1 observation — rejecting: {}",
                    source, block.height(), failure.getMessage());
            return null;
        }
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
                if (block.view() == 0) {
                    log.warn("Proposal L1 ref (slot {}) is outside our independently verified "
                            + "live window — not voting", block.l1Slot());
                    return false;
                }
                // A recovery proposal must separately carry a fully verified
                // PreparedQC and the byte-identical prepared application value.
                log.info("Recovery proposal L1 ref is outside the live window; relying on "
                        + "the verified prepared certificate at height {}", block.height());
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
        Throwable diagnostic = failure;
        for (int depth = 0; depth < 16; depth++) {
            Throwable cause = diagnostic.getCause();
            if (cause == null || cause == diagnostic) {
                break;
            }
            diagnostic = cause;
        }
        String type = diagnostic.getClass().getName();
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
        final Map<String, byte[]> prepares = new LinkedHashMap<>();
        final Map<String, byte[]> commits = new LinkedHashMap<>();
        CertifiedConsensusCodec.QuorumCertificate preparedQc;
        final long startedAt = System.currentTimeMillis();

        PendingRound(AppBlock block, byte[] blockHash, AppliedBlock applied) {
            this.block = block;
            this.blockHash = blockHash;
            this.applied = applied;
        }
    }

    private static final class AppliedBlock implements AutoCloseable {
        final AppBlock block;
        final StagedStateCommit stateCommit;
        final WriteBatch batch;
        final FxKernel.Result fx;
        private boolean closed;

        AppliedBlock(AppBlock block, StagedStateCommit stateCommit,
                     WriteBatch batch, FxKernel.Result fx) {
            this.block = block;
            this.stateCommit = stateCommit;
            this.batch = batch;
            this.fx = fx;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Throwable failure = null;
                try {
                    stateCommit.close();
                } catch (Throwable closeFailure) {
                    failure = closeFailure;
                }
                try {
                    batch.close();
                } catch (Throwable closeFailure) {
                    failure = mergeCleanupFailure(failure, closeFailure);
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure != null) {
                    throw new IllegalStateException("Failed to close applied app block", failure);
                }
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

        @Override
        public long committedHeight() {
            return ledger.tipHeight();
        }
    }
}
