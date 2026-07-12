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
import com.bloxbean.cardano.yano.api.events.AppBlockFinalizedEvent;
import com.bloxbean.cardano.yano.api.events.AppMessageReceivedEvent;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String SYSTEM_TOPIC_PREFIX = "~";
    private static final String CONSENSUS_TOPIC_PREFIX = "~consensus/";
    /** Script-anchor co-signing (008.4): diffusion-only, never pooled/sequenced. */
    private static final String ANCHOR_TOPIC_PREFIX = "~anchor/";

    private final AppChainConfig config;
    private final long protocolMagic;
    private final EventBus eventBus;
    private final Logger log;
    private final ClassLoader pluginClassLoader;

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
    private final List<AppPeerLink> peerClients = new ArrayList<>();
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
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledExecutorService sinkScheduler;

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
        this.config = Objects.requireNonNull(config, "config");
        this.protocolMagic = protocolMagic;
        this.eventBus = eventBus;
        this.log = Objects.requireNonNull(log, "log");
        this.pluginClassLoader = pluginClassLoader;
        this.signer = SignerProviders.resolve(config.signingKeyHex(), pluginClassLoader, log);
        this.group = new MemberGroup(normalizeMemberKeys(config.memberKeysHex()), config.threshold());
        this.seenMessageIds = new SeenMessageIds(SEEN_IDS_HARD_CAP);
        this.pool = new AppMsgPool(config.poolMaxMessages());
        this.stateMachine = stateMachine != null
                ? stateMachine
                : resolveStateMachine(config.stateMachineId(), pluginClassLoader,
                        new com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext() {
                            @Override public String chainId() { return config.chainId(); }
                            @Override public java.util.Map<String, String> settings() {
                                return config.pluginSettings();
                            }
                        }, log);
        this.ledgerPath = (ledgerPath != null ? ledgerPath : "./app-chain") + "/" + config.chainId();

        if (!group.contains(signer.publicKeyHex())) {
            if (governedMode()) {
                // Governed bootstrap (008.3): a late-added member starts with
                // the chain's ORIGINAL genesis list (which predates it) and
                // becomes a member via the derived governance epochs
                log.warn("App-chain '{}': this node's key {} is not in the GENESIS member list — "
                        + "expecting chain-governed epochs to include it (catch-up will derive them)",
                        config.chainId(), signer.publicKeyHex());
            } else {
                throw new IllegalArgumentException(
                        "This node's app-chain public key " + signer.publicKeyHex()
                                + " is not in the configured member list (yano.app-chain.members)");
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
     * {@code rotating}, then {@code SequencerModeProvider} plugins via
     * ServiceLoader. Selected by {@code sequencer.mode}; a bare
     * {@code sequencer.proposer} keeps meaning {@code fixed} (v1 compat).
     */
    private com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode resolveSequencerMode() {
        Map<String, String> settings = new LinkedHashMap<>(config.pluginSettings());
        if (!config.proposerKeyHex().isEmpty()) {
            settings.putIfAbsent("sequencer.proposer", config.proposerKeyHex());
        }
        String modeId = settings.getOrDefault("sequencer.mode",
                !config.proposerKeyHex().isEmpty() ? FixedSequencerMode.ID : "")
                .trim().toLowerCase(Locale.ROOT);
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

        com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode mode = switch (modeId) {
            case FixedSequencerMode.ID -> new FixedSequencerMode();
            case RotatingSequencerMode.ID -> new RotatingSequencerMode();
            default -> {
                ClassLoader loader = pluginClassLoader != null
                        ? pluginClassLoader : Thread.currentThread().getContextClassLoader();
                for (var provider : java.util.ServiceLoader.load(
                        com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider.class, loader)) {
                    if (modeId.equals(provider.id())) {
                        yield provider.create(context);
                    }
                }
                throw new IllegalArgumentException("App-chain '" + config.chainId()
                        + "': unknown sequencer mode '" + modeId
                        + "' (built-ins: fixed, rotating; plugins via SequencerModeProvider)");
            }
        };
        mode.init(context);
        return mode;
    }

    /**
     * Resolve a state machine by id: built-ins first, then
     * {@link AppStateMachineProvider} implementations discovered via
     * ServiceLoader on the plugin classloader (custom app chains deployed as
     * plugin jars on a stock yano distribution) and the context classloader.
     */
    private static AppStateMachine resolveStateMachine(String id, ClassLoader pluginClassLoader,
                                                       com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext ctx,
                                                       Logger log) {
        if (OrderedLogStateMachine.ID.equals(id)) {
            return new OrderedLogStateMachine();
        }
        List<ClassLoader> classLoaders = new ArrayList<>();
        if (pluginClassLoader != null) {
            classLoaders.add(pluginClassLoader);
        }
        classLoaders.add(Thread.currentThread().getContextClassLoader());
        List<String> available = new ArrayList<>();
        available.add(OrderedLogStateMachine.ID);
        for (ClassLoader classLoader : classLoaders) {
            try {
                for (AppStateMachineProvider provider
                        : java.util.ServiceLoader.load(AppStateMachineProvider.class, classLoader)) {
                    if (id.equals(provider.id())) {
                        log.info("App-chain state machine '{}' loaded via provider {}",
                                id, provider.getClass().getName());
                        return provider.create(ctx);
                    }
                    available.add(provider.id());
                }
            } catch (Exception e) {
                log.warn("AppStateMachineProvider discovery failed on {}: {}", classLoader, e.toString());
            }
        }
        throw new IllegalArgumentException("Unknown app-chain state machine: " + id
                + " (available: " + available + ")");
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
                        (chainId, fromHeight, toHeight) -> {
                            AppLedgerStore currentLedger = ledger;
                            if (currentLedger == null || !config.chainId().equals(chainId)) {
                                return new com.bloxbean.cardano.yaci.core.protocol.appchainsync
                                        .AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0);
                            }
                            return new com.bloxbean.cardano.yaci.core.protocol.appchainsync
                                    .AppChainSyncServerAgent.BlockRangeProvider.Range(
                                    currentLedger.blockBytesRange(fromHeight, toHeight),
                                    currentLedger.tipHeight());
                        });
        return List.of(gossipFactory, catchUpFactory);
    }

    /** The ledger, or null when sequencing is disabled / not started (manager use). */
    AppLedgerStore ledgerOrNull() {
        return ledger;
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
        bestPeerTip = Math.max(bestPeerTip, serverTipHeight);
        AppChainEngine currentEngine = engine;
        if (currentEngine != null && !blocks.isEmpty()) {
            log.info("Catch-up: received {} block(s) from {} (peer tip: {})",
                    blocks.size(), peerId, serverTipHeight);
            currentEngine.onCertifiedBlocks(blocks);
        }
    }

    private void catchUpTick() {
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
                    log.warn("Failed to publish AppChainStalledEvent: {}", e.toString());
                }
            }
        }
    }

    /** Verified messages arriving from a peer: dedup, route, relay. */
    void onInboundMessages(List<AppMessage> messages) {
        for (AppMessage message : messages) {
            boolean firstSighting =
                    seenMessageIds.markSeen(message.getMessageIdHex(), message.getExpiresAt());
            String topic = message.getTopic() != null ? message.getTopic() : "";
            // Only ~consensus/* gets the engine fast-path; other system topics
            // (~governance/*) are SEQUENCED like ordinary messages (008.3)
            if (topic.startsWith(CONSENSUS_TOPIC_PREFIX)) {
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
            if (topic.startsWith(ANCHOR_TOPIC_PREFIX)) {
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
        if (topic.startsWith(CONSENSUS_TOPIC_PREFIX)) {
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
                log.warn("Failed to publish AppMessageReceivedEvent: {}", e.toString());
            }
        }
    }

    /** Build and sign an envelope on the given topic — NOT yet diffused. */
    private AppMessage buildSigned(String topic, byte[] body, long ttlSeconds) {
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
            log.warn("L1 observation injection failed: {}", e.toString());
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
        if (effectiveTopic.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException("Topics must not contain NUL characters");

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
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null ? currentLedger.tipHeight() : 0L;
    }

    @Override
    public Optional<AppBlock> block(long height) {
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null ? currentLedger.block(height) : Optional.empty();
    }

    @Override
    public byte[] stateRoot() {
        AppLedgerStore currentLedger = ledger;
        byte[] root = currentLedger != null ? currentLedger.stateRoot() : null;
        return root != null ? root : new byte[32];
    }

    @Override
    public Optional<byte[]> stateValue(byte[] key) {
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null ? currentLedger.stateGet(key) : Optional.empty();
    }

    @Override
    public Optional<byte[]> stateProof(byte[] key) {
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null ? currentLedger.stateProofWire(key) : Optional.empty();
    }

    @Override
    public Optional<Long> messageHeight(byte[] messageId) {
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null ? currentLedger.messageHeight(messageId) : Optional.empty();
    }

    // ------------------------------------------------------------------
    // Query surface (ADR 006 E3.3)
    // ------------------------------------------------------------------

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesByTopic(
            String topic, long fromHeight, int limit) {
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null
                ? currentLedger.messagesByTopic(topic, fromHeight, Math.max(1, Math.min(limit, 1000)))
                : List.of();
    }

    @Override
    public List<com.bloxbean.cardano.yano.api.appchain.MessageRef> messagesBySender(
            byte[] sender, long fromHeight, int limit) {
        if (sender == null || sender.length != 32)
            throw new IllegalArgumentException("sender must be a 32-byte Ed25519 public key");
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null
                ? currentLedger.messagesBySender(sender, fromHeight, Math.max(1, Math.min(limit, 1000)))
                : List.of();
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
        int dropped = pool.clear();
        log.info("App-chain '{}' pool drained: {} pending message(s) dropped (admin)",
                config.chainId(), dropped);
        return dropped;
    }

    @Override
    public boolean forceAnchor() {
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
                && (anchoredHeight - messageHeight) <= MAX_EVIDENCE_CHAIN_BLOCKS;
        if (haveAnchor && !anchored) {
            log.debug("Evidence for message at height {} omits anchor chain: gap {} exceeds {}",
                    messageHeight, anchoredHeight - messageHeight, MAX_EVIDENCE_CHAIN_BLOCKS);
        }
        long toHeight = anchored ? anchoredHeight : messageHeight;
        for (long h = messageHeight; h <= toHeight; h++) {
            Optional<AppBlock> block = currentLedger.block(h);
            if (block.isEmpty()) {
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

    @Override
    public Map<String, Object> status() {
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
        if (!sinkRunners.isEmpty()) {
            Map<String, Object> sinks = new LinkedHashMap<>();
            Map<String, Object> webhooks = new LinkedHashMap<>(); // back-compat (keyed by URL)
            for (SinkRunner runner : sinkRunners) {
                Map<String, Object> sinkStatus = new LinkedHashMap<>();
                sinkStatus.put("cursor", runner.cursor());
                sinkStatus.put("delivered", runner.deliveredCount());
                sinkStatus.put("lagBlocks", Math.max(0, tipHeight() - runner.cursor()));
                if (runner.lastError() != null) {
                    sinkStatus.put("lastError", runner.lastError());
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

    // ------------------------------------------------------------------
    // Subsystem lifecycle
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return "app-chain";
    }

    @Override
    public synchronized void start() {
        if (running.get())
            return;

        log.info("Starting app chain '{}' (member: {}, members: {}, peers: {}, sequencing: {})",
                config.chainId(), signer.publicKeyHex(), group.size(), config.peers(),
                config.sequencingEnabled());

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
            chainEngine.setOnBlockFinalized(this::onBlockFinalized);
            chainEngine.setL1RefSupplier(this::stableL1Ref);
            chainEngine.setL1RefValidator(this::checkL1Ref);
            // L1 observations (008.4 I3.2) — misconfiguration fails start:
            // observers are consensus-critical and must match on all members
            this.observationService = L1ObservationService.fromConfig(
                    config.pluginSettings(), Math.max(config.l1StabilityDepth(), 1) + 64,
                    pluginClassLoader, log);
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
            this.engine = chainEngine;
            // Deliver consensus messages that raced engine wiring (see route())
            AppMessage early;
            while ((early = earlyConsensus.poll()) != null) {
                chainEngine.onConsensusMessage(early);
            }

            boolean anchorScriptMode = config.anchor() != null && config.anchor().scriptMode();
            java.util.function.Supplier<Long> anchorSlotSupplier = () -> {
                AppChainEngine.L1Ref last = recentL1Points.peekLast();
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
                            anchorSlotSupplier);
                    this.scriptAnchorService = scriptService;
                } catch (Exception e) {
                    log.warn("Script-anchor service unavailable: {}", e.toString());
                }
            }
            buildSinks(ledgerStore);
            subscribeL1Events();
        }

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-chain-" + config.chainId());
            t.setDaemon(true);
            return t;
        });
        this.scheduler = exec;
        running.set(true);

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
            exec.scheduleWithFixedDelay(currentAnchor::tick, 10, 10, TimeUnit.SECONDS);
            log.info("App-chain L1 anchoring enabled (address: {}, every {} blocks, label {})",
                    currentAnchor.anchorAddress(), config.anchor().everyBlocks(),
                    config.anchor().metadataLabel());
        }
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor != null && config.anchoringEnabled()
                && config.anchor() != null && config.anchor().scriptMode()) {
            exec.scheduleWithFixedDelay(currentScriptAnchor::tick, 10, 10, TimeUnit.SECONDS);
            log.info("App-chain L1 SCRIPT anchoring enabled (008.4): wallet={}, every {} blocks",
                    currentScriptAnchor.anchorAddress(), config.anchor().everyBlocks());
        }
        if (config.retentionEnabled()) {
            exec.scheduleWithFixedDelay(this::retentionTick, 30, 30, TimeUnit.SECONDS);
            log.info("App-chain retention enabled: bodies pruned below L1_FINAL anchor "
                    + "(keeping the most-recent {} block(s))", config.retentionKeepBlocks());
        }
    }

    /** Prune message bodies below the L1_FINAL anchor horizon (E4.4). */
    private void retentionTick() {
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
                log.warn("App-chain retention tick failed: {}", e.toString());
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
    private void subscribeL1Events() {
        if (eventBus == null) {
            return;
        }
        try {
            eventSubscriptions.add(eventBus.subscribe(
                    com.bloxbean.cardano.yano.api.events.BlockAppliedEvent.class,
                    ctx -> onL1BlockApplied(ctx.event()),
                    com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()));
            eventSubscriptions.add(eventBus.subscribe(
                    com.bloxbean.cardano.yano.api.events.RollbackEvent.class,
                    ctx -> onL1Rollback(ctx.event()),
                    com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()));
        } catch (Exception e) {
            log.warn("Failed to subscribe app-chain to L1 events: {}", e.toString());
        }
    }

    private void onL1BlockApplied(com.bloxbean.cardano.yano.api.events.BlockAppliedEvent event) {
        try {
            recentL1Points.addLast(new AppChainEngine.L1Ref(event.slot(),
                    HexUtil.decodeHexString(event.blockHash())));
            while (recentL1Points.size() > Math.max(config.l1StabilityDepth(), 1) + 64) {
                recentL1Points.pollFirst();
            }
            // L1 observations (008.4 I3.2): EVERY member recomputes (feeds the
            // verification window). Injection is STABILITY-GATED for rollback
            // safety — the app chain never rolls back, so a fact may only be
            // sequenced once it is l1.stability-depth confirmations old. All
            // members drain at the same L1 block; the scheduled proposer
            // injects (the message then replicates via the shared pool).
            L1ObservationService currentObservations = observationService;
            if (currentObservations != null && event.block() != null) {
                currentObservations.onL1Block(event.slot(),
                        HexUtil.decodeHexString(event.blockHash()), event.block());
                AppChainEngine.L1Ref stable = stableL1Ref();
                if (stable != null) {
                    List<com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation> ready =
                            currentObservations.drainInjectable(stable.slot());
                    if (!ready.isEmpty() && isScheduledProposer()) {
                        for (var observation : ready) {
                            injectObservation(observation);
                        }
                    }
                }
            }
            AnchorService currentAnchor = anchorService;
            ScriptAnchorService currentScriptAnchor = scriptAnchorService;
            if ((currentAnchor != null || currentScriptAnchor != null) && event.block() != null
                    && event.block().getTransactionBodies() != null) {
                List<String> txHashes = event.block().getTransactionBodies().stream()
                        .map(com.bloxbean.cardano.yaci.core.model.TransactionBody::getTxHash)
                        .toList();
                AnchorService.ConfirmedAnchor confirmed = currentAnchor != null
                        ? currentAnchor.onL1Block(event.slot(), txHashes) : null;
                if (confirmed == null && currentScriptAnchor != null) {
                    confirmed = currentScriptAnchor.onL1Block(event.slot(), txHashes);
                }
                if (confirmed != null && eventBus != null) {
                    eventBus.publish(new com.bloxbean.cardano.yano.api.events.AppChainAnchoredEvent(
                                    config.chainId(), confirmed.fromHeight(), confirmed.toHeight(),
                                    confirmed.txHash(), confirmed.l1Slot()),
                            EventMetadata.builder().build(), PublishOptions.builder().build());
                }
            }
        } catch (Exception e) {
            log.warn("App-chain L1 block handling failed: {}", e.toString());
        }
    }

    private void onL1Rollback(com.bloxbean.cardano.yano.api.events.RollbackEvent event) {
        try {
            long targetSlot = event.target() != null ? event.target().getSlot() : 0;
            recentL1Points.removeIf(ref -> ref.slot() > targetSlot);
            AnchorService currentAnchor = anchorService;
            if (currentAnchor != null) {
                currentAnchor.onL1Rollback(targetSlot);
            }
            ScriptAnchorService currentScriptAnchor = scriptAnchorService;
            if (currentScriptAnchor != null) {
                currentScriptAnchor.onL1Rollback(targetSlot);
            }
            L1ObservationService currentObservations = observationService;
            if (currentObservations != null) {
                currentObservations.onL1Rollback(targetSlot);
            }
        } catch (Exception e) {
            log.warn("App-chain L1 rollback handling failed: {}", e.toString());
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

    /** Build finalized-stream sinks: built-in webhooks + ServiceLoader plugins (E3.2). */
    private void buildSinks(AppLedgerStore ledgerStore) {
        for (String url : config.webhookUrls()) {
            sinkRunners.add(new SinkRunner(new WebhookSink(url, config.chainId(), log), ledgerStore, log));
        }
        // Plugin sinks (e.g. Kafka) via ServiceLoader on the plugin classloader.
        ClassLoader[] classLoaders = pluginClassLoader != null
                ? new ClassLoader[]{pluginClassLoader}
                : new ClassLoader[]{Thread.currentThread().getContextClassLoader()};
        for (ClassLoader classLoader : classLoaders) {
            for (com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory factory :
                    java.util.ServiceLoader.load(
                            com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory.class,
                            classLoader)) {
                java.util.Map<String, String> sinkConfig = sinkConfigFor(factory.scheme());
                if (sinkConfig.isEmpty()) {
                    continue;
                }
                try {
                    for (var sink : factory.create(config.chainId(), sinkConfig)) {
                        sinkRunners.add(new SinkRunner(sink, ledgerStore, log));
                        log.info("App-chain sink '{}' registered via {} plugin",
                                sink.id(), factory.scheme());
                    }
                } catch (Exception e) {
                    log.error("Failed to build '{}' sink(s): {}", factory.scheme(), e.toString());
                }
            }
        }
    }

    /** Config sub-map for a sink scheme: yano.app-chain.sinks.<scheme>.* → stripped keys. */
    private java.util.Map<String, String> sinkConfigFor(String scheme) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        String prefix = "sinks." + scheme + ".";
        for (var entry : config.pluginSettings().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
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
                log.warn("Finalized-block listener failed: {}", e.toString());
            }
        }
        if (eventBus != null) {
            try {
                eventBus.publish(new AppBlockFinalizedEvent(block, blockHash),
                        EventMetadata.builder().build(), PublishOptions.builder().build());
            } catch (Exception e) {
                log.warn("Failed to publish AppBlockFinalizedEvent: {}", e.toString());
            }
        }
    }

    private void connectTick() {
        if (!running.get())
            return;
        for (AppPeerLink peerClient : peerClients) {
            try {
                // Async: an unreachable peer must never wedge this scheduler
                // (proposer/anchor/catch-up ticks share it) — 008.2 fix
                peerClient.ensureConnectedAsync();
            } catch (Exception e) {
                log.debug("App-peer connect attempt failed for {}: {}", peerClient.peerId(), e.toString());
            }
        }
    }

    private void keepAliveTick() {
        if (!running.get())
            return;
        for (AppPeerLink peerClient : peerClients) {
            peerClient.keepAliveTick();
        }
    }

    private void sinkTick() {
        if (!running.get())
            return;
        for (SinkRunner runner : sinkRunners) {
            try {
                runner.deliveryTick();
            } catch (Exception e) {
                log.warn("Sink delivery tick failed for {}: {}", runner.id(), e.toString());
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false))
            return;
        log.info("Stopping app chain '{}'", config.chainId());
        for (var subscription : eventSubscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
        eventSubscriptions.clear();
        // Sink runners hold the ledger that is about to close and are re-created
        // by start(); close + drop them so a restart doesn't tick stale sinks
        // against a closed RocksDB handle (or double-deliver). External
        // finalizedListeners (SSE, metrics) are NOT cleared — they survive a restart.
        for (SinkRunner runner : sinkRunners) {
            runner.close();
        }
        sinkRunners.clear();
        anchorService = null;
        ScheduledExecutorService exec = scheduler;
        scheduler = null;
        if (exec != null) {
            exec.shutdownNow();
        }
        ScheduledExecutorService sinkExec = sinkScheduler;
        sinkScheduler = null;
        if (sinkExec != null) {
            sinkExec.shutdownNow();
        }
        for (AppPeerLink peerClient : peerClients) {
            peerClient.shutdown();
        }
        AppChainEngine currentEngine = engine;
        engine = null;
        if (currentEngine != null) {
            currentEngine.close();
        }
        AppLedgerStore currentLedger = ledger;
        ledger = null;
        if (currentLedger != null) {
            currentLedger.close();
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
