package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgValidator;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectView;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.events.AppBlockFinalizedEvent;
import com.bloxbean.cardano.yano.api.events.AppMessageReceivedEvent;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.plugins.LegacyServiceLoaderProviderRegistry;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-chain subsystem: authenticated diffusion (M1) + sequenced durable ledger
 * with MPF state commitment (M2) per ADR app-layer/005.
 * <p>
 * Message routing: verified inbound envelopes on system topics
 * ({@code ~consensus/...}) feed the sequencer engine; ordinary topics feed the
 * message pool for inclusion in the next app block. Everything verified is
 * relayed onward so consensus and app traffic both reach non-adjacent members.
 */
public final class AppChainSubsystem implements Subsystem, AppChainGateway {
    private static final int RECENT_MESSAGES_LIMIT = 1000;
    private static final int SEEN_IDS_HARD_CAP = 200_000;
    private static final long CONNECT_INTERVAL_SECONDS = 5;
    private static final long KEEPALIVE_INTERVAL_SECONDS = 20;
    private static final int QUERY_QUEUE_CAPACITY = 16;
    private static final long QUERY_TIMEOUT_SECONDS = 2;
    private static final int MAX_QUERY_PATH_CHARACTERS = AppQueryPath.MAX_LENGTH;
    private static final int MAX_QUERY_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_QUERY_RESULT_BYTES = 1024 * 1024;
    private static final String SYSTEM_TOPIC_PREFIX = "~";

    private final AppChainConfig config;
    private final long protocolMagic;
    private final EventBus eventBus;
    private final Logger log;
    private final PluginProviderRegistry pluginProviders;
    /** Non-null only for the compatibility constructor that created the registry. */
    private final AutoCloseable ownedPluginProviders;
    private final AtomicBoolean permanentlyClosed = new AtomicBoolean(false);
    private final CompletableFuture<Void> permanentCloseCompletion = new CompletableFuture<>();

    private final com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer;
    private final MemberGroup group;
    /**
     * Own envelope sequence. Seeded from the wall clock so a restart never
     * reuses a lower seq (ADR 008.1 I1.2 — replay floor would reject it);
     * additionally floored by the persisted last-finalized value at start()
     * on ledger nodes. Gaps are meaningless by design.
     */
    private final AtomicLong senderSeq = new AtomicLong(System.currentTimeMillis());

    private final SeenMessageIds seenMessageIds;
    private final ConcurrentLinkedDeque<ReceivedAppMessage> recentMessages = new ConcurrentLinkedDeque<>();
    // Engine callbacks and admitted API calls can still be iterating while
    // stop retires the generation. Peer links themselves reject work after
    // shutdown; snapshot iteration prevents teardown from invalidating an
    // in-flight relay/status traversal.
    private final List<AppPeerLink> peerClients =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Shared L1-session transport (ADR 005 M1 unification); null = dedicated dials. */
    private volatile SharedAppTransport sharedTransport;
    private volatile java.util.Set<String> sharedEndpoints = java.util.Set.of();

    // M2: sequenced ledger (present when sequencing is enabled)
    private final AppMsgPool pool;
    private volatile AppLedgerStore ledger;
    private volatile AppChainEngine engine;
    private final AppStateMachine stateMachine;
    /** Consensus mode (008.2): fixed | rotating | plugin-provided; null = diffusion-only. */
    private final com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode sequencerMode;
    private final String ledgerPath;

    // M3: L1 anchoring + stable L1 reference tracking
    private volatile AnchorService anchorService;
    /** Script anchors (008.4). Every ledger member gets one (co-sign verifier);
     *  only the anchor.enabled node runs it in leader mode. */
    private volatile ScriptAnchorService scriptAnchorService;
    /** L1 observations (008.4 I3.2): all members recompute; the scheduled
     *  proposer injects. Null when no observers are configured. */
    private volatile L1ObservationService observationService;
    private volatile java.util.function.Function<byte[], String> txSubmitter;
    private volatile java.util.function.Supplier<com.bloxbean.cardano.yano.api.utxo.UtxoState> utxoStateSupplier;
    private final java.util.concurrent.ConcurrentLinkedDeque<AppChainEngine.L1Ref> recentL1Points =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> eventSubscriptions =
            new ArrayList<>();

    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong relayedCount = new AtomicLong();
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();

    // Drop accounting by reason (ADR 008.1 I1.1) — surfaced in status() and metrics
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> dropCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void countDrop(String reason) {
        dropCounters.computeIfAbsent(reason, r -> new AtomicLong()).incrementAndGet();
    }

    // M4: catch-up + stall detection
    private static final long STALL_WINDOW_MS = 60_000;
    private volatile long bestPeerTip;
    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile long lastStallEventAt;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * Host lifecycle calls are serialized through an explicit transition
     * protocol. The short lock only claims/publishes state; plugin callbacks,
     * scheduler shutdown, RocksDB work and completion callbacks always run
     * after it has been released.
     */
    private final Object lifecycleTransitionLock = new Object();
    private volatile LifecycleState lifecycleState = LifecycleState.STOPPED;
    private Thread lifecycleTransitionOwner;
    private CompletableFuture<Void> lifecycleTransitionCompletion =
            CompletableFuture.completedFuture(null);
    /**
     * Threads created by a lifecycle callback inherit this marker. They must
     * never synchronously re-enter host lifecycle and wait for the transition
     * that created them. Read-only status/state access remains allowed.
     */
    private final InheritableThreadLocal<Boolean> lifecycleTransitionLineage =
            new InheritableThreadLocal<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledExecutorService sinkScheduler;
    private volatile ScheduledExecutorService fxScheduler;
    private volatile QueryLane queryLane;
    private volatile EffectRuntime effectRuntime;
    /**
     * Per-start admission fence for callers that may retain any resource owned
     * by the current app-chain generation.  stop() seals the fence before it
     * starts dismantling that generation; ledger and plugin-provider cleanup
     * are deferred until every operation admitted before the seal has left.
     *
     * <p>The fence is deliberately re-entrant per thread.  An admitted public
     * operation may call another fenced helper after stop has sealed new root
     * admissions and must still be allowed to finish against its generation.</p>
     */
    private final GenerationUseGate generationUseGate = new GenerationUseGate();
    /**
     * A stop may return while an interrupt-ignoring sink or engine callback
     * drains. A same-instance restart is rejected until callbacks, ledger and
     * product shutdown have all completed.
     */
    private volatile CompletableFuture<Void> deferredRuntimeShutdown =
            CompletableFuture.completedFuture(null);

    private enum LifecycleState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    private record OpenedGeneration(
            long token,
            CompletableFuture<Void> quiescence
    ) {
    }

    private final class GenerationUseGate {
        private final ThreadLocal<UseDepth> localUse = new ThreadLocal<>();
        private boolean generationOpen;
        private boolean accepting;
        private int activeRoots;
        private long generationSequence;
        private long generationToken;
        private CompletableFuture<Void> quiescence = CompletableFuture.completedFuture(null);

        synchronized OpenedGeneration open() {
            if (generationOpen || accepting || activeRoots != 0 || !quiescence.isDone()) {
                throw new IllegalStateException("App-chain '" + config.chainId()
                        + "' cannot open a new resource generation while the previous one drains");
            }
            if (quiescence.isCompletedExceptionally()) {
                throw new IllegalStateException("App-chain '" + config.chainId()
                        + "' cannot open a new resource generation after failed cleanup");
            }
            if (generationSequence == Long.MAX_VALUE) {
                throw new IllegalStateException("App-chain '" + config.chainId()
                        + "' exhausted its resource-generation identity space");
            }
            generationOpen = true;
            accepting = false;
            generationToken = ++generationSequence;
            quiescence = new CompletableFuture<>();
            return new OpenedGeneration(generationToken, quiescence);
        }

        synchronized void activate() {
            if (!generationOpen || accepting || activeRoots != 0 || quiescence.isDone()) {
                throw new IllegalStateException("App-chain '" + config.chainId()
                        + "' cannot activate an invalid resource generation");
            }
            accepting = true;
        }

        GenerationUse tryAcquire() {
            UseDepth nested = localUse.get();
            if (nested != null) {
                nested.depth++;
                return new GenerationUse(nested, true);
            }
            synchronized (this) {
                if (!accepting || lifecycleState != LifecycleState.RUNNING) {
                    return new GenerationUse(null, false);
                }
                activeRoots++;
                UseDepth root = new UseDepth(generationToken);
                localUse.set(root);
                return new GenerationUse(root, true);
            }
        }

        GenerationUse tryAcquire(long expectedGenerationToken) {
            UseDepth nested = localUse.get();
            if (nested != null) {
                if (nested.generationToken != expectedGenerationToken) {
                    return new GenerationUse(null, false);
                }
                nested.depth++;
                return new GenerationUse(nested, true);
            }
            synchronized (this) {
                if (!accepting || lifecycleState != LifecycleState.RUNNING
                        || generationToken != expectedGenerationToken) {
                    return new GenerationUse(null, false);
                }
                activeRoots++;
                UseDepth root = new UseDepth(generationToken);
                localUse.set(root);
                return new GenerationUse(root, true);
            }
        }

        CompletableFuture<Void> seal() {
            CompletableFuture<Void> result;
            CompletableFuture<Void> completion;
            synchronized (this) {
                accepting = false;
                generationOpen = false;
                result = quiescence;
                completion = completionIfQuiescent();
            }
            // CompletableFuture dependents may run inline and may include
            // untrusted provider cleanup. Never run them under the gate lock.
            if (completion != null) {
                completion.complete(null);
            }
            return result;
        }

        boolean inUseByCurrentThread() {
            return localUse.get() != null;
        }

        private void release(UseDepth use) {
            if (localUse.get() != use) {
                throw new IllegalStateException("App-chain generation use closed on another thread");
            }
            use.depth--;
            if (use.depth > 0) {
                return;
            }
            localUse.remove();
            CompletableFuture<Void> completion;
            synchronized (this) {
                activeRoots--;
                completion = completionIfQuiescent();
            }
            if (completion != null) {
                completion.complete(null);
            }
        }

        private CompletableFuture<Void> completionIfQuiescent() {
            return !accepting && activeRoots == 0 ? quiescence : null;
        }

        private final class UseDepth {
            private final long generationToken;
            private int depth = 1;

            private UseDepth(long generationToken) {
                this.generationToken = generationToken;
            }
        }

        final class GenerationUse implements AutoCloseable {
            private final UseDepth use;
            private final boolean admitted;
            private boolean closed;

            private GenerationUse(UseDepth use, boolean admitted) {
                this.use = use;
                this.admitted = admitted;
            }

            boolean admitted() {
                return admitted;
            }

            @Override
            public void close() {
                if (!closed && admitted) {
                    closed = true;
                    release(use);
                }
            }
        }
    }

    private <T> T generationUseOr(T unavailable, java.util.function.Supplier<T> action) {
        var generationUse = generationUseGate.tryAcquire();
        try (generationUse) {
            return generationUse.admitted() ? action.get() : unavailable;
        }
    }

    private <T> T requireGenerationUse(java.util.function.Supplier<T> action) {
        var generationUse = generationUseGate.tryAcquire();
        try (generationUse) {
            if (!generationUse.admitted()) {
                throw new IllegalStateException("App chain is not running or is stopping");
            }
            return action.get();
        }
    }

    private void generationUseOrNoop(Runnable action) {
        var generationUse = generationUseGate.tryAcquire();
        try (generationUse) {
            if (generationUse.admitted()) {
                action.run();
            }
        }
    }

    private void generationUseOrNoop(long expectedGenerationToken, Runnable action) {
        var generationUse = generationUseGate.tryAcquire(expectedGenerationToken);
        try (generationUse) {
            if (generationUse.admitted()) {
                action.run();
            }
        }
    }

    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus, Logger log) {
        this(config, protocolMagic, eventBus, null, null, log);
    }

    /**
     * @param stateMachine custom state machine (library mode); null = resolve
     *                     from config.stateMachineId() (built-ins)
     * @param ledgerPath   base dir for the app ledger; null = "./app-chain"
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath, null, log);
    }

    /**
     * @param stateMachine       custom state machine (library mode); null = resolve
     *                           from config.stateMachineId() (built-ins, then
     *                           {@link AppStateMachineProvider} ServiceLoader lookup)
     * @param pluginClassLoader  additional classloader for provider discovery
     *                           (the node's plugin jar classloader); may be null
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath,
                             ClassLoader pluginClassLoader, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath,
                pluginClassLoader, new LegacyServiceLoaderProviderRegistry(pluginClassLoader),
                true, log);
    }

    /**
     * Catalog-aware constructor used by normal runtime assembly. The registry
     * owns and caches provider factories; each factory invocation below still
     * creates fresh chain-scoped state machines, modes, observers, sinks and
     * effect executors.
     *
     * @param pluginClassLoader retained for source/assembly symmetry; provider
     *                          discovery is exclusively through
     *                          {@code pluginProviders}
     * @param pluginProviders   catalog-selected typed provider registry
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath,
                             ClassLoader pluginClassLoader,
                             PluginProviderRegistry pluginProviders, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath,
                pluginClassLoader, pluginProviders, false, log);
    }

    private AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                              AppStateMachine stateMachine, String ledgerPath,
                              ClassLoader pluginClassLoader,
                              PluginProviderRegistry pluginProviders,
                              boolean ownsPluginProviders,
                              Logger log) {
        this.pluginProviders = Objects.requireNonNull(pluginProviders, "pluginProviders");
        this.ownedPluginProviders = ownsPluginProviders
                ? (AutoCloseable) pluginProviders
                : null;
        try {
            this.config = Objects.requireNonNull(config, "config");
            this.protocolMagic = protocolMagic;
            this.eventBus = eventBus;
            this.log = Objects.requireNonNull(log, "log");
            this.signer = SignerProviders.resolveFromRegistry(
                    config.signingKeyHex(), pluginProviders, log);
            this.group = new MemberGroup(
                    normalizeMemberKeys(config.memberKeysHex()), config.threshold());
            this.seenMessageIds = new SeenMessageIds(SEEN_IDS_HARD_CAP);
            this.pool = new AppMsgPool(config.poolMaxMessages());
            this.stateMachine = stateMachine != null
                    ? stateMachine
                    : resolveStateMachine(config.stateMachineId(), pluginProviders,
                            new com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext() {
                                @Override public String chainId() { return config.chainId(); }
                                @Override public java.util.Map<String, String> settings() {
                                    return config.pluginSettings();
                                }
                            }, log);
            this.ledgerPath = (ledgerPath != null ? ledgerPath : "./app-chain")
                    + "/" + config.chainId();

            if (!group.contains(signer.publicKeyHex())) {
                if (governedMode()) {
                    // Governed bootstrap (008.3): a late-added member starts with
                    // the chain's ORIGINAL genesis list (which predates it) and
                    // becomes a member via the derived governance epochs
                    log.warn("App-chain '{}': this node's key {} is not in the GENESIS member list — "
                                    + "expecting chain-governed epochs to include it "
                                    + "(catch-up will derive them)",
                            config.chainId(), signer.publicKeyHex());
                } else {
                    throw new IllegalArgumentException(
                            "This node's app-chain public key " + signer.publicKeyHex()
                                    + " is not in the configured member list "
                                    + "(yano.app-chain.members)");
                }
            }
            if (config.sequencingEnabled()) {
                String proposer = config.proposerKeyHex().toLowerCase(Locale.ROOT);
                if (!proposer.isEmpty() && !group.contains(proposer)) {
                    throw new IllegalArgumentException(
                            "Configured proposer " + proposer + " is not in the member list");
                }
                if (config.threshold() > group.size()) {
                    throw new IllegalArgumentException("Finality threshold " + config.threshold()
                            + " exceeds member count " + group.size());
                }
                // Fail fast on an unknown/misconfigured sequencer mode (008.2)
                this.sequencerMode = resolveSequencerMode();
            } else {
                this.sequencerMode = null;
            }
            createPeerLinks();
        } catch (Throwable constructionFailure) {
            Throwable outcome = constructionFailure;
            if (ownedPluginProviders != null) {
                try {
                    ownedPluginProviders.close();
                } catch (Throwable registryFailure) {
                    outcome = LifecycleFailures.merge(outcome, registryFailure);
                }
            }
            throw propagateLifecycleFailure(outcome,
                    "App-chain construction failed");
        }
    }

    /** Build one outbound link per configured peer (shared where wired, else dedicated). */
    private void createPeerLinks() {
        AppPeerClient.CatchUpHandler catchUpHandler =
                config.sequencingEnabled() ? this::onCatchUpBlocks : null;
        SharedAppTransport currentShared = sharedTransport;
        for (AppChainConfig.AppPeer peer : config.peers()) {
            String endpointKey = SharedAppTransport.key(peer.host(), peer.port());
            if (currentShared != null && sharedEndpoints.contains(endpointKey)) {
                // Ride the node's L1 session to this peer (one connection per
                // peer pair); a dedicated dial is kept as automatic fallback.
                SharedAppPeerLink link = new SharedAppPeerLink(currentShared, endpointKey,
                        peer.toString(),
                        () -> new AppPeerClient(peer, protocolMagic, transportConfig(),
                                catchUpHandler, log),
                        log);
                link.wireCatchUpHandler(catchUpHandler);
                peerClients.add(link);
                log.info("App chain '{}' peer {} uses the SHARED L1 transport", config.chainId(), peer);
            } else {
                peerClients.add(new AppPeerClient(peer, protocolMagic, transportConfig(),
                        catchUpHandler, log));
            }
        }
    }

    /**
     * Use the shared L1-session transport for peers whose endpoint is in
     * {@code endpoints} (ADR 005 M1 unification). Must be called before
     * {@link #start()}. The constructor already built dedicated links (it runs
     * before this wiring can), so the links are REBUILT here — they are inert
     * until the first connect tick, which only happens after start().
     */
    void wireSharedTransport(SharedAppTransport transport, java.util.Set<String> endpoints) {
        this.sharedTransport = transport;
        this.sharedEndpoints = endpoints != null ? java.util.Set.copyOf(endpoints) : java.util.Set.of();
        for (AppPeerLink link : peerClients) {
            link.shutdown(); // pre-start: nothing connected; marks the link dead
        }
        peerClients.clear();
        createPeerLinks();
    }

    /** Chain-governed membership selected (ADR 008.3)? Default: static. */
    private boolean governedMode() {
        return "governed".equalsIgnoreCase(
                config.pluginSettings().getOrDefault("membership.mode", "static"));
    }

    private long parseLongSetting(String key, long defaultValue) {
        String value = config.pluginSettings().get(key);
        return value != null && !value.isBlank() ? Long.parseLong(value.trim()) : defaultValue;
    }

    /**
     * Submit a membership governance command as this member (008.3). Internal
     * path — the public submit() rejects reserved {@code ~} topics.
     */
    private String submitGovernance(byte[] commandBody) {
        if (!running.get())
            throw new IllegalStateException("App chain is not running");
        AppMessage message = buildSigned(GovernedMembership.TOPIC, commandBody,
                config.defaultTtlSeconds());
        AppMsgPool.AddResult added = pool.add(message);
        if (added == AppMsgPool.AddResult.FULL) {
            countDrop("pool_full");
            throw new PoolFullException("App-chain '" + config.chainId()
                    + "' pending pool is full — governance command not submitted");
        }
        relay(message);
        record(message, ReceivedAppMessage.Source.LOCAL);
        log.info("Governance command submitted: id={}, chain={} — activates once {} distinct "
                + "member(s) submit the identical command", message.getMessageIdHex(),
                config.chainId(), group.threshold());
        return message.getMessageIdHex();
    }

    /**
     * Resolve the sequencer mode (ADR 008.2 §2.7): built-ins {@code fixed} /
     * {@code rotating}, then catalog-selected {@link SequencerModeProvider}
     * plugins. Selected by {@code sequencer.mode}; a bare
     * {@code sequencer.proposer} keeps meaning {@code fixed} (v1 compat).
     */
    private com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode resolveSequencerMode() {
        Map<String, String> settings = new LinkedHashMap<>(config.pluginSettings());
        if (!config.proposerKeyHex().isEmpty()) {
            settings.putIfAbsent("sequencer.proposer", config.proposerKeyHex());
        }
        String modeId = settings.getOrDefault("sequencer.mode",
                !config.proposerKeyHex().isEmpty() ? FixedSequencerMode.ID : "")
                .trim();
        if (modeId.isEmpty()) {
            throw new IllegalArgumentException("App-chain '" + config.chainId()
                    + "': sequencing requires sequencer.proposer (fixed) or sequencer.mode");
        }

        var context = new com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext() {
            @Override public String chainId() { return config.chainId(); }
            @Override public String selfKeyHex() {
                return signer.publicKeyHex().toLowerCase(Locale.ROOT);
            }
            @Override public List<String> membersAt(long height) {
                List<String> sorted = new ArrayList<>(group.membersAt(height));
                java.util.Collections.sort(sorted);
                return sorted;
            }
            @Override public long currentL1Slot() {
                AppChainEngine.L1Ref last = recentL1Points.peekLast();
                return last != null ? last.slot() : 0L;
            }
            @Override public Map<String, String> settings() { return settings; }
        };

        com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode mode;
        if (FixedSequencerMode.ID.equals(modeId)) {
            mode = new FixedSequencerMode();
        } else if (RotatingSequencerMode.ID.equals(modeId)) {
            mode = new RotatingSequencerMode();
        } else {
            SequencerModeProvider provider = pluginProviders.find(
                    SequencerModeProvider.class, modeId).orElse(null);
            if (provider == null) {
                throw new PluginActivationException("App-chain '" + config.chainId()
                        + "': configured plugin sequencer mode '" + modeId
                        + "' is not selected (built-ins: fixed, rotating; selected custom modes: "
                        + pluginProviders.names(SequencerModeProvider.class) + ")", null);
            }
            try {
                mode = Objects.requireNonNull(provider.create(context),
                        "SequencerModeProvider.create returned null");
                String productId = Objects.requireNonNull(
                        mode.id(), "SequencerMode.id returned null");
                if (!modeId.equals(productId)) {
                    throw new IllegalStateException("SequencerModeProvider '" + modeId
                            + "' returned product id '" + productId + "'");
                }
                mode = stableSequencerIdentity(mode, modeId);
                mode.init(context);
                return mode;
            } catch (RuntimeException failure) {
                throw pluginActivationFailure("sequencer mode '" + modeId + "'", failure);
            }
        }
        mode.init(context);
        return mode;
    }

    /**
     * Resolve a state machine by id: built-ins first, then
     * catalog-selected {@link AppStateMachineProvider} implementations.
     */
    private static AppStateMachine resolveStateMachine(String id,
                                                       PluginProviderRegistry pluginProviders,
                                                       AppStateMachineContext ctx,
                                                       Logger log) {
        if (OrderedLogStateMachine.ID.equals(id)) {
            return new OrderedLogStateMachine();
        }
        AppStateMachineProvider provider = pluginProviders.find(
                AppStateMachineProvider.class, id).orElse(null);
        if (provider != null) {
            log.info("App-chain state machine '{}' loaded via provider {}",
                    id, provider.getClass().getName());
            try {
                AppStateMachine machine = Objects.requireNonNull(provider.create(ctx),
                        "AppStateMachineProvider.create returned null");
                String productId = Objects.requireNonNull(
                        machine.id(), "AppStateMachine.id returned null");
                if (!id.equals(productId)) {
                    throw new IllegalStateException("AppStateMachineProvider '" + id
                            + "' returned product id '" + productId + "'");
                }
                return startupMarkedStateMachine(machine, id);
            } catch (RuntimeException failure) {
                throw pluginActivationFailure("state machine '" + id + "'", failure);
            }
        }
        List<String> available = new ArrayList<>();
        available.add(OrderedLogStateMachine.ID);
        available.addAll(pluginProviders.names(AppStateMachineProvider.class));
        throw new PluginActivationException("Configured plugin app-chain state machine '" + id
                + "' is not selected (available: " + available + ")", null);
    }

    private static AppStateMachine startupMarkedStateMachine(AppStateMachine delegate, String id) {
        return new AppStateMachine() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void init(AppStateReader state, AppChainInfo info) {
                try {
                    delegate.init(state, info);
                } catch (RuntimeException failure) {
                    throw pluginActivationFailure("state machine '" + id + "'", failure);
                }
            }

            @Override
            public AdmissionResult validate(AppMessage message) {
                return delegate.validate(message);
            }

            @Override
            public void apply(AppBlock block, AppStateWriter writer) {
                delegate.apply(block, writer);
            }

            @Override
            public void apply(
                    AppBlock block,
                    AppStateWriter writer,
                    com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter effects
            ) {
                delegate.apply(block, writer, effects);
            }

            @Override
            public void onEffectResult(
                    AppBlock block,
                    com.bloxbean.cardano.yano.api.appchain.effects.EffectResult result,
                    AppStateWriter writer
            ) {
                delegate.onEffectResult(block, result, writer);
            }

            @Override
            public byte[] query(String path, byte[] params) {
                return delegate.query(path, params);
            }

            @Override
            public byte[] query(String path, byte[] params, AppQueryContext state) {
                return delegate.query(path, params, state);
            }
        };
    }

    private static com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode
    stableSequencerIdentity(
            com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode delegate,
            String id
    ) {
        return new com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void init(
                    com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext context) {
                delegate.init(context);
            }

            @Override
            public boolean shouldProposeNow(long height) {
                return delegate.shouldProposeNow(height);
            }

            @Override
            public com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode.ProposalEligibility
            checkProposal(byte[] proposerKey, long height) {
                return delegate.checkProposal(proposerKey, height);
            }

            @Override
            public Map<String, Object> status() {
                return delegate.status();
            }
        };
    }

    private static PluginActivationException pluginActivationFailure(
            String component,
            Throwable failure
    ) {
        if (failure instanceof PluginActivationException activationFailure) {
            return activationFailure;
        }
        return new PluginActivationException(
                "Configured plugin " + component + " failed to activate", failure);
    }

    private static Set<String> normalizeMemberKeys(Set<String> keys) {
        Set<String> normalized = new HashSet<>();
        for (String key : keys) {
            String k = key.trim().toLowerCase(Locale.ROOT);
            if (k.length() != 64)
                throw new IllegalArgumentException("App-chain member key must be a 32-byte hex Ed25519 public key: " + key);
            normalized.add(k);
        }
        if (normalized.isEmpty())
            throw new IllegalArgumentException("App-chain member list must not be empty");
        return Set.copyOf(normalized);
    }

    /**
     * Transport frame limit: a consensus proposal carries a whole block, so the
     * transport must accept messages up to {@code block.max-bytes} plus a small
     * envelope margin (single source of truth with the engine's block-bytes cap;
     * block-bytes fix). Ordinary user messages stay capped at
     * {@code maxMessageBytes} by the submit() gate and the follower's
     * per-message verify, so relaxing the frame limit only benefits proposals.
     */
    private static final int TRANSPORT_ENVELOPE_MARGIN = 65536;

    /** Transport config shared by inbound (server) and outbound (client) agents. */
    private AppMsgSubmissionConfig transportConfig() {
        long limit = config.blockMaxBytes() + TRANSPORT_ENVELOPE_MARGIN;
        int maxSize = (int) Math.min(Integer.MAX_VALUE, Math.max(config.maxMessageBytes(), limit));
        return AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(config.chainId()))
                .maxMessageSize(maxSize)
                .maxTtlSeconds(config.maxTtlSeconds())
                .validator(this::verifyEnvelope)
                .build();
    }

    /**
     * Envelope authentication: Ed25519 signature by a registered group member.
     * Structural checks (id recompute, size, TTL, chain) already ran in the agent.
     */
    AppMsgValidator.Result verifyEnvelope(AppMessage message) {
        String topic = message == null ? null : message.getTopic();
        if (message == null || message.getVersion() != AppMessage.ENVELOPE_VERSION
                || !config.chainId().equals(message.getChainId())
                || message.getMessageId() == null || message.getMessageId().length != 32
                || !validTopic(topic)
                || message.getSender() == null || message.getSender().length != 32
                || message.getSenderSeq() < 0 || message.getExpiresAt() < 0
                || !validEnvelopeBodyProfile(topic, message.getBody())
                || message.getAuthProof() == null
                || message.getAuthProof().length != AppChainConfig.ED25519_SIGNATURE_BYTES
                || !message.hasValidMessageId()) {
            countDrop("bad_profile");
            return AppMsgValidator.Result.reject("invalid app-message v1 profile");
        }
        if (message.getAuthScheme() != AuthScheme.ED25519.getValue()) {
            countDrop("bad_auth");
            return AppMsgValidator.Result.reject("unsupported auth scheme: " + message.getAuthScheme());
        }

        byte[] sender = message.getSender();
        if (sender == null || sender.length != 32) {
            countDrop("bad_auth");
            return AppMsgValidator.Result.reject("invalid sender key length");
        }

        String senderHex = HexUtil.encodeHexString(sender).toLowerCase(Locale.ROOT);
        if (!group.contains(senderHex)) {
            countDrop("not_member");
            return AppMsgValidator.Result.reject("sender not in app-chain member list: " + senderHex);
        }

        byte[] proof = message.getAuthProof();
        if (proof == null || proof.length == 0) {
            countDrop("bad_auth");
            return AppMsgValidator.Result.reject("missing signature");
        }

        if (!AppMessageSigner.verify(proof, message.signedBodyBytes(), sender)) {
            countDrop("bad_auth");
            return AppMsgValidator.Result.reject("signature verification failed");
        }

        return AppMsgValidator.Result.accept();
    }

    boolean validEnvelopeBodyProfile(String topic, byte[] body) {
        if (body == null) {
            return false;
        }
        long bodyLimit = switch (topic == null ? "" : topic) {
            case ConsensusCodec.TOPIC_PROPOSE -> config.proposalMaxBytes();
            case ConsensusCodec.TOPIC_VOTE -> ConsensusCodec.MAX_VOTE_BYTES;
            case ConsensusCodec.TOPIC_CERT -> ConsensusCodec.MAX_CERT_NOTICE_BYTES;
            default -> config.maxMessageBytes();
        };
        return body.length <= bodyLimit;
    }

    /**
     * Wire node-level L1 access (tx submission for anchoring, UTXO queries for
     * anchor input selection). Called by the runtime before start; optional —
     * without it, anchoring is unavailable even when configured.
     */
    /**
     * Current protocol parameters for anchor tx pricing: linear fee (008.1
     * I1.5) plus, for script anchors (008.4), the ex-unit prices and the
     * PlutusV3 cost model (script-data-hash language views). The script
     * fields may be null — the script anchor falls back to Conway defaults.
     */
    public record AnchorFeeParams(long minFeeA, long minFeeB,
                                  java.math.BigDecimal priceMem,
                                  java.math.BigDecimal priceStep,
                                  long[] costModelV3) {
        /** Pre-008.4 signature — linear fee only. */
        public AnchorFeeParams(long minFeeA, long minFeeB) {
            this(minFeeA, minFeeB, null, null, null);
        }
    }

    private volatile java.util.function.Supplier<AnchorFeeParams> anchorFeeParamsSupplier;

    /**
     * Wire the node's protocol-parameter fee source for anchor transactions.
     * Optional — without it (or when the supplier returns null) the anchor tx
     * uses the configured {@code anchor.fallback-fee-lovelace}.
     */
    public void wireAnchorFees(java.util.function.Supplier<AnchorFeeParams> supplier) {
        this.anchorFeeParamsSupplier = supplier;
    }

    private volatile java.util.function.Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams>
            anchorProtocolParamsSupplier;

    /**
     * Wire the node's full protocol parameters (cardano-client-lib model) for
     * QuickTx-based script-anchor tx construction (Iteration 4). Optional —
     * without it the script anchor uses Conway defaults.
     */
    public void wireAnchorProtocolParams(
            java.util.function.Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams> supplier) {
        this.anchorProtocolParamsSupplier = supplier;
    }

    public void wireL1(java.util.function.Function<byte[], String> txSubmitter,
                       java.util.function.Supplier<com.bloxbean.cardano.yano.api.utxo.UtxoState> utxoStateSupplier) {
        this.txSubmitter = txSubmitter;
        this.utxoStateSupplier = utxoStateSupplier;
    }

    /**
     * Server-side agent factory for inbound peer sessions; install into the
     * NodeServer via {@code ServeSubsystem.enableAppLayer(...)}.
     */
    public List<AgentFactory> serverAgentFactories() {
        AgentFactory gossipFactory = () -> {
            AppMsgSubmissionServerAgent serverAgent = new AppMsgSubmissionServerAgent(transportConfig());
            serverAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {
                    onInboundMessages(reply.getMessages());
                }
            });
            return serverAgent;
        };
        if (!config.sequencingEnabled()) {
            return List.of(gossipFactory);
        }
        AgentFactory catchUpFactory = () ->
                new com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent(
                        this::catchUpRange);
        return List.of(gossipFactory, catchUpFactory);
    }

    com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent
            .BlockRangeProvider.Range catchUpRange(String chainId, long fromHeight, long toHeight) {
        var generationUse = generationUseGate.tryAcquire();
        try (generationUse) {
            AppLedgerStore currentLedger = ledger;
            if (!generationUse.admitted() || currentLedger == null
                    || !config.chainId().equals(chainId)) {
                return new com.bloxbean.cardano.yaci.core.protocol.appchainsync
                        .AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0);
            }
            return new com.bloxbean.cardano.yaci.core.protocol.appchainsync
                    .AppChainSyncServerAgent.BlockRangeProvider.Range(
                    currentLedger.blockBytesRange(fromHeight, toHeight),
                    currentLedger.tipHeight());
        }
    }

    /** Test seam for retention policy without leaking a ledger past its generation lease. */
    int pruneBodiesBelowForTesting(long horizon) {
        return requireGenerationUse(() -> {
            AppLedgerStore currentLedger = ledger;
            if (currentLedger == null) {
                throw new IllegalStateException("App chain has no ledger (sequencing disabled)");
            }
            return currentLedger.pruneBodiesBelow(horizon);
        });
    }

    AppChainConfig chainConfig() {
        return config;
    }

    /** Transport config for this chain (manager builds shared multi-chain agents). */
    AppMsgSubmissionConfig chainTransportConfig() {
        return transportConfig();
    }

    /** Catch-up replies from a peer (protocol 103). */
    private void onCatchUpBlocks(String peerId, List<byte[]> blocks, long serverTipHeight) {
        generationUseOrNoop(() -> onCatchUpBlocksWithinGeneration(peerId, blocks, serverTipHeight));
    }

    private void onCatchUpBlocksWithinGeneration(
            String peerId, List<byte[]> blocks, long serverTipHeight) {
        bestPeerTip = Math.max(bestPeerTip, serverTipHeight);
        AppChainEngine currentEngine = engine;
        if (currentEngine != null && !blocks.isEmpty()) {
            log.info("Catch-up: received {} block(s) from {} (peer tip: {})",
                    blocks.size(), peerId, serverTipHeight);
            currentEngine.onCertifiedBlocks(blocks);
        }
    }

    private void catchUpTick() {
        generationUseOrNoop(this::catchUpTickWithinGeneration);
    }

    private void catchUpTickWithinGeneration() {
        AppChainEngine currentEngine = engine;
        if (!running.get() || currentEngine == null) {
            return;
        }
        long tip = currentEngine.tipHeight();
        for (AppPeerLink peerClient : peerClients) {
            if (peerClient.isConnected()
                    && peerClient.requestCatchUp(config.chainId(), tip + 1, tip + 50)) {
                break; // one in-flight catch-up request at a time
            }
        }
        // Stall detection: a peer is ahead but we've made no progress in a while
        long now = System.currentTimeMillis();
        if (bestPeerTip > tip && now - lastProgressAt > STALL_WINDOW_MS
                && now - lastStallEventAt > STALL_WINDOW_MS) {
            lastStallEventAt = now;
            log.warn("App chain '{}' appears stalled: local tip {}, best peer tip {}",
                    config.chainId(), tip, bestPeerTip);
            if (eventBus != null) {
                try {
                    eventBus.publish(new com.bloxbean.cardano.yano.api.events.AppChainStalledEvent(
                                    config.chainId(), tip, bestPeerTip),
                            EventMetadata.builder().build(), PublishOptions.builder().build());
                } catch (Exception e) {
                    log.warn("Failed to publish AppChainStalledEvent (errorType={})",
                            e.getClass().getName());
                }
            }
        }
    }

    /** Verified messages arriving from a peer: dedup, route, relay. */
    void onInboundMessages(List<AppMessage> messages) {
        generationUseOrNoop(() -> onInboundMessagesWithinGeneration(messages));
    }

    private void onInboundMessagesWithinGeneration(List<AppMessage> messages) {
        for (AppMessage message : messages) {
            boolean firstSighting =
                    seenMessageIds.markSeen(message.getMessageIdHex(), message.getExpiresAt());
            String topic = message.getTopic() != null ? message.getTopic() : "";
            // Only ~consensus/* gets the engine fast-path; other system topics
            // (~governance/*) are SEQUENCED like ordinary messages (008.3)
            if (topic.startsWith(AppChainSystemTopics.CONSENSUS_DIFFUSION_PREFIX)) {
                // Consensus/system messages: relay only on first sighting (loop
                // control) but ALWAYS route — the engine is idempotent (round
                // guards, vote locks), and partial-round re-gossip (008.2) must
                // reach it even when the first copy raced engine wiring; wire
                // dedup once dropped such a proposal permanently.
                if (firstSighting) {
                    receivedCount.incrementAndGet();
                    relay(message);
                } else {
                    duplicateCount.incrementAndGet();
                }
                route(message, ReceivedAppMessage.Source.PEER);
                continue;
            }
            if (!firstSighting) {
                duplicateCount.incrementAndGet();
                continue;
            }
            receivedCount.incrementAndGet();
            // ~anchor/* (008.4): diffusion-only co-signing traffic — relayed
            // but never pooled/sequenced; the leader re-diffuses each tick, so
            // first-sighting delivery is enough
            if (topic.startsWith(AppChainSystemTopics.ANCHOR_DIFFUSION_PREFIX)) {
                relay(message);
                ScriptAnchorService currentScriptAnchor = scriptAnchorService;
                if (currentScriptAnchor != null) {
                    currentScriptAnchor.onAnchorMessage(message);
                }
                continue;
            }
            route(message, ReceivedAppMessage.Source.PEER);
            relay(message);
        }
    }

    /**
     * Consensus messages that arrived before the engine finished wiring
     * (peers connect to the server the moment it binds, while start() is
     * still opening the ledger) — drained into the engine at start. Without
     * this, a proposal delivered during the race is LOST for the whole
     * session: the transport never re-delivers an acked message id (008.2).
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<AppMessage> earlyConsensus =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int EARLY_CONSENSUS_LIMIT = 256;

    private void route(AppMessage message, ReceivedAppMessage.Source source) {
        String topic = message.getTopic() != null ? message.getTopic() : "";
        if (topic.startsWith(AppChainSystemTopics.CONSENSUS_DIFFUSION_PREFIX)) {
            AppChainEngine currentEngine = engine;
            if (currentEngine != null) {
                currentEngine.onConsensusMessage(message);
            } else if (earlyConsensus.size() < EARLY_CONSENSUS_LIMIT) {
                earlyConsensus.add(message);
            }
            return;
        }
        // ~governance/* and ordinary messages continue to the pool below —
        // governance commands are finalized chain transactions (008.3)
        // Ordinary app message: pool for sequencing + observability surface
        AppLedgerStore currentLedger = ledger;
        if (currentLedger != null && currentLedger.messageHeight(message.getMessageId()).isPresent()) {
            duplicateCount.incrementAndGet();
            return; // already finalized in a block
        }
        // Replay floor (I1.2): a seq at or below the sender's last finalized
        // seq is a replay — reject at admission (node-local, always on)
        if (currentLedger != null && message.getSenderSeq() > 0
                && message.getSenderSeq() <= currentLedger.senderSeq(message.getSender())) {
            countDrop("stale_seq");
            log.debug("App-chain '{}': stale sender-seq {} from {} — dropped",
                    config.chainId(), message.getSenderSeq(), message.getMessageIdHex());
            return;
        }
        AppMsgPool.AddResult added = pool.add(message);
        if (added == AppMsgPool.AddResult.FULL) {
            // Gossip is best-effort; the drop is surfaced via counters, not errors
            countDrop("pool_full");
            log.debug("App-chain '{}' pool full ({}): dropped inbound message {}",
                    config.chainId(), pool.capacity(), message.getMessageIdHex());
            return;
        }
        if (added == AppMsgPool.AddResult.DUPLICATE) {
            duplicateCount.incrementAndGet();
            return;
        }
        record(message, source);
    }

    private void relay(AppMessage message) {
        for (AppPeerLink peerClient : peerClients) {
            peerClient.enqueue(message);
        }
        if (!peerClients.isEmpty()) {
            relayedCount.incrementAndGet();
        }
    }

    private void record(AppMessage message, ReceivedAppMessage.Source source) {
        ReceivedAppMessage received = new ReceivedAppMessage(
                message.getMessageIdHex(),
                message.getChainId(),
                message.getTopic(),
                HexUtil.encodeHexString(message.getSender()),
                message.getSenderSeq(),
                message.getExpiresAt(),
                message.getBody(),
                System.currentTimeMillis(),
                source);
        recentMessages.addLast(received);
        while (recentMessages.size() > RECENT_MESSAGES_LIMIT) {
            recentMessages.pollFirst();
        }
        if (eventBus != null) {
            try {
                eventBus.publish(new AppMessageReceivedEvent(received),
                        EventMetadata.builder().build(), PublishOptions.builder().build());
            } catch (Exception e) {
                log.warn("Failed to publish AppMessageReceivedEvent (errorType={})",
                        e.getClass().getName());
            }
        }
    }

    /** Build and sign an envelope on the given topic — NOT yet diffused. */
    private AppMessage buildSigned(String topic, byte[] body, long ttlSeconds) {
        if (!validTopic(topic) || !validEnvelopeBodyProfile(topic, body)) {
            throw new IllegalArgumentException(
                    "Internal app message is outside the v1 topic/body profile");
        }
        long expiresAt = System.currentTimeMillis() / 1000 + ttlSeconds;
        long seq = senderSeq.incrementAndGet();
        byte[] signedBody = AppMessage.signedBodyBytes(config.chainId(), topic,
                signer.publicKey(), seq, expiresAt, body);
        byte[] signature = signer.sign(signedBody);
        byte[] messageId = AppMessage.computeMessageId(config.chainId(), topic,
                signer.publicKey(), seq, expiresAt, body);

        AppMessage message = AppMessage.builder()
                .messageId(messageId)
                .chainId(config.chainId())
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(seq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signature)
                .build();

        seenMessageIds.markSeen(message.getMessageIdHex(), expiresAt);
        return message;
    }

    /** Build, sign and diffuse an envelope (consensus/system topics). */
    private AppMessage buildAndDiffuse(String topic, byte[] body, long ttlSeconds) {
        AppMessage message = buildSigned(topic, body, ttlSeconds);
        relay(message);
        return message;
    }

    /** Is this node the currently-scheduled proposer (fixed or rotating)? */
    private boolean isScheduledProposer() {
        AppChainEngine currentEngine = engine;
        if (currentEngine == null) {
            return false;
        }
        Map<String, Object> sequencer = currentEngine.sequencerStatus();
        String scheduled = String.valueOf(
                sequencer.getOrDefault("currentProposer", sequencer.get("proposer")));
        return signer.publicKeyHex().equalsIgnoreCase(scheduled);
    }

    /**
     * Inject an L1 observation into the pool for sequencing (008.4 I3.2) —
     * the internal path for the reserved {@code ~l1/*} topics ({@link #submit}
     * rejects them for external callers). Best-effort: a full pool drops the
     * observation (counted); the proposer re-observes nothing — the fact is
     * simply not sequenced until an operator inspects the drop counters.
     */
    private void injectObservation(com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation observation) {
        try {
            AppMessage message = buildSigned(observation.topic(), observation.encode(),
                    config.defaultTtlSeconds());
            AppMsgPool.AddResult added = pool.add(message);
            if (added == AppMsgPool.AddResult.ADDED) {
                relay(message);
                record(message, ReceivedAppMessage.Source.LOCAL);
                log.info("L1 observation injected: topic={}, l1Slot={}, id={}",
                        observation.topic(), observation.slot(), message.getMessageIdHex());
            } else if (added == AppMsgPool.AddResult.FULL) {
                countDrop("pool_full");
                log.warn("L1 observation dropped — pool full (topic {}, l1Slot {})",
                        observation.topic(), observation.slot());
            }
        } catch (Exception e) {
            log.warn("L1 observation injection failed (errorType={})",
                    e.getClass().getName());
        }
    }

    // ------------------------------------------------------------------
    // AppChainGateway
    // ------------------------------------------------------------------

    @Override
    public String chainId() {
        return config.chainId();
    }

    @Override
    public String submit(String topic, byte[] body) {
        return requireGenerationUse(() -> submitWithinGeneration(topic, body));
    }

    private String submitWithinGeneration(String topic, byte[] body) {
        if (!running.get())
            throw new IllegalStateException("App chain is not running");
        if (submissionsPaused.get())
            throw new IllegalStateException("Submissions are paused (admin)");
        Objects.requireNonNull(body, "body");
        if (body.length == 0)
            throw new IllegalArgumentException("body must not be empty");
        if (body.length > config.maxMessageBytes())
            throw new IllegalArgumentException("body exceeds max message size ("
                    + body.length + " > " + config.maxMessageBytes() + ")");
        String effectiveTopic = topic != null ? topic : "";
        if (effectiveTopic.startsWith(SYSTEM_TOPIC_PREFIX))
            throw new IllegalArgumentException("Topics starting with '~' are reserved for the framework");
        if (!validTopic(effectiveTopic))
            throw new IllegalArgumentException("topic must be at most "
                    + AppChainConfig.MAX_TOPIC_BYTES + " valid UTF-8 bytes without NUL");

        // Admit locally BEFORE diffusing — a message this node cannot hold must
        // not be half-way into the network with an "accepted" id (ADR 008.1 I1.1)
        AppMessage message = buildSigned(effectiveTopic, body, config.defaultTtlSeconds());
        AppMsgPool.AddResult added = pool.add(message);
        if (added == AppMsgPool.AddResult.FULL) {
            countDrop("pool_full");
            throw new PoolFullException("App-chain '" + config.chainId()
                    + "' pending pool is full (" + pool.capacity() + " messages) — retry later");
        }
        relay(message);
        submittedCount.incrementAndGet();
        record(message, ReceivedAppMessage.Source.LOCAL);
        log.info("App message submitted: id={}, chain={}, topic={}, seq={}",
                message.getMessageIdHex(), config.chainId(), effectiveTopic, message.getSenderSeq());
        return message.getMessageIdHex();
    }

    private static boolean validTopic(String topic) {
        return topic != null && topic.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(topic)
                && topic.getBytes(StandardCharsets.UTF_8).length
                <= AppChainConfig.MAX_TOPIC_BYTES;
    }

    @Override
    public List<ReceivedAppMessage> recentMessages(int limit) {
        int effectiveLimit = limit <= 0 ? RECENT_MESSAGES_LIMIT : Math.min(limit, RECENT_MESSAGES_LIMIT);
        List<ReceivedAppMessage> all = new ArrayList<>(recentMessages);
        if (all.size() <= effectiveLimit)
            return all;
        return all.subList(all.size() - effectiveLimit, all.size());
    }

    @Override
    public long tipHeight() {
        return generationUseOr(0L, () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null ? currentLedger.tipHeight() : 0L;
        });
    }

    @Override
    public Optional<AppBlock> block(long height) {
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null ? currentLedger.block(height) : Optional.empty();
        });
    }

    @Override
    public byte[] stateRoot() {
        return generationUseOr(new byte[32], () -> {
            AppLedgerStore currentLedger = ledger;
            byte[] root = currentLedger != null ? currentLedger.stateRoot() : null;
            return root != null ? root : new byte[32];
        });
    }

    @Override
    public Optional<byte[]> stateValue(byte[] key) {
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null ? currentLedger.stateGet(key) : Optional.empty();
        });
    }

    @Override
    public Optional<byte[]> stateProof(byte[] key) {
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null ? currentLedger.stateProofWire(key) : Optional.empty();
        });
    }

    @Override
    public Optional<AppStateProofSnapshot> stateProofSnapshot(byte[] key) {
        byte[] keySnapshot = Objects.requireNonNull(key, "key").clone();
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null
                    ? currentLedger.stateProofSnapshot(keySnapshot) : Optional.empty();
        });
    }

    @Override
    public AppQueryResult query(String path, byte[] request) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(QUERY_TIMEOUT_SECONDS);
        String validatedPath = validateQueryPath(path);
        if (request != null && request.length > MAX_QUERY_REQUEST_BYTES) {
            throw queryFailure(AppQueryException.Code.REQUEST_TOO_LARGE,
                    "App-chain query request exceeds the size limit");
        }
        byte[] requestCopy = request != null ? request.clone() : new byte[0];

        QueryLane currentLane = queryLane;
        if (currentLane == null) {
            throw queryUnavailable();
        }
        if (deadline - System.nanoTime() <= 0) {
            throw queryFailure(AppQueryException.Code.TIMEOUT,
                    "App-chain query deadline exceeded");
        }
        QueryTask task = currentLane.admit(validatedPath, requestCopy);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("query admission exceeded deadline");
            }
            return task.completion().get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            AppQueryException timeout = queryFailure(AppQueryException.Code.TIMEOUT,
                    "App-chain query deadline exceeded");
            task.cancel(timeout);
            throw timeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppQueryException interrupted = queryFailure(AppQueryException.Code.UNAVAILABLE,
                    "App-chain query wait was interrupted");
            task.cancel(interrupted);
            throw interrupted;
        } catch (ExecutionException e) {
            Throwable failure = e.getCause();
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            if (failure instanceof AppQueryException queryException) {
                throw queryException;
            }
            throw queryFailure(AppQueryException.Code.FAILED,
                    "App-chain query failed", failure);
        }
    }

    private static String validateQueryPath(String path) {
        if (path != null && path.length() > MAX_QUERY_PATH_CHARACTERS) {
            throw queryFailure(AppQueryException.Code.REQUEST_TOO_LARGE,
                    "App-chain query path exceeds the size limit");
        }
        try {
            return AppQueryPath.validate(path);
        } catch (IllegalArgumentException invalidPath) {
            throw queryFailure(AppQueryException.Code.INVALID_REQUEST,
                    "App-chain query path is invalid", invalidPath);
        }
    }

    private static AppQueryException queryUnavailable() {
        return queryFailure(AppQueryException.Code.UNAVAILABLE,
                "App-chain query service is unavailable");
    }

    private static AppQueryException queryFailure(
            AppQueryException.Code code,
            String message
    ) {
        return new AppQueryException(code, message);
    }

    private static AppQueryException queryFailure(
            AppQueryException.Code code,
            String message,
            Throwable cause
    ) {
        return new AppQueryException(code, message, cause);
    }

    /** One generation-scoped, single-worker bounded query lane. */
    private final class QueryLane {
        private final long generationToken;
        private final AppLedgerStore generationLedger;
        private final ThreadPoolExecutor executor;
        private final Set<QueryTask> accepted = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean accepting = new AtomicBoolean(true);

        private QueryLane(long generationToken, AppLedgerStore generationLedger) {
            this.generationToken = generationToken;
            this.generationLedger = Objects.requireNonNull(generationLedger, "generationLedger");
            this.executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUERY_QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(
                                runnable, "app-chain-query-" + config.chainId());
                        thread.setDaemon(true);
                        thread.setContextClassLoader(AppChainSubsystem.class.getClassLoader());
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private QueryTask admit(String path, byte[] request) {
            if (!accepting.get()) {
                throw queryUnavailable();
            }
            QueryTask task = new QueryTask(this, path, request);
            accepted.add(task);
            if (!accepting.get()) {
                AppQueryException unavailable = queryUnavailable();
                task.cancel(unavailable);
                throw unavailable;
            }
            try {
                executor.execute(task);
                return task;
            } catch (RejectedExecutionException e) {
                boolean busy = accepting.get() && !executor.isShutdown();
                AppQueryException rejection = busy
                        ? queryFailure(AppQueryException.Code.BUSY,
                                "App-chain query queue is full")
                        : queryUnavailable();
                task.cancel(rejection);
                throw rejection;
            }
        }

        private AppQueryResult execute(String path, byte[] request, QueryTask task) {
            var generationUse = generationUseGate.tryAcquire(generationToken);
            try (generationUse) {
                if (!generationUse.admitted()) {
                    throw queryUnavailable();
                }
                task.throwIfCancelled();

                final String machineId;
                try {
                    machineId = stateMachine.id();
                } catch (Throwable failure) {
                    LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                    logQueryPluginFailure("identity", failure);
                    throw queryFailure(AppQueryException.Code.FAILED,
                            "App-chain state-machine identity failed");
                }
                if (machineId == null || machineId.isBlank()) {
                    throw queryFailure(AppQueryException.Code.FAILED,
                            "App-chain state-machine identity is invalid");
                }

                final AppLedgerStore.CommittedStateSnapshot snapshot;
                try {
                    snapshot = generationLedger.captureCommittedState();
                } catch (Throwable failure) {
                    LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                    throw queryFailure(AppQueryException.Code.UNAVAILABLE,
                            "Committed app-chain state is unavailable", failure);
                }

                ExpiringQueryContext context = new ExpiringQueryContext(
                        generationLedger, snapshot.height(), snapshot.stateRoot());
                byte[] result;
                try {
                    // A queued request whose caller timed out must never begin
                    // plugin execution merely because it raced dequeue. An
                    // already-started callback is interrupted and remains
                    // generation-fenced until it exits.
                    task.throwIfCancelled();
                    result = stateMachine.query(path, request.clone(), context);
                } catch (AppQueryException declared) {
                    LifecycleFailures.rethrowIfProcessFatalReachable(declared);
                    if (declared.code() == AppQueryException.Code.UNSUPPORTED) {
                        throw queryFailure(AppQueryException.Code.UNSUPPORTED,
                                "App-chain query path is unsupported");
                    }
                    if (declared.code() == AppQueryException.Code.INVALID_REQUEST) {
                        throw queryFailure(AppQueryException.Code.INVALID_REQUEST,
                                "App-chain query parameters are invalid");
                    }
                    logQueryPluginFailure("callback", declared);
                    throw queryFailure(AppQueryException.Code.FAILED,
                            "App-chain state-machine query failed");
                } catch (Throwable failure) {
                    LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                    logQueryPluginFailure("callback", failure);
                    throw queryFailure(AppQueryException.Code.FAILED,
                            "App-chain state-machine query failed");
                } finally {
                    context.expire();
                }

                if (result == null) {
                    throw queryFailure(AppQueryException.Code.FAILED,
                            "App-chain state-machine query returned no result");
                }
                if (result.length > MAX_QUERY_RESULT_BYTES) {
                    throw queryFailure(AppQueryException.Code.RESULT_TOO_LARGE,
                            "App-chain query result exceeds the size limit");
                }
                return new AppQueryResult(
                        config.chainId(), machineId, snapshot.height(),
                        snapshot.stateRoot(), result);
            }
        }

        private void requestShutdown() {
            if (!accepting.compareAndSet(true, false)) {
                return;
            }
            AppQueryException unavailable = queryUnavailable();
            for (QueryTask task : List.copyOf(accepted)) {
                task.cancel(unavailable);
            }
            for (Runnable queued : executor.shutdownNow()) {
                if (queued instanceof QueryTask task) {
                    task.cancel(unavailable);
                }
            }
        }

        private void finished(QueryTask task) {
            accepted.remove(task);
        }

        private void logQueryPluginFailure(String stage, Throwable failure) {
            try {
                log.warn("App-chain '{}' query {} failed (errorType={})",
                        config.chainId(), stage, failure.getClass().getName());
            } catch (Throwable diagnosticFailure) {
                LifecycleFailures.rethrowIfProcessFatalReachable(diagnosticFailure);
            }
        }
    }

    /**
     * Direct executor task rather than FutureTask: process-fatal plugin errors
     * must still reach the worker's uncaught-error path after a caller timeout.
     */
    private static final class QueryTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final QueryLane owner;
        private final String path;
        private final byte[] request;
        private final CompletableFuture<AppQueryResult> completion = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicReference<AppQueryException> cancellation = new AtomicReference<>();
        private volatile Thread runner;

        private QueryTask(QueryLane owner, String path, byte[] request) {
            this.owner = owner;
            this.path = path;
            this.request = request.clone();
        }

        private CompletableFuture<AppQueryResult> completion() {
            return completion;
        }

        private void cancel(AppQueryException reason) {
            cancellation.compareAndSet(null, Objects.requireNonNull(reason, "reason"));
            if (state.compareAndSet(QUEUED, FINISHED)) {
                owner.executor.remove(this);
                completion.completeExceptionally(cancellation.get());
                owner.finished(this);
                return;
            }
            Thread activeRunner = runner;
            if (state.get() == RUNNING && activeRunner != null) {
                activeRunner.interrupt();
            }
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                return;
            }
            runner = Thread.currentThread();
            Throwable processFatal = null;
            try {
                AppQueryException cancellationFailure = cancellation.get();
                if (cancellationFailure != null) {
                    throw cancellationFailure;
                }
                AppQueryResult result = owner.execute(path, request, this);
                cancellationFailure = cancellation.get();
                if (cancellationFailure != null) {
                    throw cancellationFailure;
                }
                completion.complete(result);
            } catch (Throwable failure) {
                try {
                    LifecycleFailures.rethrowIfProcessFatalReachable(failure);
                } catch (Throwable fatal) {
                    processFatal = fatal;
                    completion.completeExceptionally(fatal);
                }
                if (processFatal == null) {
                    AppQueryException cancellationFailure = cancellation.get();
                    Throwable exposedFailure = cancellationFailure != null
                            ? cancellationFailure : failure;
                    AppQueryException exposed = exposedFailure instanceof AppQueryException queryException
                            ? queryException
                            : queryFailure(AppQueryException.Code.FAILED,
                                    "App-chain query failed", exposedFailure);
                    completion.completeExceptionally(exposed);
                }
            } finally {
                runner = null;
                state.set(FINISHED);
                owner.finished(this);
            }
            if (processFatal != null) {
                LifecycleFailures.rethrowIfProcessFatal(processFatal);
            }
        }

        private void throwIfCancelled() {
            AppQueryException cancellationFailure = cancellation.get();
            if (cancellationFailure != null) {
                throw cancellationFailure;
            }
        }
    }

    /** Root-fixed reader that fences retained access after callback return. */
    private static final class ExpiringQueryContext implements AppQueryContext {
        private final AppLedgerStore ledger;
        private final long committedHeight;
        private final byte[] stateRoot;
        private boolean active = true;
        private int activeReads;

        private ExpiringQueryContext(
                AppLedgerStore ledger,
                long committedHeight,
                byte[] stateRoot
        ) {
            this.ledger = Objects.requireNonNull(ledger, "ledger");
            this.committedHeight = committedHeight;
            this.stateRoot = Objects.requireNonNull(stateRoot, "stateRoot").clone();
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            beginRead();
            try {
                byte[] keyCopy = Objects.requireNonNull(key, "key").clone();
                return ledger.stateGetAtRoot(stateRoot, keyCopy)
                        .map(byte[]::clone);
            } finally {
                endRead();
            }
        }

        @Override
        public byte[] stateRoot() {
            beginRead();
            try {
                return stateRoot.clone();
            } finally {
                endRead();
            }
        }

        @Override
        public long committedHeight() {
            beginRead();
            try {
                return committedHeight;
            } finally {
                endRead();
            }
        }

        private synchronized void beginRead() {
            if (!active) {
                throw queryUnavailable();
            }
            activeReads++;
        }

        private void endRead() {
            synchronized (this) {
                activeReads--;
                if (!active && activeReads == 0) {
                    notifyAll();
                }
            }
        }

        private void expire() {
            boolean interrupted = false;
            synchronized (this) {
                active = false;
                while (activeReads != 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Optional<Long> messageHeight(byte[] messageId) {
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null ? currentLedger.messageHeight(messageId) : Optional.empty();
        });
    }

    // ------------------------------------------------------------------
    // Query surface (ADR 006 E3.3)
    // ------------------------------------------------------------------

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesByTopic(
            String topic, long fromHeight, int limit) {
        return generationUseOr(List.of(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null
                    ? currentLedger.messagesByTopic(topic, fromHeight, Math.max(1, Math.min(limit, 1000)))
                    : List.of();
        });
    }

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesBySender(
            byte[] sender, long fromHeight, int limit) {
        if (sender == null || sender.length != 32)
            throw new IllegalArgumentException("sender must be a 32-byte Ed25519 public key");
        return generationUseOr(List.of(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null
                    ? currentLedger.messagesBySender(sender, fromHeight, Math.max(1, Math.min(limit, 1000)))
                    : List.of();
        });
    }

    // ------------------------------------------------------------------
    // Effects (ADR app-layer/010 F12): consensus-tier read surface
    // ------------------------------------------------------------------

    @Override
    public List<EffectView> effects(long fromHeight, int limit) {
        return generationUseOr(List.of(), () -> {
            AppLedgerStore currentLedger = ledger;
            if (currentLedger == null) {
                return List.of();
            }
            return currentLedger.fxRecordsFrom(fromHeight, Math.max(1, Math.min(limit, 1000))).stream()
                    .map(EffectView::of)
                    .toList();
        });
    }

    @Override
    public Optional<EffectView> effect(long height, int ordinal) {
        return generationUseOr(Optional.empty(), () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null
                    ? currentLedger.fxRecord(height, ordinal).map(EffectView::of)
                    : Optional.empty();
        });
    }

    @Override
    public com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup effectProof(
            long height, int ordinal) {
        var unavailable = com.bloxbean.cardano.yano.api.appchain.effects.EffectProofLookup.notFound(0);
        return generationUseOr(unavailable, () -> {
            AppLedgerStore currentLedger = ledger;
            return currentLedger != null
                    ? currentLedger.fxEffectProof(height, ordinal)
                    : unavailable;
        });
    }

    @Override
    public Map<String, Object> effectStats() {
        return generationUseOr(inactiveEffectStats(null), () -> {
            EffectRuntime currentFx = effectRuntime;
            AppLedgerStore currentLedger = ledger;
            return currentFx != null ? currentFx.stats() : inactiveEffectStats(currentLedger);
        });
    }

    private Map<String, Object> inactiveEffectStats(AppLedgerStore currentLedger) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("enabled", Boolean.parseBoolean(
                config.pluginSettings().getOrDefault("effects.enabled", "false")));
        stats.put("runtimeEnabled", false);
        stats.put("metricsGeneration", "inactive");
        stats.put("openOnChain", currentLedger != null ? currentLedger.fxOpenCount() : 0L);
        stats.put("expiredTotal", currentLedger != null ? currentLedger.fxExpiredCount() : 0L);
        stats.put("queueDepth", 0L);
        stats.put("inFlight", 0L);
        stats.put("statusCounts", Map.of());
        stats.put("resultBacklog", 0L);
        Map<String, Long> emptyBacklogByType = new java.util.LinkedHashMap<>();
        Map<String, Map<String, Number>> emptyLatencyByType = new java.util.LinkedHashMap<>();
        for (String type : effectMetricBuckets()) {
            emptyBacklogByType.put(type, 0L);
            emptyLatencyByType.put(type, Map.of("count", 0L, "totalMillis", 0L));
        }
        stats.put("resultBacklogByType", java.util.Collections.unmodifiableMap(emptyBacklogByType));
        stats.put("oldestPending", Map.of("height", 0L, "ageBlocks", 0L, "ageSeconds", 0d));
        stats.put("executionTotals", Map.of("confirmed", 0L, "failed", 0L, "parked", 0L));
        stats.put("latencyByType", java.util.Collections.unmodifiableMap(emptyLatencyByType));
        return java.util.Collections.unmodifiableMap(stats);
    }

    /** Stable metric buckets are available even before the local executor starts. */
    private List<String> effectMetricBuckets() {
        java.util.Set<String> configured = EffectRuntime.Settings.metricsTypesFrom(
                config.pluginSettings());
        if (configured.isEmpty()) {
            return List.of("all");
        }
        List<String> buckets = new ArrayList<>(new java.util.TreeSet<>(configured));
        buckets.add("other");
        return List.copyOf(buckets);
    }

    @Override
    public Optional<Map<String, Object>> effectRuntimeStatus(long height, int ordinal) {
        return generationUseOr(Optional.empty(), () -> {
            EffectRuntime currentFx = effectRuntime;
            return currentFx != null ? currentFx.statusOf(height, ordinal) : Optional.empty();
        });
    }

    @Override
    public boolean requeueEffect(long height, int ordinal) {
        return generationUseOr(false, () -> {
            EffectRuntime currentFx = effectRuntime;
            return currentFx != null && currentFx.requeue(height, ordinal);
        });
    }

    private boolean externalEffectsEnabled() {
        return Boolean.parseBoolean(
                config.pluginSettings().getOrDefault("effects.external.enabled", "false"));
    }

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect> claimEffects(
            String executorId, java.util.Set<String> types, int max, long leaseSeconds) {
        return generationUseOr(List.of(), () -> {
            EffectRuntime currentFx = effectRuntime;
            if (currentFx == null || !externalEffectsEnabled()) {
                return List.of();
            }
            return currentFx.claim(executorId, types, max, leaseSeconds);
        });
    }

    @Override
    public boolean reportEffect(String executorId, long height, int ordinal, boolean success,
                                byte[] externalRef, String reason) {
        return generationUseOr(false, () -> {
            EffectRuntime currentFx = effectRuntime;
            return currentFx != null && externalEffectsEnabled()
                    && currentFx.report(executorId, height, ordinal, success, externalRef, reason);
        });
    }

    @Override
    public boolean cancelEffect(long height, int ordinal, String reason) {
        return generationUseOr(false, () -> cancelEffectWithinGeneration(height, ordinal, reason));
    }

    private boolean cancelEffectWithinGeneration(long height, int ordinal, String reason) {
        AppLedgerStore currentLedger = ledger;
        if (!running.get() || currentLedger == null || !config.sequencingEnabled()) {
            return false;
        }
        var record = currentLedger.fxRecord(height, ordinal).orElse(null);
        if (record == null || currentLedger.fxClosed(height, ordinal)
                || record.result() != com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
            return false;
        }
        injectFxResult(new com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody(
                com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.BODY_VERSION,
                height, ordinal,
                com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome.CANCELLED,
                truncateReason(reason), null));
        log.info("App-chain '{}' effect {}/{} CANCELLED by operator ({})",
                config.chainId(), height, ordinal, reason);
        return true;
    }

    // ------------------------------------------------------------------
    // Key rotation (ADR 006 E4.5): staged member add / re-threshold / retire.
    // Height-versioned: each change starts a NEW membership epoch effective
    // from tip+1, so historical blocks always verify against the epoch that
    // finalized them. Requires the app ledger (rotation state persists there);
    // gossip-only nodes must rotate via config. Operator-coordinated runbook
    // in the user guide.
    // ------------------------------------------------------------------

    private static final String META_MEMBER_EPOCHS = "member_epochs";
    private final Object rotationLock = new Object();

    private void loadMemberOverride(AppLedgerStore ledgerStore) {
        String encoded = ledgerStore.metaString(META_MEMBER_EPOCHS);
        if (encoded == null || encoded.isBlank()) {
            return;
        }
        group.load(MemberGroup.decode(encoded));
        log.info("App-chain '{}' membership epochs loaded: {} epoch(s), current {} member(s) @ threshold {}",
                config.chainId(), group.history().size(), group.size(), group.threshold());
        if (!group.contains(signer.publicKeyHex())) {
            log.warn("This node's key {} is NOT in the current member epoch — it can observe but "
                    + "its submissions/votes will be rejected by peers", signer.publicKeyHex());
        }
    }

    private AppLedgerStore requireLedgerForRotation() {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            throw new IllegalStateException("Member rotation requires the app ledger (sequencing "
                    + "node); on a gossip-only node update yano.app-chain.members config instead");
        }
        return currentLedger;
    }

    private void applyEpoch(AppLedgerStore ledgerStore, Set<String> members, int threshold) {
        group.appendEpoch(ledgerStore.tipHeight() + 1, members, threshold);
        ledgerStore.metaPutString(META_MEMBER_EPOCHS, group.encode());
    }

    @Override
    public Set<String> members() {
        return group.members();
    }

    @Override
    public int effectiveThreshold() {
        return group.threshold();
    }

    @Override
    public void addMember(String publicKeyHex) {
        requireGenerationUse(() -> {
            addMemberWithinGeneration(publicKeyHex);
            return null;
        });
    }

    private void addMemberWithinGeneration(String publicKeyHex) {
        if (governedMode()) {
            // Governed mode (008.3): this call SUBMITS a governance command —
            // the change activates once threshold-many members do the same
            String normalized = normalizeMemberKeys(Set.of(publicKeyHex)).iterator().next();
            submitGovernance(GovernedMembership.encodeCommand(GovernedMembership.OP_ADD,
                    HexUtil.decodeHexString(normalized), 0, GovernedMembership.DEFAULT_ACTIVATION_LAG));
            return;
        }
        synchronized (rotationLock) {
            AppLedgerStore ledgerStore = requireLedgerForRotation();
            String normalized = normalizeMemberKeys(Set.of(publicKeyHex)).iterator().next();
            Set<String> updated = new HashSet<>(group.members());
            if (!updated.add(normalized)) {
                return; // already a member — idempotent
            }
            applyEpoch(ledgerStore, updated, group.threshold());
            log.info("App-chain '{}' member ADDED: {} (epoch from height {}, {} member(s), threshold {})",
                    config.chainId(), normalized, ledgerStore.tipHeight() + 1, group.size(), group.threshold());
        }
    }

    @Override
    public void removeMember(String publicKeyHex) {
        requireGenerationUse(() -> {
            removeMemberWithinGeneration(publicKeyHex);
            return null;
        });
    }

    private void removeMemberWithinGeneration(String publicKeyHex) {
        if (governedMode()) {
            String normalized = normalizeMemberKeys(Set.of(publicKeyHex)).iterator().next();
            submitGovernance(GovernedMembership.encodeCommand(GovernedMembership.OP_REMOVE,
                    HexUtil.decodeHexString(normalized), 0, GovernedMembership.DEFAULT_ACTIVATION_LAG));
            return;
        }
        synchronized (rotationLock) {
            AppLedgerStore ledgerStore = requireLedgerForRotation();
            String normalized = normalizeMemberKeys(Set.of(publicKeyHex)).iterator().next();
            if (normalized.equals(config.proposerKeyHex().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Cannot remove the configured proposer "
                        + "(rotate the proposer key via config + restart + admin/members/reset, "
                        + "or wait for S2 rotation)");
            }
            Set<String> updated = new HashSet<>(group.members());
            if (!updated.remove(normalized)) {
                throw new IllegalArgumentException("Not a member: " + normalized);
            }
            if (updated.size() < group.threshold()) {
                throw new IllegalArgumentException("Removing " + normalized + " would leave "
                        + updated.size() + " member(s), below threshold " + group.threshold()
                        + " — lower the threshold first");
            }
            applyEpoch(ledgerStore, updated, group.threshold());
            log.info("App-chain '{}' member RETIRED: {} (epoch from height {}, {} member(s), threshold {})",
                    config.chainId(), normalized, ledgerStore.tipHeight() + 1, group.size(), group.threshold());
        }
    }

    @Override
    public void setThreshold(int threshold) {
        requireGenerationUse(() -> {
            setThresholdWithinGeneration(threshold);
            return null;
        });
    }

    private void setThresholdWithinGeneration(int threshold) {
        if (governedMode()) {
            if (threshold < 1) {
                throw new IllegalArgumentException("Threshold must be >= 1");
            }
            submitGovernance(GovernedMembership.encodeCommand(GovernedMembership.OP_SET_THRESHOLD,
                    null, threshold, GovernedMembership.DEFAULT_ACTIVATION_LAG));
            return;
        }
        synchronized (rotationLock) {
            AppLedgerStore ledgerStore = requireLedgerForRotation();
            if (threshold < 1 || threshold > group.size()) {
                throw new IllegalArgumentException("Threshold must be in [1, " + group.size() + "]");
            }
            applyEpoch(ledgerStore, group.members(), threshold);
            log.info("App-chain '{}' threshold set to {} (epoch from height {}, {} member(s))",
                    config.chainId(), threshold, ledgerStore.tipHeight() + 1, group.size());
        }
    }

    @Override
    public void resetMembers() {
        requireGenerationUse(() -> {
            resetMembersWithinGeneration();
            return null;
        });
    }

    private void resetMembersWithinGeneration() {
        if (governedMode()) {
            log.warn("App-chain '{}': BREAK-GLASS membership reset on a GOVERNED chain — this "
                    + "node-local override deviates from chain-derived membership until the next "
                    + "governed change (ADR 008.3 §2.4)", config.chainId());
        }
        synchronized (rotationLock) {
            AppLedgerStore ledgerStore = requireLedgerForRotation();
            // Re-adopt the static config as a NEW epoch (history preserved so
            // pre-reset blocks still verify) — the escape hatch when a persisted
            // rotation must yield to a config change (e.g. proposer rotation).
            applyEpoch(ledgerStore, normalizeMemberKeys(config.memberKeysHex()), config.threshold());
            log.info("App-chain '{}' membership RESET to config (epoch from height {}, {} member(s), threshold {})",
                    config.chainId(), ledgerStore.tipHeight() + 1, group.size(), group.threshold());
        }
    }

    // ------------------------------------------------------------------
    // Admin operations (ADR 006 E5.4) — node-local, no consensus change
    // ------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicBoolean submissionsPaused =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void pauseSubmissions() {
        if (submissionsPaused.compareAndSet(false, true)) {
            log.info("App-chain '{}' local submissions PAUSED (admin)", config.chainId());
        }
    }

    @Override
    public void resumeSubmissions() {
        if (submissionsPaused.compareAndSet(true, false)) {
            log.info("App-chain '{}' local submissions RESUMED (admin)", config.chainId());
        }
    }

    @Override
    public boolean submissionsPaused() {
        return submissionsPaused.get();
    }

    @Override
    public int drainPool() {
        return requireGenerationUse(() -> {
            int dropped = pool.clear();
            log.info("App-chain '{}' pool drained: {} pending message(s) dropped (admin)",
                    config.chainId(), dropped);
            return dropped;
        });
    }

    @Override
    public boolean forceAnchor() {
        return generationUseOr(false, this::forceAnchorWithinGeneration);
    }

    private boolean forceAnchorWithinGeneration() {
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor != null && config.anchoringEnabled()
                && config.anchor() != null && config.anchor().scriptMode()) {
            return currentScriptAnchor.forceAnchorNow();
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor == null) {
            return false; // anchoring disabled
        }
        return currentAnchor.forceAnchorNow();
    }

    /**
     * Bootstrap the script anchor (ADR 008.4 §2.5, admin action): mint the
     * one-shot thread NFT and lock the initial datum at the anchor validator.
     * Only valid on the anchor leader with {@code anchor.mode: script}.
     */
    public Map<String, Object> bootstrapScriptAnchor() {
        return requireGenerationUse(this::bootstrapScriptAnchorWithinGeneration);
    }

    private Map<String, Object> bootstrapScriptAnchorWithinGeneration() {
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor == null)
            throw new IllegalStateException("Script-anchor service is not available on this node");
        if (!config.anchoringEnabled() || config.anchor() == null || !config.anchor().scriptMode())
            throw new IllegalStateException("Script anchoring is not enabled for this chain "
                    + "(set anchor.enabled: true and anchor.mode: script)");
        return currentScriptAnchor.bootstrap();
    }

    @Override
    public boolean unlockStaleRound() {
        return requireGenerationUse(this::unlockStaleRoundWithinGeneration);
    }

    private boolean unlockStaleRoundWithinGeneration() {
        AppChainEngine currentEngine = engine;
        if (currentEngine == null)
            throw new IllegalStateException("Sequencing is not enabled on this node");
        try {
            return currentEngine.clearStaleVoteLock();
        } catch (Exception e) {
            throw new IllegalStateException("Stale-lock unlock failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long snapshot(String snapshotPath) {
        return requireGenerationUse(() -> snapshotWithinGeneration(snapshotPath));
    }

    private long snapshotWithinGeneration(String snapshotPath) {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            throw new IllegalStateException("App chain has no ledger (sequencing disabled)");
        }
        currentLedger.createSnapshot(snapshotPath);
        long tip = currentLedger.tipHeight();
        // Signed manifest binds the checkpoint to this certified tip (008.1 I1.7)
        byte[] tipHash = tip > 0
                ? currentLedger.block(tip)
                        .map(com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec::blockHash)
                        .orElse(null)
                : null;
        String epochsEncoded = currentLedger.metaString(META_MEMBER_EPOCHS);
        SnapshotManifest.write(java.nio.file.Path.of(snapshotPath),
                config.chainId(), tip, tipHash, currentLedger.stateRoot(),
                epochsEncoded != null ? epochsEncoded.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null,
                currentLedger.metaString("anchor_last_tx"),
                currentLedger.metaLong("anchor_last_height", 0L),
                signer);
        return tip;
    }

    @Override
    public Optional<com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle> evidence(byte[] messageId) {
        return generationUseOr(Optional.empty(), () -> evidenceWithinGeneration(messageId));
    }

    private Optional<com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle>
            evidenceWithinGeneration(byte[] messageId) {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            return Optional.empty();
        }
        Optional<Long> heightOpt = currentLedger.messageHeight(messageId);
        if (heightOpt.isEmpty()) {
            return Optional.empty();
        }
        long messageHeight = heightOpt.get();

        // Evidence verifies against the epoch in effect at the message's height
        MemberGroup.Epoch epoch = group.epochAt(messageHeight);
        List<String> members = new ArrayList<>(epoch.members());
        long anchoredHeight = currentLedger.metaLong("anchor_last_height", 0L);
        byte[] anchoredBlockHash = currentLedger.metaBytes("anchor_last_block_hash");
        String anchorTx = currentLedger.metaString("anchor_last_tx");
        long anchorSlot = currentLedger.metaLong("anchor_last_slot", 0L);

        List<AppBlock> blocks = new ArrayList<>();
        com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle.AnchorRef anchorRef = null;

        // Include the anchor's prev-hash chain only when the anchor covers this
        // message AND the chain is bounded — an old message under a far-advanced
        // anchor would otherwise materialize a huge bundle (guard against OOM).
        // The record still verifies as finalized; it just isn't L1-linked here.
        boolean haveAnchor = anchoredHeight >= messageHeight
                && anchoredBlockHash != null && anchoredBlockHash.length > 0
                && anchorTx != null && !anchorTx.isBlank();
        // The anchor chain must stay within ONE membership epoch — a rotation
        // inside the range would need per-block member sets in the bundle.
        boolean sameEpoch = haveAnchor && group.epochAt(anchoredHeight) == epoch;
        boolean anchored = haveAnchor && sameEpoch
                && evidenceChainFits(messageHeight, anchoredHeight);
        if (haveAnchor && !anchored) {
            log.debug("Evidence for message at height {} omits anchor chain: gap {} reaches/exceeds {}",
                    messageHeight, anchoredHeight - messageHeight, MAX_EVIDENCE_CHAIN_BLOCKS);
        }
        long evidenceByteLimit = Math.min(config.blockMaxBytes(),
                EvidenceBundle.MAX_TOTAL_BLOCK_CBOR_BYTES);
        if (anchored) {
            long accumulatedBytes = 0;
            for (long h = messageHeight; ; h++) {
                Optional<AppBlock> block = currentLedger.block(h);
                if (block.isEmpty()) {
                    return Optional.empty();
                }
                int encodedBytes;
                try {
                    encodedBytes = AppBlockCodec.serialize(block.get()).length;
                } catch (RuntimeException malformed) {
                    return Optional.empty();
                }
                if (!evidenceBytesFit(accumulatedBytes, encodedBytes,
                        evidenceByteLimit)) {
                    log.debug("Evidence for message at height {} omits anchor chain: "
                                    + "canonical segment exceeds {} bytes",
                            messageHeight, evidenceByteLimit);
                    anchored = false;
                    blocks.clear();
                    break;
                }
                accumulatedBytes += encodedBytes;
                blocks.add(block.get());
                if (h == anchoredHeight) {
                    break;
                }
            }
        }
        if (!anchored) {
            Optional<AppBlock> block = currentLedger.block(messageHeight);
            if (block.isEmpty()) {
                return Optional.empty();
            }
            int encodedBytes;
            try {
                encodedBytes = AppBlockCodec.serialize(block.get()).length;
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
            if (!evidenceBytesFit(0, encodedBytes, evidenceByteLimit)) {
                return Optional.empty();
            }
            blocks.add(block.get());
        }
        if (anchored) {
            anchorRef = new com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle.AnchorRef(
                    anchoredHeight, HexUtil.encodeHexString(anchoredBlockHash), anchorTx, anchorSlot);
        }

        return Optional.of(new com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle(
                config.chainId(), HexUtil.encodeHexString(messageId), blocks, members,
                epoch.threshold(), anchorRef));
    }

    private static final long MAX_EVIDENCE_CHAIN_BLOCKS = 4096;

    static boolean evidenceChainFits(long messageHeight, long anchoredHeight) {
        return messageHeight >= 1 && anchoredHeight >= messageHeight
                && anchoredHeight - messageHeight < MAX_EVIDENCE_CHAIN_BLOCKS;
    }

    static boolean evidenceBytesFit(long accumulated, long next, long limit) {
        return accumulated >= 0 && next >= 0 && limit >= 0
                && next <= limit && accumulated <= limit - next;
    }

    @Override
    public Map<String, Object> status() {
        var generationUse = generationUseGate.tryAcquire();
        try (generationUse) {
            if (!generationUse.admitted()) {
                return stoppedStatus();
            }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chainId", config.chainId());
        status.put("memberKey", signer.publicKeyHex());
        status.put("members", group.size());
        status.put("threshold", group.threshold());
        status.put("running", running.get());
        status.put("sequencing", config.sequencingEnabled());
        if (config.sequencingEnabled()) {
            AppChainEngine currentEngine = engine;
            Map<String, Object> sequencer = currentEngine != null
                    ? new LinkedHashMap<>(currentEngine.sequencerStatus()) : new LinkedHashMap<>();
            String scheduledProposer = String.valueOf(
                    sequencer.getOrDefault("currentProposer", sequencer.get("proposer")));
            status.put("role", signer.publicKeyHex().equalsIgnoreCase(scheduledProposer)
                    ? "proposer" : "member");
            if (currentEngine != null) {
                sequencer.put("splitVotesObserved", currentEngine.splitVotesObserved());
            }
            status.put("sequencer", sequencer);
            status.put("tipHeight", tipHeight());
            status.put("stateRoot", HexUtil.encodeHexString(stateRoot()));
            status.put("stateMachine", stateMachine.id());
        }
        AnchorService currentAnchor = anchorService;
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentAnchor != null) {
            Map<String, Object> anchorStatus = currentAnchor.status();
            anchorStatus.put("lagBlocks",
                    Math.max(0, tipHeight() - currentAnchor.lastAnchoredHeight()));
            status.put("anchor", anchorStatus);
        } else if (currentScriptAnchor != null
                && ((config.anchoringEnabled() && config.anchor() != null && config.anchor().scriptMode())
                        || currentScriptAnchor.bootstrapped())) {
            // Script mode (008.4): leader always; followers once they adopt
            // the anchor identity from a verified sign request
            Map<String, Object> anchorStatus = currentScriptAnchor.status();
            anchorStatus.put("lagBlocks",
                    Math.max(0, tipHeight() - currentScriptAnchor.lastAnchoredHeight()));
            status.put("anchor", anchorStatus);
        }
        L1ObservationService currentObservations = observationService;
        if (currentObservations != null) {
            status.put("observers", currentObservations.status());
        }
        Map<String, String> activations = transitionActivationSettings(config.pluginSettings());
        if (!activations.isEmpty()) {
            // Transition activations apply to all state-machine/kernel logic,
            // not only effects. Keep this top-level so operators can compare
            // members even while effects.enabled=false (ADR-010.1 §2.3).
            status.put("activations", activations);
        }
        // Effects (ADR-010 F12 / 010.1 §2.3): the PARSED consensus-affecting
        // settings (single source of truth: EffectsSettings) and activation
        // entries, so operators can eyeball-compare members before an
        // activation height arrives.
        EffectsSettings effectsSettings = EffectsSettings.fromSettings(config.pluginSettings());
        if (effectsSettings.enabled()) {
            Map<String, Object> effectsStatus = new LinkedHashMap<>();
            effectsStatus.put("enabled", true);
            effectsStatus.put("maxPerBlock", effectsSettings.maxPerBlock());
            effectsStatus.put("maxPayloadBytes", effectsSettings.maxPayloadBytes());
            effectsStatus.put("maxExpiryBlocks", effectsSettings.maxExpiryBlocks());
            effectsStatus.put("defaultGate", effectsSettings.defaultGate().name());
            effectsStatus.put("outcomeCommitment", effectsSettings.outcomeCommitment().name());
            effectsStatus.put("strictReservedPrefix", effectsSettings.strictReservedPrefix());
            AppLedgerStore currentLedger = ledger;
            if (currentLedger != null) {
                effectsStatus.put("openCount", currentLedger.fxOpenCount());
            }
            // Preserve the nested field for existing effects-status consumers.
            if (!activations.isEmpty()) {
                effectsStatus.put("activations", activations);
            }
            EffectRuntime currentFx = effectRuntime;
            if (currentFx != null) {
                effectsStatus.put("executor", currentFx.stats());
            }
            status.put("effects", effectsStatus);
        }
        if (!sinkRunners.isEmpty()) {
            Map<String, Object> sinks = new LinkedHashMap<>();
            Map<String, Object> webhooks = new LinkedHashMap<>(); // back-compat (keyed by URL)
            for (SinkRunner runner : sinkRunners) {
                Map<String, Object> sinkStatus = new LinkedHashMap<>();
                sinkStatus.put("cursor", runner.cursor());
                sinkStatus.put("delivered", runner.deliveredCount());
                sinkStatus.put("lagBlocks", Math.max(0, tipHeight() - runner.cursor()));
                sinkStatus.put("failureCount", runner.failureCount());
                String errorType = runner.lastErrorType();
                sinkStatus.put("state", errorType == null ? "ACTIVE" : "DEGRADED");
                if (errorType != null) {
                    // Retain the legacy key but expose only the exception class;
                    // plugin messages may contain credentials/config values.
                    sinkStatus.put("lastError", errorType);
                    sinkStatus.put("lastErrorType", errorType);
                }
                sinks.put(runner.id(), sinkStatus);
                if (runner.id().startsWith("webhook:")) {
                    webhooks.put(runner.id().substring("webhook:".length()), sinkStatus);
                }
            }
            status.put("sinks", sinks);
            if (!webhooks.isEmpty()) {
                status.put("webhooks", webhooks); // pre-Wave-2 consumers
            }
        }
        if (!sinkActivationFailures.isEmpty()) {
            Map<String, Object> failures = new java.util.TreeMap<>();
            sinkActivationFailures.forEach((scheme, errorType) -> failures.put(
                    scheme, Map.of("state", "FAILED", "errorType", errorType)));
            status.put("sinkActivationFailures", Map.copyOf(failures));
        }
        status.put("poolSize", pool.size());
        status.put("poolCapacity", pool.capacity());
        status.put("submissionsPaused", submissionsPaused.get());
        status.put("submitted", submittedCount.get());
        status.put("received", receivedCount.get());
        status.put("relayed", relayedCount.get());
        status.put("duplicates", duplicateCount.get());
        status.put("seenIds", seenMessageIds.size());
        Map<String, Long> drops = new LinkedHashMap<>();
        for (var dropEntry : dropCounters.entrySet()) {
            drops.put(dropEntry.getKey(), dropEntry.getValue().get());
        }
        status.put("drops", drops);
        if (config.l1StabilityDepth() > 0) {
            status.put("l1RefDeferrals", l1RefDeferrals.get());
        }
        // Block timing + stall flag (008.1 I1.8)
        long lastBlockAt = lastBlockAtMillis;
        if (lastBlockAt > 0) {
            status.put("lastBlockAtMillis", lastBlockAt);
        }
        synchronized (recentBlockIntervals) {
            if (!recentBlockIntervals.isEmpty()) {
                status.put("blockIntervalMs", Math.round(recentBlockIntervals.stream()
                        .mapToLong(Long::longValue).average().orElse(0)));
            }
        }
        status.put("stalled", bestPeerTip > tipHeight()
                && System.currentTimeMillis() - lastProgressAt > STALL_WINDOW_MS);
        status.put("storedMessages", recentMessages.size());
        Map<String, Boolean> peers = new LinkedHashMap<>();
        Map<String, String> peerTransports = new LinkedHashMap<>();
        for (AppPeerLink peerClient : peerClients) {
            peers.put(peerClient.peerId(), peerClient.isConnected());
            peerTransports.put(peerClient.peerId(), peerClient.transport());
        }
        status.put("peers", peers);
        status.put("peerTransports", peerTransports);
        return status;
        }
    }

    private Map<String, Object> stoppedStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chainId", config.chainId());
        status.put("memberKey", signer.publicKeyHex());
        status.put("members", group.size());
        status.put("threshold", group.threshold());
        status.put("running", false);
        status.put("sequencing", config.sequencingEnabled());
        status.put("tipHeight", 0L);
        status.put("stateRoot", HexUtil.encodeHexString(new byte[32]));
        status.put("poolSize", pool.size());
        status.put("poolCapacity", pool.capacity());
        status.put("submissionsPaused", submissionsPaused.get());
        status.put("peers", Map.of());
        status.put("peerTransports", Map.of());
        // Consensus-affecting configuration remains observable before the
        // first start and between generations. This branch must stay
        // resource-free because the ledger and plugin products may already be
        // closed, but operators still need the same transition/effect values
        // when comparing members prior to activation.
        Map<String, String> activations = transitionActivationSettings(
                config.pluginSettings());
        if (!activations.isEmpty()) {
            status.put("activations", activations);
        }
        EffectsSettings effectsSettings = EffectsSettings.fromSettings(
                config.pluginSettings());
        if (effectsSettings.enabled()) {
            Map<String, Object> effectsStatus = new LinkedHashMap<>();
            effectsStatus.put("enabled", true);
            effectsStatus.put("maxPerBlock", effectsSettings.maxPerBlock());
            effectsStatus.put("maxPayloadBytes", effectsSettings.maxPayloadBytes());
            effectsStatus.put("maxExpiryBlocks", effectsSettings.maxExpiryBlocks());
            effectsStatus.put("defaultGate", effectsSettings.defaultGate().name());
            effectsStatus.put("outcomeCommitment", effectsSettings.outcomeCommitment().name());
            effectsStatus.put("strictReservedPrefix", effectsSettings.strictReservedPrefix());
            if (!activations.isEmpty()) {
                effectsStatus.put("activations", activations);
            }
            status.put("effects", effectsStatus);
        }
        return status;
    }

    // ------------------------------------------------------------------
    // Subsystem lifecycle
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return "app-chain";
    }

    @Override
    public void start() {
        if (permanentlyClosed.get()) {
            throw new IllegalStateException("Cannot start a closed app-chain subsystem");
        }
        if (!claimLifecycleTransition(LifecycleState.STARTING, "start")) {
            return;
        }

        Throwable failure = null;
        try {
            lifecycleTransitionLineage.set(Boolean.TRUE);
            startTransition();
        } catch (Throwable transitionFailure) {
            failure = transitionFailure;
        } finally {
            try {
                failure = finishLifecycleTransition(
                        LifecycleState.STARTING,
                        failure == null ? LifecycleState.RUNNING : LifecycleState.STOPPED,
                        failure);
            } finally {
                lifecycleTransitionLineage.remove();
            }
        }
        if (failure != null) {
            throw propagateLifecycleFailure(failure, "App-chain startup failed");
        }
    }

    private void startTransition() {
        if (!deferredRuntimeShutdown.isDone()) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "' is still draining a previous runtime callback; "
                    + "retry start after shutdown completes");
        }
        try {
            deferredRuntimeShutdown.join();
        } catch (java.util.concurrent.CompletionException previousShutdownFailure) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "' cannot restart because previous runtime cleanup failed",
                    previousShutdownFailure.getCause());
        }

        boolean generationOpened = false;
        try {
            OpenedGeneration openedGeneration = generationUseGate.open();
            generationOpened = true;
            // A provider product can be retained by any admitted subsystem
            // operation (for example sequencerStatus()).  Keep the selected
            // providers and their classloader alive until that whole operation,
            // not merely the immediately visible callback, has returned.
            pluginProviders.registerContributionCleanup(openedGeneration.quiescence());
            startOwnedResources(openedGeneration.token());
            running.set(true);
            // Admission is published only after every resource and scheduler
            // has been constructed. lifecycleState remains STARTING until the
            // outer finally publishes RUNNING, so workers cannot observe a
            // partially initialized generation.
            generationUseGate.activate();
        } catch (Throwable startupFailure) {
            // NodeKernel cannot stop a subsystem whose start() failed because
            // it is never added to the kernel's started list. Unwind here,
            // including resources acquired before running became true.
            running.set(false);
            Throwable outcome = startupFailure;
            if (generationOpened) {
                log.warn("Rolling back failed startup of app chain '{}' (errorType={})",
                        config.chainId(), startupFailure.getClass().getName());
                try {
                    releaseOwnedResources(startupFailure);
                } catch (Throwable cleanupFailure) {
                    outcome = LifecycleFailures.merge(outcome, cleanupFailure);
                }
            }
            throw propagateLifecycleFailure(outcome, "App-chain startup failed");
        }
    }

    /**
     * Claim one host lifecycle transition, waiting outside the transition lock
     * for an already-running host operation. Calls originating in plugin/
     * resource callbacks fail immediately: waiting there can self-deadlock a
     * start/stop transition that is waiting for that callback to return.
     */
    private boolean claimLifecycleTransition(LifecycleState requested, String action) {
        requireLifecycleHostCaller(action);
        while (true) {
            CompletableFuture<Void> waitFor;
            synchronized (lifecycleTransitionLock) {
                if (lifecycleTransitionOwner == Thread.currentThread()) {
                    throw lifecycleReentryFailure(action);
                }
                if (requested == LifecycleState.STARTING) {
                    if (lifecycleState == LifecycleState.RUNNING) {
                        return false;
                    }
                    if (lifecycleState == LifecycleState.STOPPED) {
                        beginLifecycleTransition(LifecycleState.STARTING);
                        return true;
                    }
                } else if (requested == LifecycleState.STOPPING) {
                    if (lifecycleState == LifecycleState.STOPPED && !hasOwnedResources()) {
                        return false;
                    }
                    if (lifecycleState == LifecycleState.RUNNING
                            || lifecycleState == LifecycleState.STOPPED) {
                        beginLifecycleTransition(LifecycleState.STOPPING);
                        return true;
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported lifecycle transition: " + requested);
                }
                waitFor = lifecycleTransitionCompletion;
            }
            // Completion dependents and lifecycle work run outside the lock.
            // A failed operation still publishes a terminal state; re-check it
            // so this concurrent host call can perform its own requested action.
            try {
                waitFor.join();
            } catch (java.util.concurrent.CompletionException ignored) {
                // State publication, not the prior caller's result, governs us.
            }
        }
    }

    private void requireLifecycleHostCaller(String action) {
        pluginProviders.requireContributionTeardownAllowed(
                action + " app-chain '" + config.chainId() + "'");
        if (generationUseGate.inUseByCurrentThread()
                || Boolean.TRUE.equals(lifecycleTransitionLineage.get())) {
            throw lifecycleReentryFailure(action);
        }
    }

    private IllegalStateException lifecycleReentryFailure(String action) {
        return new IllegalStateException("Cannot " + action + " app-chain '"
                + config.chainId() + "' from an app-chain callback/lifecycle transition");
    }

    private void beginLifecycleTransition(LifecycleState transition) {
        lifecycleState = transition;
        lifecycleTransitionOwner = Thread.currentThread();
        lifecycleTransitionCompletion = new CompletableFuture<>();
    }

    private Throwable finishLifecycleTransition(
            LifecycleState expected,
            LifecycleState terminal,
            Throwable failure
    ) {
        CompletableFuture<Void> completion;
        synchronized (lifecycleTransitionLock) {
            // Only the claiming thread writes terminal state. Avoid throwing
            // from this publication path: every Error must still wake waiters.
            if (lifecycleState != expected
                    || lifecycleTransitionOwner != Thread.currentThread()) {
                failure = LifecycleFailures.merge(failure,
                        new IllegalStateException("App-chain lifecycle transition ownership lost"));
                terminal = LifecycleState.STOPPED;
                running.set(false);
            }
            lifecycleState = terminal;
            lifecycleTransitionOwner = null;
            completion = lifecycleTransitionCompletion;
        }
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
        return failure;
    }

    private void startOwnedResources(long generationToken) {
        // stop() retires peer-link instances. Rebuild them for a supported
        // stop/start cycle (and after a failed start rollback).
        if (peerClients.isEmpty() && !config.peers().isEmpty()) {
            createPeerLinks();
        }

        log.info("Starting app chain '{}' (member: {}, members: {}, peers: {}, sequencing: {})",
                config.chainId(), signer.publicKeyHex(), group.size(), config.peers(),
                config.sequencingEnabled());
        logTransitionActivations();

        // Fail fast on a silently-degraded L1 linkage (ADR 008.1 I1.3): with
        // stability-depth or anchoring configured but no L1 event feed, every
        // block would carry l1Slot=0 / anchors would never confirm.
        if ((config.l1StabilityDepth() > 0 || config.anchoringEnabled()) && eventBus == null) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': l1.stability-depth/anchoring is configured but no L1 event feed is wired "
                    + "(EventBus is null) — refusing to start with a silent L1 linkage "
                    + "(set l1.stability-depth: 0 and disable anchoring for L1-less chains)");
        }
        // Rotating proposership is clocked by observed L1 slots (008.2 §2.1)
        if (sequencerMode instanceof RotatingSequencerMode && eventBus == null) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': sequencer.mode=rotating requires an L1 event feed (the window clock)");
        }

        if (config.sequencingEnabled()) {
            // Pre-open manifest verification (008.1 I1.7): a freshly restored
            // snapshot must carry a valid member-signed manifest and intact file
            // hashes BEFORE RocksDB mutates the files. No manifest = legacy
            // snapshot/normal dir; verified marker = already checked.
            com.fasterxml.jackson.databind.JsonNode restoredManifest =
                    SnapshotManifest.verifyPreOpen(java.nio.file.Path.of(ledgerPath),
                            group.members(), log);
            AppLedgerStore ledgerStore = new AppLedgerStore(ledgerPath, log);
            this.ledger = ledgerStore;
            // Integrity check catches a corrupt/partial restore (E5.3) at startup.
            if (!ledgerStore.verifyIntegrity()) {
                throw new IllegalStateException("App-chain ledger integrity check failed for '"
                        + config.chainId() + "' — tip state-root does not match the committed MPF root "
                        + "(corrupt or partial snapshot?)");
            }
            // Apply a persisted member-rotation override (E4.5) before wiring
            // the engine — rotated membership survives restarts and wins over
            // the static config.
            loadMemberOverride(ledgerStore);
            if (restoredManifest != null) {
                String epochsEncoded = ledgerStore.metaString(META_MEMBER_EPOCHS);
                long tipH = ledgerStore.tipHeight();
                byte[] tipHash = tipH > 0
                        ? ledgerStore.block(tipH)
                                .map(com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec::blockHash)
                                .orElse(null)
                        : null;
                SnapshotManifest.verifyPostOpen(restoredManifest, tipH, tipHash,
                        ledgerStore.stateRoot(),
                        epochsEncoded != null
                                ? epochsEncoded.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
                log.info("Restored snapshot bound to manifest: height {}, chain '{}'",
                        tipH, config.chainId());
            }
            // Strengthened integrity (I1.7): the tip block's recomputed hash and
            // its finality cert must hold against membership-at-height
            verifyTipCert(ledgerStore);
            // Own seq must stay above the finalized floor across restarts (I1.2)
            senderSeq.updateAndGet(v -> Math.max(v, ledgerStore.senderSeq(signer.publicKey())));
            AppChainEngine chainEngine = new AppChainEngine(
                    config,
                    ledgerStore,
                    pool,
                    stateMachine,
                    signer,
                    group,
                    sequencerMode,
                    Math.max(config.blockIntervalMs() * 5, 10_000),
                    config.maxBlockMessages(),
                    config.blockMaxBytes(),
                    (topic, body) -> buildAndDiffuse(topic, body, Math.max(60, config.maxTtlSeconds() / 6)),
                    log);
            // Publish ownership immediately: AppChainEngine creates its own
            // executor in the constructor, and later configuration may fail.
            // Startup rollback must therefore be able to close it.
            this.engine = chainEngine;
            pluginProviders.registerContributionCleanup(
                    chainEngine.closeCompletion().toCompletableFuture());
            chainEngine.setOnBlockFinalized(this::onBlockFinalized);
            chainEngine.setL1RefSupplier(this::stableL1Ref);
            chainEngine.setL1RefValidator(this::checkL1Ref);
            // L1 observations (008.4 I3.2) — misconfiguration fails start:
            // observers are consensus-critical and must match on all members
            this.observationService = L1ObservationService.fromRegistry(
                    config.pluginSettings(), Math.max(config.l1StabilityDepth(), 1) + 64,
                    pluginProviders, log);
            if (observationService != null) {
                if (config.l1StabilityDepth() <= 0) {
                    // Injection is gated on the stable L1 ref — without it a
                    // reorg-able fact could finalize into a chain that never
                    // rolls back (ADR 008.4 §3.1: slot <= block l1-ref REQUIRED)
                    throw new IllegalArgumentException("L1 observers require l1.stability-depth > 0 "
                            + "(observations are injected only once stability-deep — rollback safety)");
                }
                chainEngine.setObservationValidator(observationService::verify);
                log.info("App-chain '{}' L1 observers configured: {}",
                        config.chainId(), observationService.status().keySet());
            }
            chainEngine.setEnvelopeRelay(this::relay);
            if (governedMode()) {
                GovernedMembership governed = new GovernedMembership(
                        group,
                        config.proposerKeyHex(),
                        parseLongSetting("membership.approval-window-blocks",
                                GovernedMembership.DEFAULT_APPROVAL_WINDOW_BLOCKS),
                        log);
                governed.restore(ledgerStore);
                chainEngine.setGovernance(governed);
                log.info("App-chain '{}' membership mode: governed (approval = {} identical "
                        + "member commands on {})", config.chainId(), group.threshold(),
                        GovernedMembership.TOPIC);
            }
            // Deliver consensus messages that raced engine wiring (see route())
            AppMessage early;
            while ((early = earlyConsensus.poll()) != null) {
                chainEngine.onConsensusMessage(early);
            }

            boolean anchorScriptMode = config.anchor() != null && config.anchor().scriptMode();
            java.util.function.Supplier<AppChainEngine.L1Ref> anchorPointSupplier =
                    recentL1Points::peekLast;
            java.util.function.Supplier<Long> anchorSlotSupplier = () -> {
                AppChainEngine.L1Ref last = anchorPointSupplier.get();
                return last != null ? last.slot() : 0L;
            };
            if (config.anchoringEnabled() && !anchorScriptMode) {
                if (txSubmitter == null || utxoStateSupplier == null) {
                    log.warn("App-chain anchoring configured but L1 access is not wired — anchoring disabled");
                } else {
                    this.anchorService = new AnchorService(
                            config.chainId(),
                            config.anchor(),
                            ledgerStore,
                            txSubmitter,
                            utxoStateSupplier,
                            h -> ledgerStore.block(h).orElse(null),
                            ledgerStore::tipHeight,
                            protocolMagic,
                            log);
                    this.anchorService.wireFees(
                            () -> {
                                var supplier = anchorFeeParamsSupplier;
                                AnchorFeeParams params = supplier != null ? supplier.get() : null;
                                return params != null
                                        ? new AnchorService.FeeParams(params.minFeeA(), params.minFeeB())
                                        : null;
                            },
                            anchorSlotSupplier);
                }
            }
            // Script anchors (008.4): EVERY ledger member runs the co-sign
            // verifier (zero follower config — sign requests are verified
            // against this node's own ledger + L1 view); the node with
            // anchor.enabled + anchor.mode=script leads bootstrap/advances.
            if (txSubmitter != null && utxoStateSupplier != null) {
                boolean scriptLeader = config.anchoringEnabled() && anchorScriptMode;
                try {
                    var scriptConfig = config.anchor() != null && config.anchor().script() != null
                            ? config.anchor().script()
                            : com.bloxbean.cardano.yano.api.appchain.AppChainConfig.AnchorScriptConfig.defaults();
                    var anchorCfg = config.anchor() != null ? config.anchor()
                            : new com.bloxbean.cardano.yano.api.appchain.AppChainConfig.AnchorConfig(
                                    false, "", 0, 0, 0);
                    ScriptAnchorService scriptService = new ScriptAnchorService(
                            config.chainId(),
                            anchorCfg,
                            ledgerStore,
                            txSubmitter,
                            utxoStateSupplier,
                            h -> ledgerStore.block(h).orElse(null),
                            ledgerStore::tipHeight,
                            new AnchorScriptArtifacts(scriptConfig),
                            signer,
                            group::members,
                            group::threshold,
                            (topic, body) -> buildAndDiffuse(topic, body, 120),
                            scriptLeader,
                            protocolMagic,
                            log);
                    scriptService.wireTxPricing(
                            () -> {
                                var supplier = anchorProtocolParamsSupplier;
                                return supplier != null ? supplier.get() : null;
                            },
                            anchorPointSupplier);
                    this.scriptAnchorService = scriptService;
                } catch (Exception e) {
                    log.warn("Script-anchor service unavailable (errorType={})",
                            e.getClass().getName());
                }
            }
            buildSinks(ledgerStore);
            buildEffectRuntime(ledgerStore);
            subscribeL1Events(generationToken);
            this.queryLane = new QueryLane(generationToken, ledgerStore);
        }

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-chain-" + config.chainId());
            t.setDaemon(true);
            return t;
        });
        this.scheduler = exec;

        exec.scheduleWithFixedDelay(this::connectTick, 0, CONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::keepAliveTick,
                KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(() -> {
            pool.sweepExpired();
            int capEvicted = seenMessageIds.sweep(System.currentTimeMillis() / 1000);
            if (capEvicted > 0) {
                log.warn("App-chain '{}' seen-ids hard cap hit — evicted {} unexpired entries "
                        + "(dedup degraded to best-effort under this load)", config.chainId(), capEvicted);
            }
        }, 30, 30, TimeUnit.SECONDS);
        AppChainEngine currentEngine = engine;
        if (currentEngine != null) {
            // EVERY member ticks (008.2): the tick self-gates through the
            // sequencer mode (fixed = configured proposer only; rotating = the
            // member scheduled for the current window) and drives partial-round
            // re-gossip for locked heights on all members.
            exec.scheduleWithFixedDelay(currentEngine::proposeTick,
                    config.blockIntervalMs(), config.blockIntervalMs(), TimeUnit.MILLISECONDS);
            log.info("App-chain '{}' sequencer mode: {}", config.chainId(), sequencerMode.id());
            exec.scheduleWithFixedDelay(this::catchUpTick, 5, 5, TimeUnit.SECONDS);
        }
        if (!sinkRunners.isEmpty()) {
            // Sinks run on their OWN thread — a slow/blocked sink (e.g. an
            // unreachable Kafka broker) must never stall proposeTick / catch-up
            // / anchoring on the main scheduler.
            ScheduledExecutorService sinkExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "app-chain-sinks-" + config.chainId());
                t.setDaemon(true);
                return t;
            });
            this.sinkScheduler = sinkExec;
            sinkExec.scheduleWithFixedDelay(this::sinkTick, 5, 5, TimeUnit.SECONDS);
            log.info("App-chain finalized-stream sinks enabled: {}",
                    sinkRunners.stream().map(SinkRunner::id).toList());
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor != null) {
            exec.scheduleWithFixedDelay(
                    () -> generationUseOrNoop(currentAnchor::tick), 10, 10, TimeUnit.SECONDS);
            log.info("App-chain L1 anchoring enabled (address: {}, every {} blocks, label {})",
                    currentAnchor.anchorAddress(), config.anchor().everyBlocks(),
                    config.anchor().metadataLabel());
        }
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        boolean metadataAnchorLeader = config.anchoringEnabled()
                && config.anchor() != null && !config.anchor().scriptMode();
        if (currentScriptAnchor != null && !metadataAnchorLeader) {
            // Every member polls its OWN authenticated thread UTxO so
            // follower status/evidence/finality gates survive missed event
            // ordering and restart. ScriptAnchorService.tick() keeps all tx
            // construction/submission leader-only.
            exec.scheduleWithFixedDelay(
                    () -> generationUseOrNoop(() ->
                            publishConfirmedAnchor(currentScriptAnchor.tick())),
                    10, 10, TimeUnit.SECONDS);
            if (config.anchoringEnabled()
                    && config.anchor() != null && config.anchor().scriptMode()) {
                log.info("App-chain L1 SCRIPT anchoring enabled (008.4): wallet={}, every {} blocks",
                        currentScriptAnchor.anchorAddress(), config.anchor().everyBlocks());
            }
        }
        if (config.retentionEnabled()) {
            exec.scheduleWithFixedDelay(this::retentionTick, 30, 30, TimeUnit.SECONDS);
            log.info("App-chain retention enabled: bodies pruned below L1_FINAL anchor "
                    + "(keeping the most-recent {} block(s))", config.retentionKeepBlocks());
        }
        EffectRuntime currentFx = effectRuntime;
        if (currentFx != null) {
            // Own thread, like sinks: a slow external system must never stall
            // proposeTick / catch-up / anchoring (ADR-010 F5)
            long tickMs = currentFx.settings().tickMs();
            ScheduledExecutorService fxExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "app-chain-fx-" + config.chainId());
                t.setDaemon(true);
                return t;
            });
            this.fxScheduler = fxExec;
            fxExec.scheduleWithFixedDelay(
                    () -> generationUseOrNoop(currentFx::tick), tickMs, tickMs,
                    TimeUnit.MILLISECONDS);
            if (config.sequencingEnabled()) {
                // Result loop (ADR-010 F8): locally-terminal CHAIN outcomes are
                // injected as member-signed ~fx/result messages until the chain
                // records the closure (first result wins; re-injection throttled)
                fxExec.scheduleWithFixedDelay(this::fxResultInjectionTick, 5, 5, TimeUnit.SECONDS);
            }
        }
        if (Boolean.parseBoolean(config.pluginSettings().getOrDefault("effects.enabled", "false"))) {
            exec.scheduleWithFixedDelay(this::fxRetentionTick, 60, 60, TimeUnit.SECONDS);
        }
    }

    /** Consensus-affecting activation entries, sorted for stable status/log comparison. */
    static Map<String, String> transitionActivationSettings(Map<String, String> settings) {
        Map<String, String> activations = new TreeMap<>();
        for (var entry : settings.entrySet()) {
            String key = entry.getKey();
            boolean effectActivation = key.startsWith("effects.activations.")
                    && key.length() > "effects.activations.".length();
            int machineMarker = key.indexOf(".activations.", "machines.".length());
            boolean machineActivation = key.startsWith("machines.")
                    && machineMarker > "machines.".length()
                    && machineMarker + ".activations.".length() < key.length();
            if (effectActivation || machineActivation) {
                activations.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(activations);
    }

    /** Fail fast and make the activation schedule visible on every startup. */
    private void logTransitionActivations() {
        for (var entry : transitionActivationSettings(config.pluginSettings()).entrySet()) {
            long height;
            try {
                height = Long.parseLong(entry.getValue().trim());
            } catch (RuntimeException e) {
                throw invalidActivation(entry.getKey(), entry.getValue(), e);
            }
            if (height <= 0) {
                throw invalidActivation(entry.getKey(), entry.getValue(), null);
            }
            log.info("App-chain '{}' transition activation {}={}",
                    config.chainId(), entry.getKey(), height);
        }
    }

    private IllegalArgumentException invalidActivation(String key, String value, Throwable cause) {
        String message = "App-chain '" + config.chainId() + "': transition activation '" + key
                + "' must be a positive block height, got '" + value + "'";
        return cause != null ? new IllegalArgumentException(message, cause)
                : new IllegalArgumentException(message);
    }

    /** Prune message bodies below the L1_FINAL anchor horizon (E4.4). */
    private void retentionTick() {
        generationUseOrNoop(this::retentionTickWithinGeneration);
    }

    private void retentionTickWithinGeneration() {
        AppLedgerStore currentLedger = ledger;
        if (!running.get() || currentLedger == null) {
            return;
        }
        long anchored = currentLedger.metaLong("anchor_last_height", 0L);
        long horizon = anchored - config.retentionKeepBlocks();
        // Never prune ahead of a sink that hasn't delivered those blocks yet —
        // otherwise the sink would later deliver bodies already stripped (data
        // loss). Cap the horizon at the slowest sink cursor.
        for (SinkRunner runner : sinkRunners) {
            horizon = Math.min(horizon, runner.cursor());
        }
        if (horizon > currentLedger.pruneCursor()) {
            try {
                currentLedger.pruneBodiesBelow(horizon);
            } catch (Exception e) {
                log.warn("App-chain retention tick failed (errorType={})",
                        e.getClass().getName());
            }
        }
    }

    /**
     * Inject {@code ~fx/result} messages for locally-terminal CHAIN outcomes
     * (ADR-010 F8) — the same internal member-signed path as governance and
     * L1 observations; external submit() rejects {@code ~} topics. Repeats
     * (throttled) until the interpreter's closure is observed; duplicates are
     * deterministic no-ops (first result wins).
     */
    private void fxResultInjectionTick() {
        generationUseOrNoop(this::fxResultInjectionTickWithinGeneration);
    }

    private void fxResultInjectionTickWithinGeneration() {
        EffectRuntime currentFx = effectRuntime;
        if (!running.get() || currentFx == null) {
            return;
        }
        for (EffectRuntime.Injection injection : currentFx.pendingInjections(32, 60_000)) {
            // Per-iteration guard: one poisoned entry must never starve the batch
            try {
                com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody body =
                        new com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody(
                                com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.BODY_VERSION,
                                injection.height(), injection.ordinal(),
                                injection.confirmed()
                                        ? com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome.CONFIRMED
                                        : com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome.FAILED,
                                injection.confirmed()
                                        ? truncateRef(injection.externalRef())
                                        : truncateReason(injection.reason()),
                                injection.confirmed() ? injection.detailHash() : null);
                injectFxResult(body);
            } catch (Exception e) {
                log.warn("App-chain '{}' result injection for {}/{} failed (errorType={})",
                        config.chainId(), injection.height(), injection.ordinal(),
                        e.getClass().getName());
            }
        }
    }

    private static byte[] truncateReason(String reason) {
        return truncateRef((reason != null ? reason : "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] truncateRef(byte[] ref) {
        int max = com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.MAX_EXTERNAL_REF_BYTES;
        return ref == null ? new byte[0]
                : ref.length <= max ? ref : java.util.Arrays.copyOf(ref, max);
    }

    /** Pool + relay a member-signed ~fx/result (internal path, like injectObservation). */
    private void injectFxResult(com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody body) {
        AppMessage message = buildSigned(
                com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.TOPIC,
                body.encode(), config.defaultTtlSeconds());
        AppMsgPool.AddResult added = pool.add(message);
        if (added == AppMsgPool.AddResult.FULL) {
            countDrop("pool_full");
            return; // retried on a later injection tick
        }
        if (added == AppMsgPool.AddResult.ADDED) {
            relay(message);
            record(message, ReceivedAppMessage.Source.LOCAL);
        }
    }

    /**
     * Effect outbox retention (ADR-010 F3) — independent of body retention
     * and of anchoring: resolved records prune once older than
     * {@code effects.retention.keep-blocks} behind the tip, capped at the
     * executor's intake cursor. The ledger enforces the safety rules (live
     * obligations and future-expiry records never prune).
     */
    private void fxRetentionTick() {
        generationUseOrNoop(this::fxRetentionTickWithinGeneration);
    }

    private void fxRetentionTickWithinGeneration() {
        AppLedgerStore currentLedger = ledger;
        if (!running.get() || currentLedger == null) {
            return;
        }
        long keepBlocks = parseLongSetting("effects.retention.keep-blocks", 100_000);
        // NEVER prune inside the consensus result window: the interpreter
        // consults the fx CF there, and CF contents must be identical on
        // every node or incorporation forks (ADR-010 F8)
        long resultWindow = parseLongSetting("effects.result-window-blocks", 100_000);
        long fxHorizon = currentLedger.tipHeight() - Math.max(keepBlocks, resultWindow);
        long intakeCursor = currentLedger.fxIntakeCursor(-1);
        if (intakeCursor >= 0) {
            fxHorizon = Math.min(fxHorizon, intakeCursor);
        }
        if (fxHorizon > 0) {
            try {
                int pruned = currentLedger.fxPruneBelow(fxHorizon);
                if (pruned > 0) {
                    log.info("App-chain '{}' pruned {} resolved effect record(s) below height {}",
                            config.chainId(), pruned, fxHorizon);
                }
            } catch (Exception e) {
                log.warn("App-chain effect retention failed (errorType={})",
                        e.getClass().getName());
            }
        }
    }

    /**
     * Tip binding check on every start (008.1 I1.7): the stored tip block must
     * re-hash to the stored tip hash, and its finality cert must reach the
     * threshold of valid member signatures at that height. Catches snapshots
     * whose stored roots merely agree with each other.
     */
    private void verifyTipCert(AppLedgerStore ledgerStore) {
        long tipH = ledgerStore.tipHeight();
        if (tipH == 0) {
            return;
        }
        AppBlock tipBlock = ledgerStore.block(tipH).orElseThrow(() -> new IllegalStateException(
                "App-chain '" + config.chainId() + "': tip block " + tipH + " missing from the ledger"));
        byte[] recomputed = com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec.blockHash(tipBlock);
        if (!java.util.Arrays.equals(recomputed, ledgerStore.tipHash())) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': tip block hash does not recompute — corrupt or tampered ledger");
        }
        int valid = 0;
        Set<String> seen = new HashSet<>();
        for (FinalityCert.Signature sig : tipBlock.cert().signatures()) {
            String signerHex = HexUtil.encodeHexString(sig.signer()).toLowerCase(Locale.ROOT);
            if (!group.containsAt(signerHex, tipH) || !seen.add(signerHex)) {
                continue;
            }
            if (AppMessageSigner.verify(sig.signature(), recomputed, sig.signer())) {
                valid++;
            }
        }
        if (valid < group.thresholdAt(tipH)) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': tip finality cert has " + valid + " valid member signature(s), below threshold "
                    + group.thresholdAt(tipH) + " — corrupt or tampered ledger");
        }
    }

    /** Track applied L1 blocks: stable-depth reference for proposals + anchor confirmation. */
    private void subscribeL1Events(long generationToken) {
        if (eventBus == null) {
            return;
        }
        // Bind the subscription to this exact resource generation.  An event
        // callback admitted before stop may outlive field unpublication, and
        // a late callback from a retired subscription must never observe the
        // next generation's services.
        L1GenerationServices services = new L1GenerationServices(
                generationToken, anchorService, scriptAnchorService, observationService);
        eventSubscriptions.addAll(acquireL1Subscriptions(
                eventBus,
                event -> onL1BlockApplied(event, services),
                event -> onL1Rollback(event, services)));
    }

    private record L1GenerationServices(
            long generationToken,
            AnchorService anchor,
            ScriptAnchorService scriptAnchor,
            L1ObservationService observations
    ) {
    }

    /**
     * Acquire the applied/rollback pair transactionally. A half-installed L1
     * view is worse than a failed start: stability, observations and anchors
     * would evolve from different event streams.
     */
    static List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle>
            acquireL1Subscriptions(
                    EventBus eventBus,
                    java.util.function.Consumer<com.bloxbean.cardano.yano.api.events.BlockAppliedEvent>
                            applied,
                    java.util.function.Consumer<com.bloxbean.cardano.yano.api.events.RollbackEvent>
                            rollback
            ) {
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(rollback, "rollback");
        List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> acquired =
                new ArrayList<>(2);
        try {
            acquired.add(Objects.requireNonNull(eventBus.subscribe(
                            com.bloxbean.cardano.yano.api.events.BlockAppliedEvent.class,
                            ctx -> applied.accept(ctx.event()),
                            com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()),
                    "EventBus returned null BlockAppliedEvent subscription"));
            acquired.add(Objects.requireNonNull(eventBus.subscribe(
                            com.bloxbean.cardano.yano.api.events.RollbackEvent.class,
                            ctx -> rollback.accept(ctx.event()),
                            com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()),
                    "EventBus returned null RollbackEvent subscription"));
            return List.copyOf(acquired);
        } catch (Throwable subscriptionFailure) {
            Throwable outcome = subscriptionFailure;
            for (int i = acquired.size() - 1; i >= 0; i--) {
                try {
                    acquired.get(i).close();
                } catch (Throwable closeFailure) {
                    outcome = LifecycleFailures.merge(outcome, closeFailure);
                }
            }
            throw propagateLifecycleFailure(outcome,
                    "Failed to install app-chain L1 subscriptions");
        }
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

    private void onL1BlockApplied(
            com.bloxbean.cardano.yano.api.events.BlockAppliedEvent event,
            L1GenerationServices services) {
        generationUseOrNoop(services.generationToken(),
                () -> onL1BlockAppliedWithinGeneration(event, services));
    }

    private void onL1BlockAppliedWithinGeneration(
            com.bloxbean.cardano.yano.api.events.BlockAppliedEvent event,
            L1GenerationServices services) {
        runL1Phase("reference tracking", () -> {
            recentL1Points.addLast(new AppChainEngine.L1Ref(event.slot(),
                    HexUtil.decodeHexString(event.blockHash())));
            while (recentL1Points.size() > Math.max(config.l1StabilityDepth(), 1) + 64) {
                recentL1Points.pollFirst();
            }
        });

        // Observation and anchor confirmation are deliberately independent.
        // A plugin observer failure must not consume the only event carrying
        // an anchor transaction, and an anchor failure must not prevent the
        // local L1 verification window from advancing.
        runL1Phase("observation", () -> {
            // L1 observations (008.4 I3.2): EVERY member recomputes (feeds the
            // verification window). Injection is STABILITY-GATED for rollback
            // safety — the app chain never rolls back, so a fact may only be
            // sequenced once it is l1.stability-depth confirmations old. All
            // members drain at the same L1 block; the scheduled proposer
            // injects (the message then replicates via the shared pool).
            L1ObservationService currentObservations = services.observations();
            if (currentObservations != null && event.block() != null) {
                currentObservations.onL1Block(event.slot(),
                        HexUtil.decodeHexString(event.blockHash()), event.block());
                AppChainEngine.L1Ref stable = stableL1Ref();
                if (stable != null) {
                    List<com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation> ready =
                            currentObservations.drainInjectable(stable.slot());
                    if (!ready.isEmpty() && isScheduledProposer()) {
                        for (var observation : ready) {
                            runL1Phase("observation injection",
                                    () -> injectObservation(observation));
                        }
                    }
                }
            }
        });

        runL1Phase("anchor confirmation", () -> {
            AnchorService currentAnchor = services.anchor();
            ScriptAnchorService currentScriptAnchor = services.scriptAnchor();
            if ((currentAnchor != null || currentScriptAnchor != null) && event.block() != null
                    && event.block().getTransactionBodies() != null) {
                List<Integer> invalidTransactions = event.block().getInvalidTransactions();
                java.util.Set<Integer> invalidIndexes = invalidTransactions != null
                        ? new java.util.HashSet<>(invalidTransactions) : java.util.Set.of();
                List<String> txHashes = new ArrayList<>();
                List<com.bloxbean.cardano.yaci.core.model.TransactionBody> transactionBodies =
                        event.block().getTransactionBodies();
                for (int index = 0; index < transactionBodies.size(); index++) {
                    if (!invalidIndexes.contains(index)) {
                        txHashes.add(transactionBodies.get(index).getTxHash());
                    }
                }
                AnchorService.ConfirmedAnchor confirmed = currentAnchor != null
                        ? currentAnchor.onL1Block(event.slot(), txHashes) : null;
                if (confirmed == null && currentScriptAnchor != null) {
                    confirmed = currentScriptAnchor.onL1Block(event.slot(), txHashes);
                }
                publishConfirmedAnchor(confirmed);
            }
        });
    }

    private void publishConfirmedAnchor(AnchorService.ConfirmedAnchor confirmed) {
        if (confirmed == null || eventBus == null) {
            return;
        }
        runL1Phase("anchor event publication", () ->
                eventBus.publish(new com.bloxbean.cardano.yano.api.events.AppChainAnchoredEvent(
                                config.chainId(), confirmed.fromHeight(), confirmed.toHeight(),
                                confirmed.txHash(), confirmed.l1Slot()),
                        EventMetadata.builder().build(), PublishOptions.builder().build()));
    }

    private void onL1Rollback(
            com.bloxbean.cardano.yano.api.events.RollbackEvent event,
            L1GenerationServices services) {
        generationUseOrNoop(services.generationToken(),
                () -> onL1RollbackWithinGeneration(event, services));
    }

    private void onL1RollbackWithinGeneration(
            com.bloxbean.cardano.yano.api.events.RollbackEvent event,
            L1GenerationServices services) {
        final long targetSlot;
        try {
            targetSlot = event.target() != null ? event.target().getSlot() : 0;
        } catch (Throwable failure) {
            reportL1PhaseFailure("rollback target", failure);
            return;
        }
        runL1Phase("reference rollback", () ->
                recentL1Points.removeIf(ref -> ref.slot() > targetSlot));
        runL1Phase("metadata-anchor rollback", () -> {
            AnchorService currentAnchor = services.anchor();
            if (currentAnchor != null) {
                currentAnchor.onL1Rollback(targetSlot);
            }
        });
        runL1Phase("script-anchor rollback", () -> {
            ScriptAnchorService currentScriptAnchor = services.scriptAnchor();
            if (currentScriptAnchor != null) {
                currentScriptAnchor.onL1Rollback(targetSlot);
            }
        });
        runL1Phase("observation rollback", () -> {
            L1ObservationService currentObservations = services.observations();
            if (currentObservations != null) {
                currentObservations.onL1Rollback(targetSlot);
            }
        });
    }

    private void runL1Phase(String phase, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            reportL1PhaseFailure(phase, failure);
        }
    }

    private void reportL1PhaseFailure(String phase, Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatal(failure);
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            log.warn("App-chain L1 {} failed (errorType={})",
                    phase, failure.getClass().getName());
        } catch (Throwable loggingFailure) {
            // A recoverable logging backend failure must not cancel the event
            // subscription or prevent later independent phases from running.
            LifecycleFailures.rethrowIfProcessFatal(loggingFailure);
            if (loggingFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final AtomicLong l1RefDeferrals = new AtomicLong();

    /**
     * Follower-side verdict on a proposed L1 ref against this node's OWN
     * observed points (ADR 008.1 I1.3). The window covers stability-depth + 64
     * blocks, so an in-window slot that is absent means the proposer's ref does
     * not exist on our chain — fabricated or rolled back — a hard MISMATCH.
     */
    private AppChainEngine.L1RefVerdict checkL1Ref(long slot, byte[] blockHash) {
        AppChainEngine.L1Ref newest = recentL1Points.peekLast();
        AppChainEngine.L1Ref oldest = recentL1Points.peekFirst();
        if (newest == null) {
            return AppChainEngine.L1RefVerdict.UNKNOWN; // no local view yet (restart)
        }
        if (slot > newest.slot()) {
            l1RefDeferrals.incrementAndGet();
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        if (slot < oldest.slot()) {
            return AppChainEngine.L1RefVerdict.UNKNOWN; // older than our window
        }
        int fromEnd = 0;
        AppChainEngine.L1Ref match = null;
        for (var iterator = recentL1Points.descendingIterator(); iterator.hasNext(); ) {
            AppChainEngine.L1Ref ref = iterator.next();
            if (ref.slot() == slot) {
                match = ref;
                break;
            }
            fromEnd++;
        }
        if (match == null) {
            return AppChainEngine.L1RefVerdict.MISMATCH; // in-window slot we never saw
        }
        if (!java.util.Arrays.equals(match.blockHash(), blockHash)) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        if (fromEnd < config.l1StabilityDepth()) {
            l1RefDeferrals.incrementAndGet();
            return AppChainEngine.L1RefVerdict.AHEAD; // not deep enough yet in OUR view
        }
        return AppChainEngine.L1RefVerdict.OK;
    }

    /**
     * L1 point at least l1StabilityDepth blocks below the observed tip, from the
     * subsystem's own view of applied blocks. Null when depth is 0/unknown.
     */
    private AppChainEngine.L1Ref stableL1Ref() {
        int depth = config.l1StabilityDepth();
        if (depth <= 0 || recentL1Points.size() <= depth) {
            return null;
        }
        // deque: oldest..newest; pick the element depth-from-the-end
        int index = recentL1Points.size() - 1 - depth;
        int i = 0;
        for (AppChainEngine.L1Ref ref : recentL1Points) {
            if (i++ == index) {
                return ref;
            }
        }
        return null;
    }

    private final List<FinalizedBlockListener> finalizedListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SinkRunner> sinkRunners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * Auxiliary activation diagnostics retained for health/ADR-011.4. Values
     * deliberately contain only exception class names: plugin-supplied error
     * messages may contain configuration or secret material.
     */
    private final java.util.concurrent.ConcurrentMap<String, String> sinkActivationFailures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Build finalized-stream sinks: built-in webhooks + selected plugin factories (E3.2). */
    private void buildSinks(AppLedgerStore ledgerStore) {
        sinkActivationFailures.clear();
        Set<String> claimedIds = new HashSet<>();
        Set<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> claimedInstances =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (String url : config.webhookUrls()) {
            var sink = new WebhookSink(url, config.chainId(), log);
            String id = requireSinkId(sink);
            if (!claimedIds.add(id)) {
                throw new IllegalArgumentException("Duplicate finalized-stream sink id '" + id + "'");
            }
            claimedInstances.add(sink);
            SinkRunner runner = new SinkRunner(sink, id, ledgerStore, log);
            SinkRunner.initializeCursors(ledgerStore, List.of(runner));
            sinkRunners.add(runner);
        }
        // Auxiliary sinks are intentionally isolated: a filtered, broken or
        // misconfigured sink is diagnosed but cannot take down consensus.
        for (String scheme : configuredSchemes("sinks.")) {
            java.util.Map<String, String> sinkConfig = sinkConfigFor(scheme);
            List<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> stagedSinks =
                    new ArrayList<>();
            List<SinkRunner> stagedRunners = new ArrayList<>();
            try {
                FinalizedStreamSinkFactory factory = pluginProviders.find(
                                FinalizedStreamSinkFactory.class, scheme)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No selected FinalizedStreamSinkFactory for scheme '" + scheme
                                        + "' (available: "
                                        + pluginProviders.names(FinalizedStreamSinkFactory.class) + ")"));
                List<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> sinks =
                        Objects.requireNonNull(factory.create(config.chainId(), sinkConfig),
                                "FinalizedStreamSinkFactory.create returned null");
                // Take ownership of every fresh non-null product in the
                // complete batch before invoking any product callback. If a
                // malformed/null/duplicate entry appears in the middle, a
                // valid tail product must still be rolled back.
                RuntimeException ownershipFailure = null;
                for (var sink : sinks) {
                    if (sink == null) {
                        if (ownershipFailure == null) {
                            ownershipFailure = new NullPointerException(
                                    "FinalizedStreamSinkFactory.create returned a null sink");
                        }
                        continue;
                    }
                    if (!claimedInstances.add(sink)) {
                        if (ownershipFailure == null) {
                            ownershipFailure = new IllegalArgumentException(
                                    "FinalizedStreamSinkFactory '" + scheme
                                            + "' returned the same sink instance more than once");
                        }
                        continue;
                    }
                    stagedSinks.add(sink);
                }
                if (ownershipFailure != null) {
                    throw ownershipFailure;
                }
                Set<String> stagedIds = new HashSet<>();
                for (var sink : stagedSinks) {
                    String id = requireSinkId(sink);
                    if (claimedIds.contains(id) || !stagedIds.add(id)) {
                        throw new IllegalArgumentException(
                                "Duplicate finalized-stream sink id '" + id + "'");
                    }
                    // Snapshot the validated identity exactly once. The
                    // runner never re-enters a stateful plugin id() callback.
                    stagedRunners.add(new SinkRunner(sink, id, ledgerStore, log));
                }
                for (var runner : stagedRunners) {
                    log.info("App-chain sink '{}' validated via {} plugin", runner.id(), scheme);
                }
                // Cursor migration is a two-phase operation: all remaining
                // plugin callbacks prepare first, then one atomic RocksDB
                // batch commits only after the whole sink batch validates.
                SinkRunner.initializeCursors(ledgerStore, stagedRunners);
                claimedIds.addAll(stagedIds);
                sinkActivationFailures.remove(scheme);
                // Final publication step. A malformed or stateful callback
                // above cannot leave a closed runner live or an inactive
                // persisted cursor.
                sinkRunners.addAll(stagedRunners);
            } catch (Throwable e) {
                closeUnownedSinks(stagedSinks, e);
                if (e instanceof Error fatal) {
                    throw fatal;
                }
                sinkActivationFailures.put(scheme, e.getClass().getName());
                // Plugin messages may include credentials from sink config.
                log.error("Failed to build '{}' sink(s) (errorType={})",
                        scheme, e.getClass().getName());
            }
        }
        if (!sinkRunners.isEmpty()) {
            // One registry-owned lifetime signal covers the complete sink set
            // published by this start cycle. It remains pending through an
            // interrupt-resistant delivery and through every product close,
            // keeping bundle providers/lifecycle/class-loader resources alive.
            pluginProviders.registerContributionCleanup(CompletableFuture.allOf(
                    sinkRunners.stream()
                            .map(SinkRunner::closeCompletion)
                            .toArray(CompletableFuture[]::new)));
        }
    }

    private static String requireSinkId(
            com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink sink) {
        return requireOperationalProductId(
                sink.id(), "FinalizedStreamSink.id");
    }

    private void closeUnownedSinks(
            List<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> sinks,
            Throwable failure) {
        Throwable outcome = failure;
        Set<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> closed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ListIterator<com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink> iterator =
                sinks.listIterator(sinks.size());
        while (iterator.hasPrevious()) {
            var sink = iterator.previous();
            if (!closed.add(sink)) {
                continue;
            }
            try {
                sink.close();
            } catch (Throwable closeFailure) {
                outcome = LifecycleFailures.merge(outcome, closeFailure);
            }
        }
        sinks.clear();
        if (outcome != failure) {
            throw propagateLifecycleFailure(outcome, "Finalized sink cleanup failed");
        }
    }

    /** Config sub-map for a sink scheme: yano.app-chain.sinks.<scheme>.* → stripped keys. */
    private java.util.Map<String, String> sinkConfigFor(String scheme) {
        return strippedSubMap("sinks." + scheme + ".");
    }

    /** Config sub-map for an executor scheme: yano.app-chain.effects.executors.<scheme>.* → stripped. */
    private java.util.Map<String, String> executorConfigFor(String scheme) {
        return strippedSubMap("effects.executors." + scheme + ".");
    }

    private java.util.Map<String, String> strippedSubMap(String prefix) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (var entry : config.pluginSettings().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    /** Configured factory selectors below a namespace, in deterministic order. */
    private SortedSet<String> configuredSchemes(String prefix) {
        SortedSet<String> schemes = new TreeSet<>();
        for (String key : config.pluginSettings().keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String remainder = key.substring(prefix.length());
            int dot = remainder.indexOf('.');
            if (dot > 0) {
                schemes.add(remainder.substring(0, dot));
            }
        }
        return schemes;
    }

    /**
     * Build the Effect Runtime (ADR-010 F5) when this node is an executor:
     * built-in webhook executor when configured, plus selected
     * {@link AppEffectExecutorFactory} plugins — mirroring buildSinks. OFF by default; at
     * most one executor node per effect-type partition is the operator's
     * responsibility (ADR-010 F6), with idempotency as the safety net.
     */
    private void buildEffectRuntime(AppLedgerStore ledgerStore) {
        final boolean executorEnabled;
        try {
            executorEnabled = strictBooleanPluginSetting("effects.executor.enabled", false);
        } catch (RuntimeException e) {
            throw pluginActivationFailure("effect runtime settings for chain '"
                    + config.chainId() + "'", e);
        }
        if (!executorEnabled) {
            return;
        }
        EffectRuntime.Settings runtimeSettings;
        try {
            runtimeSettings = EffectRuntime.Settings.fromSettings(config.pluginSettings());
        } catch (RuntimeException e) {
            throw new PluginActivationException(
                    "Invalid effects.executor.* settings for app-chain '"
                            + config.chainId() + "'", e);
        }
        final boolean effectsEnabled;
        try {
            effectsEnabled = strictBooleanPluginSetting("effects.enabled", false);
        } catch (RuntimeException e) {
            throw pluginActivationFailure("effect runtime settings for chain '"
                    + config.chainId() + "'", e);
        }
        if (!effectsEnabled) {
            throw new PluginActivationException("App-chain '" + config.chainId()
                    + "': effects.executor.enabled=true requires effects.enabled=true", null);
        }
        java.util.List<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> executors =
                new java.util.ArrayList<>();
        Set<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> executorInstances =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> executorIds = new HashSet<>();
        java.util.Map<String, java.util.Map<String, String>> executorConfigs =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, String> webhookConfig = executorConfigFor("webhook");
        if (!webhookConfig.isEmpty()) {
            var webhookExecutor = new WebhookEffectExecutor(webhookConfig, log);
            String id = requireExecutorId(webhookExecutor);
            executors.add(stableExecutorIdentity(webhookExecutor, id));
            executorInstances.add(webhookExecutor);
            executorIds.add(id);
            executorConfigs.put(id, webhookConfig);
        }
        SortedSet<String> configuredExecutorSchemes = configuredSchemes("effects.executors.");
        configuredExecutorSchemes.remove("webhook"); // explicit SYSTEM built-in above wins
        for (String scheme : configuredExecutorSchemes) {
            java.util.Map<String, String> executorConfig = executorConfigFor(scheme);
            try {
                AppEffectExecutorFactory factory = pluginProviders.find(
                                AppEffectExecutorFactory.class, scheme)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No selected AppEffectExecutorFactory for scheme '" + scheme
                                        + "' (available: "
                                        + pluginProviders.names(AppEffectExecutorFactory.class) + ")"));
                List<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> created =
                        Objects.requireNonNull(factory.create(config.chainId(), executorConfig),
                                "AppEffectExecutorFactory.create returned null");
                // Pre-own the complete fresh batch before invoking id() on
                // any product. This guarantees exact rollback even when a
                // null/duplicate/malformed middle entry precedes valid tail
                // products returned by the same factory.
                List<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor>
                        stagedExecutors = new ArrayList<>();
                RuntimeException ownershipFailure = null;
                for (var executor : created) {
                    if (executor == null) {
                        if (ownershipFailure == null) {
                            ownershipFailure = new NullPointerException(
                                    "AppEffectExecutorFactory.create returned a null executor");
                        }
                        continue;
                    }
                    if (!executorInstances.add(executor)) {
                        if (ownershipFailure == null) {
                            ownershipFailure = new IllegalArgumentException(
                                    "AppEffectExecutorFactory '" + scheme
                                            + "' returned the same executor instance more than once");
                        }
                        continue;
                    }
                    stagedExecutors.add(executor);
                    executors.add(executor);
                }
                if (ownershipFailure != null) {
                    throw ownershipFailure;
                }
                int ownedIndex = executors.size() - stagedExecutors.size();
                for (var executor : stagedExecutors) {
                    String id = requireExecutorId(executor);
                    if (!executorIds.add(id)) {
                        throw new IllegalArgumentException(
                                "Duplicate app-chain effect executor id '" + id + "'");
                    }
                    // The validated identity is immutable for this product's
                    // lifetime. EffectRuntime must not re-enter a stateful
                    // plugin id() callback when binding config or reporting.
                    executors.set(ownedIndex, stableExecutorIdentity(executor, id));
                    ownedIndex++;
                    executorConfigs.put(id, executorConfig);
                    log.info("App-chain effect executor '{}' registered via {} plugin",
                            id, scheme);
                }
            } catch (Throwable e) {
                closeUnownedEffectExecutors(executors, e);
                if (e instanceof Error fatal) {
                    throw fatal;
                }
                throw pluginActivationFailure("effect executor factory '" + scheme
                        + "' for chain '" + config.chainId() + "'", e);
            }
        }
        if (executors.isEmpty() && !externalEffectsEnabled()) {
            throw new PluginActivationException("App-chain '" + config.chainId()
                    + "': effects.executor.enabled=true but no executors are configured "
                    + "(set effects.executors.<scheme>.* or enable effects.external.enabled)", null);
        }
        // Security posture (ADR-010 F12 / final review): operator effect
        // actions and external claim/report move real funds. If the REST
        // surface has no full key, it fails closed. The subsystem cannot see
        // app-module API-key config, so retain a best-effort operator banner.
        if (externalEffectsEnabled()) {
            log.warn("App-chain '{}': effects.external.enabled=true exposes claim/report over "
                    + "REST — configure an unscoped yano.app-chain.api.keys full key and restrict network "
                    + "access to the executor/operator network (ADR-010 F12)", config.chainId());
        }
        EffectRuntime createdRuntime = null;
        try {
            String executorIdentity = resolveEffectExecutorIdentity();
            String runtimeOwner = effectRuntimeOwner(executorIdentity, runtimeSettings.types());
            createdRuntime = new EffectRuntime(ledgerStore, config.chainId(), runtimeSettings,
                    executors, executorConfigs, runtimeOwner, log);
            // Publish ownership before registering the lifetime signal. If a
            // custom registry rejects registration, startup rollback sees and
            // closes the runtime instead of directly double-closing products.
            this.effectRuntime = createdRuntime;
            pluginProviders.registerContributionCleanup(
                    createdRuntime.closeCompletion().toCompletableFuture());
        } catch (Throwable e) {
            if (createdRuntime == null) {
                closeUnownedEffectExecutors(executors, e);
            }
            if (e instanceof Error fatal) {
                throw fatal;
            }
            throw pluginActivationFailure("effect runtime for chain '"
                    + config.chainId() + "'", e);
        }
        log.info("App-chain '{}' effect runtime enabled: executors={}, tick={}ms, maxParallel={}",
                config.chainId(), executors.stream()
                        .map(com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor::id).toList(),
                runtimeSettings.tickMs(), runtimeSettings.maxParallel());
    }

    private void closeUnownedEffectExecutors(
            List<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> executors,
            Throwable failure) {
        Throwable outcome = failure;
        ListIterator<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> iterator =
                executors.listIterator(executors.size());
        Set<com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor> closed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        while (iterator.hasPrevious()) {
            var executor = iterator.previous();
            if (!closed.add(executor)) {
                continue;
            }
            try {
                executor.close();
            } catch (Throwable closeFailure) {
                outcome = LifecycleFailures.merge(outcome, closeFailure);
            }
        }
        executors.clear();
        if (outcome != failure) {
            throw propagateLifecycleFailure(outcome, "Effect executor cleanup failed");
        }
    }

    private static String requireExecutorId(
            com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor executor) {
        return requireOperationalProductId(
                executor.id(), "AppEffectExecutor.id");
    }

    private static String requireOperationalProductId(String value, String source) {
        String id = Objects.requireNonNull(value, source + " returned null");
        if (id.isEmpty() || id.length() > 128
                || !isAsciiIdentifierStart(id.charAt(0))) {
            throw new IllegalArgumentException(source
                    + " must be 1-128 ASCII identifier characters");
        }
        for (int index = 1; index < id.length(); index++) {
            char character = id.charAt(index);
            if (!isAsciiIdentifierStart(character)
                    && character != '.' && character != '_' && character != '-'
                    && character != ':' && character != '+') {
                throw new IllegalArgumentException(source
                        + " must be 1-128 ASCII identifier characters");
            }
        }
        return id;
    }

    private static boolean isAsciiIdentifierStart(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private static com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor
    stableExecutorIdentity(
            com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor delegate,
            String id
    ) {
        return new com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(String effectType) {
                return delegate.supports(effectType);
            }

            @Override
            public com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution execute(
                    com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext context,
                    com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect effect
            ) throws Exception {
                return delegate.execute(context, effect);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    private boolean strictBooleanPluginSetting(String key, boolean defaultValue) {
        String raw = config.pluginSettings().get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalStateException("Invalid boolean value for " + key + ": '" + raw + "'");
        }
        return Boolean.parseBoolean(normalized);
    }

    /**
     * Stable physical-executor identity. The generated sidecar deliberately
     * lives beside the checkpointed chain directory: member-key rotation on
     * this node preserves pending results, while restoring another node's
     * checkpoint does not import that node's leases/status/cursors.
     */
    private String resolveEffectExecutorIdentity() throws java.io.IOException {
        String configured = config.pluginSettings()
                .getOrDefault("effects.executor.identity", "").trim();
        if (!configured.isEmpty()) {
            if (configured.length() > 512) {
                throw new IllegalArgumentException(
                        "effects.executor.identity must be at most 512 characters");
            }
            return configured;
        }

        java.nio.file.Path chainPath = java.nio.file.Path.of(ledgerPath).toAbsolutePath();
        java.nio.file.Path identityPath = chainPath.resolveSibling(
                chainPath.getFileName() + ".effect-executor-id");
        java.nio.file.Path parent = identityPath.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        if (!java.nio.file.Files.exists(identityPath)) {
            String candidate = "local:" + java.util.UUID.randomUUID();
            try {
                java.nio.file.Files.writeString(identityPath, candidate,
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A concurrent startup won creation; read its identity below.
            }
        }
        String identity = java.nio.file.Files.readString(identityPath,
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (identity.isEmpty() || identity.length() > 512) {
            throw new IllegalStateException("Malformed effect executor identity sidecar: "
                    + identityPath);
        }
        return identity;
    }

    /**
     * Fixed-size, collision-resistant binding of a physical executor identity
     * and its exact type partition. Length-prefixing avoids delimiter
     * ambiguities; hashing keeps the stored owner inside the runtime-CF bound
     * even when the configured identity is at its documented maximum.
     */
    static String effectRuntimeOwner(String executorIdentity, Set<String> types) {
        java.util.Objects.requireNonNull(executorIdentity, "executorIdentity");
        java.util.Objects.requireNonNull(types, "types");
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream data = new java.io.DataOutputStream(bytes)) {
                byte[] identityBytes = executorIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                data.writeInt(identityBytes.length);
                data.write(identityBytes);
                java.util.SortedSet<String> sortedTypes = new java.util.TreeSet<>(types);
                data.writeInt(sortedTypes.size());
                for (String type : sortedTypes) {
                    byte[] typeBytes = java.util.Objects.requireNonNull(type, "effect type")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    data.writeInt(typeBytes.length);
                    data.write(typeBytes);
                }
            }
            return "fp1:" + com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                    com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                            bytes.toByteArray()));
        } catch (java.io.IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
    }

    @Override
    public AutoCloseable subscribeFinalized(FinalizedBlockListener listener) {
        Objects.requireNonNull(listener, "listener");
        finalizedListeners.add(listener);
        return () -> finalizedListeners.remove(listener);
    }

    // Block timing for status/metrics (008.1 I1.8)
    private volatile long lastBlockAtMillis;
    private final java.util.ArrayDeque<Long> recentBlockIntervals = new java.util.ArrayDeque<>();

    private void onBlockFinalized(AppBlock block, byte[] blockHash) {
        generationUseOrNoop(() -> onBlockFinalizedWithinGeneration(block, blockHash));
    }

    private void onBlockFinalizedWithinGeneration(AppBlock block, byte[] blockHash) {
        long now = System.currentTimeMillis();
        lastProgressAt = now;
        synchronized (recentBlockIntervals) {
            if (lastBlockAtMillis > 0) {
                recentBlockIntervals.addLast(now - lastBlockAtMillis);
                while (recentBlockIntervals.size() > 20) {
                    recentBlockIntervals.pollFirst();
                }
            }
            lastBlockAtMillis = now;
        }
        for (FinalizedBlockListener listener : finalizedListeners) {
            try {
                listener.onFinalized(block, blockHash);
            } catch (Exception e) {
                log.warn("Finalized-block listener failed (errorType={})",
                        e.getClass().getName());
            }
        }
        if (eventBus != null) {
            try {
                eventBus.publish(new AppBlockFinalizedEvent(block, blockHash),
                        EventMetadata.builder().build(), PublishOptions.builder().build());
            } catch (Exception e) {
                log.warn("Failed to publish AppBlockFinalizedEvent (errorType={})",
                        e.getClass().getName());
            }
        }
    }

    private void connectTick() {
        generationUseOrNoop(this::connectTickWithinGeneration);
    }

    private void connectTickWithinGeneration() {
        if (!running.get())
            return;
        for (AppPeerLink peerClient : peerClients) {
            try {
                // Async: an unreachable peer must never wedge this scheduler
                // (proposer/anchor/catch-up ticks share it) — 008.2 fix
                peerClient.ensureConnectedAsync();
            } catch (Exception e) {
                log.debug("App-peer connect attempt failed for {} (errorType={})",
                        peerClient.peerId(), e.getClass().getName());
            }
        }
    }

    private void keepAliveTick() {
        generationUseOrNoop(this::keepAliveTickWithinGeneration);
    }

    private void keepAliveTickWithinGeneration() {
        if (!running.get())
            return;
        for (AppPeerLink peerClient : peerClients) {
            peerClient.keepAliveTick();
        }
    }

    private void sinkTick() {
        generationUseOrNoop(this::sinkTickWithinGeneration);
    }

    private void sinkTickWithinGeneration() {
        if (!running.get())
            return;
        runSinkDeliveryTicks(sinkRunners);
    }

    /** One periodic-task body, package-visible for deterministic policy tests. */
    static void runSinkDeliveryTicks(Iterable<SinkRunner> runners) {
        for (SinkRunner runner : runners) {
            try {
                runner.deliveryTick();
            } catch (Throwable failure) {
                // ScheduledExecutorService suppresses every later invocation
                // when a periodic task lets a Throwable escape. Keep one bad
                // sink isolated and retain a secret-safe health signal.
                runner.recordTickFailure(failure);
                SinkRunner.rethrowIfFatal(failure);
            }
        }
    }

    @Override
    public void stop() {
        if (!claimLifecycleTransition(LifecycleState.STOPPING, "stop")) {
            return;
        }

        Throwable failure = null;
        try {
            lifecycleTransitionLineage.set(Boolean.TRUE);
            running.set(false);
            log.info("Stopping app chain '{}'", config.chainId());
            releaseOwnedResources();
        } catch (Throwable transitionFailure) {
            failure = transitionFailure;
        } finally {
            // STOPPED is terminal even when teardown reports Error. If an
            // unexpected early failure retained a resource, a later host stop
            // can claim STOPPING again because hasOwnedResources() remains true.
            running.set(false);
            try {
                failure = finishLifecycleTransition(
                        LifecycleState.STOPPING, LifecycleState.STOPPED, failure);
            } finally {
                lifecycleTransitionLineage.remove();
            }
        }
        if (failure != null) {
            throw propagateLifecycleFailure(failure, "App-chain stop failed");
        }
    }

    /**
     * Terminal subsystem release. Ordinary {@link #stop()} remains restartable;
     * close additionally releases the legacy provider registry created by the
     * direct compatibility constructor. Catalog registries stay owned by their
     * enclosing {@code PluginRuntimeEnvironment} and are never closed here.
     */
    @Override
    public void close() {
        // Fail before publishing terminal ownership when invoked from a
        // contribution/resource callback. Otherwise close could strand a
        // still-running subsystem after stop correctly rejects self-teardown.
        requireLifecycleHostCaller("close");
        if (!permanentlyClosed.compareAndSet(false, true)) {
            try {
                permanentCloseCompletion.join();
                return;
            } catch (java.util.concurrent.CompletionException closeFailure) {
                throw propagateLifecycleFailure(
                        unwrapCompletionFailure(closeFailure),
                        "App-chain close failed");
            }
        }

        Throwable failure = null;
        try {
            stop();
        } catch (Throwable stopFailure) {
            failure = LifecycleFailures.merge(failure, stopFailure);
        }
        try {
            deferredRuntimeShutdown.join();
        } catch (Throwable drainFailure) {
            failure = LifecycleFailures.merge(
                    failure, unwrapCompletionFailure(drainFailure));
        }
        if (ownedPluginProviders != null) {
            try {
                ownedPluginProviders.close();
            } catch (Throwable providerFailure) {
                failure = LifecycleFailures.merge(failure, providerFailure);
            }
        }

        if (failure == null) {
            permanentCloseCompletion.complete(null);
            return;
        }
        permanentCloseCompletion.completeExceptionally(failure);
        throw propagateLifecycleFailure(failure, "App-chain close failed");
    }

    /**
     * Release every resource acquired by {@link #startOwnedResources(long)}.
     * This method deliberately does not consult {@link #running}: failed
     * startup happens before that flag is set, but still owns a RocksDB handle
     * and (once the engine is constructed) an executor.
     */
    private void releaseOwnedResources() {
        releaseOwnedResources(null);
    }

    private void releaseOwnedResources(Throwable primaryFailure) {
        SynchronousCleanupFailures cleanupFailures =
                new SynchronousCleanupFailures(primaryFailure);
        // FIRST: reject every new root operation. Already-admitted operations
        // remain re-entrant and ledger/provider cleanup waits on this future.
        CompletableFuture<Void> generationQuiescent = generationUseGate.seal();
        running.set(false);
        QueryLane retiringQueries = queryLane;
        queryLane = null;
        if (retiringQueries != null) {
            try {
                retiringQueries.requestShutdown();
            } catch (Throwable cleanupFailure) {
                recordCleanupFailure("query lane", cleanupFailure, cleanupFailures);
            }
        }
        // Close sink admission before stopping any scheduler. An already
        // admitted delivery may ignore interruption, so ledger/product close
        // is fenced on the runner's terminal signals below.
        List<SinkRunner> retiringSinks = List.copyOf(sinkRunners);
        for (SinkRunner runner : retiringSinks) {
            try {
                runner.requestShutdown();
            } catch (Throwable cleanupFailure) {
                recordCleanupFailure("sink admission " + runner.id(), cleanupFailure,
                        cleanupFailures);
            }
        }

        for (var subscription : eventSubscriptions) {
            try {
                subscription.close();
            } catch (Throwable cleanupFailure) {
                recordCleanupFailure("event subscription", cleanupFailure, cleanupFailures);
            }
        }
        eventSubscriptions.clear();

        // Shutdown ORDER matters (ADR-010 F5 review): stop the tick source and
        // WAIT for it, then close the runtime (waits for workers), and only
        // then may the ledger close — nothing may touch RocksDB afterwards.
        ScheduledExecutorService fxExec = fxScheduler;
        fxScheduler = null;
        if (fxExec != null) {
            try {
                fxExec.shutdown();
                if (!fxExec.awaitTermination(3, TimeUnit.SECONDS)) {
                    fxExec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fxExec.shutdownNow();
            } catch (Throwable cleanupFailure) {
                recordCleanupFailure("effect scheduler", cleanupFailure, cleanupFailures);
            }
        }
        EffectRuntime currentFx = effectRuntime;
        effectRuntime = null;
        CompletableFuture<Void> effectsClosed = CompletableFuture.completedFuture(null);
        if (currentFx != null) {
            closeResource("effect runtime", currentFx::close, cleanupFailures);
            effectsClosed = currentFx.closeCompletion().toCompletableFuture();
        }

        ScheduledExecutorService exec = scheduler;
        scheduler = null;
        if (exec != null) {
            closeResource("main scheduler", () -> shutdownScheduler(exec, "main"),
                    cleanupFailures);
        }
        ScheduledExecutorService sinkExec = sinkScheduler;
        sinkScheduler = null;
        if (sinkExec != null) {
            closeResource("sink scheduler", () -> shutdownScheduler(sinkExec, "sink"),
                    cleanupFailures);
        }

        // Sink runners hold the ledger that is about to close and are re-created
        // by start(); close + drop them so a restart doesn't tick stale sinks
        // against a closed RocksDB handle (or double-deliver). External
        // finalizedListeners (SSE, metrics) are NOT cleared — they survive a restart.
        for (SinkRunner runner : retiringSinks) {
            if (primaryFailure != null) {
                // Startup rollback has no admitted delivery in normal
                // operation. Keep synchronous failure suppression semantics.
                closeSinkResource(runner, cleanupFailures);
            } else {
                try {
                    runner.closeAsync();
                } catch (Throwable cleanupFailure) {
                    recordSinkCleanupFailure(runner, cleanupFailure, cleanupFailures);
                }
            }
        }
        sinkRunners.clear();

        for (AppPeerLink peerClient : peerClients) {
            closeResource("peer " + peerClient.peerId(), peerClient::shutdown, cleanupFailures);
        }
        peerClients.clear();

        anchorService = null;
        scriptAnchorService = null;
        observationService = null;

        AppChainEngine currentEngine = engine;
        engine = null;
        CompletableFuture<Void> engineQuiescent = CompletableFuture.completedFuture(null);
        if (currentEngine != null) {
            closeResource("engine", currentEngine::close, cleanupFailures);
            engineQuiescent = currentEngine.closeCompletion().toCompletableFuture();
        }
        AppLedgerStore currentLedger = ledger;
        ledger = null;
        CompletableFuture<Void> productsClosed = awaitAllCleanupStages(
                awaitAllCleanupStages(retiringSinks.stream()
                        .map(SinkRunner::closeCompletion)
                        .toArray(CompletableFuture[]::new)),
                effectsClosed);
        Throwable synchronousCleanupFailure = cleanupFailures.cleanupOutcome();
        CompletableFuture<Void> synchronousCleanup = synchronousCleanupFailure == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(synchronousCleanupFailure);
        Throwable deferredPrimary = cleanupFailures.outcome();
        if (currentLedger != null) {
            CompletableFuture<Void> deliveriesQuiescent = awaitAllCleanupStages(
                    retiringSinks.stream()
                            .map(SinkRunner::deliveryQuiescence)
                            .toArray(CompletableFuture[]::new));
            CompletableFuture<Void> ledgerUsersQuiescent = awaitAllCleanupStages(
                    deliveriesQuiescent, engineQuiescent, generationQuiescent);
            // CompletableFuture runs this inline when already quiescent (the
            // normal fast path), or on the last delivery/engine thread
            // otherwise. The close must run even when an engine cleanup stage
            // completes exceptionally: a failed WriteBatch cleanup may poison
            // restart, but it must never strand the RocksDB lock as well.
            CompletableFuture<Void> ledgerClosed = closeRestartCriticalResourceAfter(
                    ledgerUsersQuiescent, "ledger", currentLedger::close, deferredPrimary);
            deferredRuntimeShutdown = awaitAllCleanupStages(
                    ledgerClosed, productsClosed, engineQuiescent, generationQuiescent,
                    synchronousCleanup);
        } else {
            deferredRuntimeShutdown = awaitAllCleanupStages(
                    productsClosed, engineQuiescent, generationQuiescent, synchronousCleanup);
        }
        // Every remaining close has now run or has an explicit lifetime
        // barrier. A process-fatal signal must escape. During failed startup,
        // a non-process Error from cleanup also replaces an ordinary startup
        // failure so the synchronous caller observes the strongest outcome.
        // Ordinary stop-time failures remain on deferredRuntimeShutdown: stop
        // has released every resource it can and the same instance fails its
        // next restart closed.
        cleanupFailures.rethrowImmediateWinner();
    }

    private boolean hasOwnedResources() {
        return ledger != null
                || engine != null
                || scheduler != null
                || sinkScheduler != null
                || fxScheduler != null
                || queryLane != null
                || effectRuntime != null
                || anchorService != null
                || scriptAnchorService != null
                || observationService != null
                || !eventSubscriptions.isEmpty()
                || !sinkRunners.isEmpty();
    }

    private void closeResource(
            String resource,
            Runnable close,
            SynchronousCleanupFailures cleanupFailures
    ) {
        try {
            close.run();
        } catch (Throwable cleanupFailure) {
            recordCleanupFailure(resource, cleanupFailure, cleanupFailures);
        }
    }

    /**
     * Close a resource whose successful release is a prerequisite for opening
     * the next generation. Throwing from the completion callback makes
     * {@code deferredRuntimeShutdown} exceptional, so restart fails closed.
     */
    private void closeRestartCriticalResource(
            String resource,
            Runnable close,
            Throwable primaryFailure
    ) {
        try {
            close.run();
        } catch (Throwable cleanupFailure) {
            Throwable outcome = LifecycleFailures.merge(primaryFailure, cleanupFailure);
            try {
                log.warn("Error closing restart-critical app-chain {} for '{}' (errorType={})",
                        resource, config.chainId(), cleanupFailure.getClass().getName());
            } catch (Throwable diagnosticFailure) {
                outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
            }
            throw propagateLifecycleFailure(
                    outcome, "Restart-critical app-chain resource close failed");
        }
    }

    /** Package-private for the exceptional-quiescence lifecycle regression. */
    CompletableFuture<Void> closeRestartCriticalResourceAfter(
            CompletableFuture<Void> quiescence,
            String resource,
            Runnable close,
            Throwable primaryFailure
    ) {
        return quiescence.handle((ignored, quiescenceFailure) -> {
            Throwable failure = quiescenceFailure;
            try {
                failure = unwrapCompletionFailure(quiescenceFailure);
            } catch (Throwable inspectionFailure) {
                failure = mergeAsyncCleanupFailure(failure, inspectionFailure);
            }
            try {
                closeRestartCriticalResource(resource, close, primaryFailure);
            } catch (Throwable closeFailure) {
                failure = mergeAsyncCleanupFailure(failure, closeFailure);
            }
            rethrowAsyncCleanupFailure(failure);
            return null;
        });
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int inspected = 0;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            if (!visited.add(current) || ++inspected > 256) {
                return failure;
            }
            Throwable cause = current.getCause();
            if (cause == null) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    private static Throwable mergeAsyncCleanupFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    /**
     * Wait for every cleanup stage and publish their strongest merged failure.
     * {@link CompletableFuture#allOf(CompletableFuture[])} waits for all inputs
     * but exposes only one implementation-selected exception; that can hide a
     * later {@link Error}. This wrapper retains the barrier semantics and then
     * deterministically folds every terminal failure in declaration order.
     */
    private static CompletableFuture<Void> awaitAllCleanupStages(
            CompletableFuture<?>... stages
    ) {
        if (stages.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] observed = Arrays.stream(stages)
                .map(stage -> stage.handle((ignored, failure) -> {
                    try {
                        return unwrapCompletionFailure(failure);
                    } catch (Throwable inspectionFailure) {
                        return mergeAsyncCleanupFailure(failure, inspectionFailure);
                    }
                }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(observed).thenApply(ignored -> {
            Throwable outcome = null;
            for (CompletableFuture<?> stage : observed) {
                Throwable failure = (Throwable) stage.join();
                if (failure != null) {
                    outcome = mergeAsyncCleanupFailure(outcome, failure);
                }
            }
            rethrowAsyncCleanupFailure(outcome);
            return null;
        });
    }

    private static boolean isJvmFatalCleanup(Throwable failure) {
        return LifecycleFailures.isProcessFatal(failure);
    }

    private static void rethrowAsyncCleanupFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    private void closeSinkResource(
            SinkRunner runner,
            SynchronousCleanupFailures cleanupFailures
    ) {
        try {
            runner.close();
        } catch (Throwable cleanupFailure) {
            recordSinkCleanupFailure(runner, cleanupFailure, cleanupFailures);
        }
    }

    private void recordSinkCleanupFailure(
            SinkRunner runner,
            Throwable cleanupFailure,
            SynchronousCleanupFailures cleanupFailures
    ) {
        cleanupFailures.record(cleanupFailure);
        // Unlike platform resources, this Throwable originates in plugin code
        // and its message can contain sink credentials/configuration.
        try {
            log.warn("Error closing app-chain sink '{}' for '{}' (errorType={})",
                    runner.id(), config.chainId(), cleanupFailure.getClass().getName());
        } catch (Throwable diagnosticFailure) {
            cleanupFailures.record(diagnosticFailure);
        }
    }

    private void recordCleanupFailure(
            String resource,
            Throwable cleanupFailure,
            SynchronousCleanupFailures cleanupFailures
    ) {
        // Continue unwinding: one faulty plugin/resource must not retain the
        // RocksDB lock or mask an actual JVM termination signal. The collector
        // preserves ranked suppression semantics and delays any required
        // process-fatal (or stronger startup-cleanup) rethrow until every
        // lifetime barrier has been installed.
        cleanupFailures.record(cleanupFailure);
        try {
            log.warn("Error closing app-chain {} for '{}' (errorType={})",
                    resource, config.chainId(), cleanupFailure.getClass().getName());
        } catch (Throwable diagnosticFailure) {
            cleanupFailures.record(diagnosticFailure);
        }
    }

    private static final class SynchronousCleanupFailures {
        private final Throwable primaryFailure;
        private Throwable outcome;
        private boolean cleanupFailed;

        private SynchronousCleanupFailures(Throwable primaryFailure) {
            this.primaryFailure = primaryFailure;
            this.outcome = primaryFailure;
        }

        private void record(Throwable cleanupFailure) {
            cleanupFailed = true;
            if (outcome == null) {
                outcome = cleanupFailure;
            } else {
                outcome = mergeCleanupFailure(outcome, cleanupFailure);
            }
        }

        private Throwable outcome() {
            return outcome;
        }

        /**
         * The failure contributed by cleanup, merged with the startup primary
         * when present so deferred restart diagnostics retain full context.
         */
        private Throwable cleanupOutcome() {
            return cleanupFailed ? outcome : null;
        }

        private void rethrowImmediateWinner() {
            if (isJvmFatal(outcome)) {
                LifecycleFailures.rethrowIfProcessFatal(outcome);
            }
            if (primaryFailure != null && outcome != primaryFailure) {
                rethrowAsyncCleanupFailure(outcome);
            }
        }

        private static Throwable mergeCleanupFailure(Throwable current, Throwable next) {
            return LifecycleFailures.merge(current, next);
        }

        @SuppressWarnings("removal")
        private static boolean isJvmFatal(Throwable failure) {
            return isJvmFatalCleanup(failure);
        }
    }

    private void shutdownScheduler(ScheduledExecutorService executor, String schedulerName) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("App-chain '{}' {} scheduler did not terminate within 3 seconds",
                        config.chainId(), schedulerName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping app-chain '{}' {} scheduler",
                    config.chainId(), schedulerName);
        }
    }

    @Override
    public SubsystemHealth health() {
        // Peer connectivity is intentionally NOT a readiness gate: in a two-node
        // group the first node would never become ready while waiting for the
        // second (bootstrap deadlock). Connectivity is reported via status().
        return running.get()
                ? SubsystemHealth.up(name())
                : SubsystemHealth.down(name(), "stopped");
    }

}
