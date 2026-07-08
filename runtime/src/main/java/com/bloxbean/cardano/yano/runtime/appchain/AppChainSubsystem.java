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
    private static final int SEEN_IDS_LIMIT = 100_000;
    private static final long CONNECT_INTERVAL_SECONDS = 5;
    private static final long KEEPALIVE_INTERVAL_SECONDS = 20;
    private static final String SYSTEM_TOPIC_PREFIX = "~";

    private final AppChainConfig config;
    private final long protocolMagic;
    private final EventBus eventBus;
    private final Logger log;

    private final AppMessageSigner signer;
    private final Set<String> memberKeys;
    private final AtomicLong senderSeq = new AtomicLong(0);

    private final Set<String> seenMessageIds;
    private final ConcurrentLinkedDeque<ReceivedAppMessage> recentMessages = new ConcurrentLinkedDeque<>();
    private final List<AppPeerClient> peerClients = new ArrayList<>();

    // M2: sequenced ledger (present when sequencing is enabled)
    private final AppMsgPool pool;
    private volatile AppLedgerStore ledger;
    private volatile AppChainEngine engine;
    private final AppStateMachine stateMachine;
    private final String ledgerPath;

    // M3: L1 anchoring + stable L1 reference tracking
    private volatile AnchorService anchorService;
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

    // M4: catch-up + stall detection
    private static final long STALL_WINDOW_MS = 60_000;
    private volatile long bestPeerTip;
    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile long lastStallEventAt;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

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
        this.signer = new AppMessageSigner(config.signingKeyHex());
        this.memberKeys = normalizeMemberKeys(config.memberKeysHex());
        this.seenMessageIds = boundedSet(SEEN_IDS_LIMIT);
        this.pool = new AppMsgPool(10_000);
        this.stateMachine = stateMachine != null
                ? stateMachine
                : resolveStateMachine(config.stateMachineId(), pluginClassLoader, log);
        this.ledgerPath = (ledgerPath != null ? ledgerPath : "./app-chain") + "/" + config.chainId();

        if (!memberKeys.contains(signer.publicKeyHex())) {
            throw new IllegalArgumentException(
                    "This node's app-chain public key " + signer.publicKeyHex()
                            + " is not in the configured member list (yano.app-chain.members)");
        }
        if (config.sequencingEnabled()) {
            String proposer = config.proposerKeyHex().toLowerCase(Locale.ROOT);
            if (!memberKeys.contains(proposer)) {
                throw new IllegalArgumentException(
                        "Configured proposer " + proposer + " is not in the member list");
            }
            if (config.threshold() > memberKeys.size()) {
                throw new IllegalArgumentException("Finality threshold " + config.threshold()
                        + " exceeds member count " + memberKeys.size());
            }
        }
        AppPeerClient.CatchUpHandler catchUpHandler =
                config.sequencingEnabled() ? this::onCatchUpBlocks : null;
        for (AppChainConfig.AppPeer peer : config.peers()) {
            peerClients.add(new AppPeerClient(peer, protocolMagic, transportConfig(), catchUpHandler, log));
        }
    }

    /**
     * Resolve a state machine by id: built-ins first, then
     * {@link AppStateMachineProvider} implementations discovered via
     * ServiceLoader on the plugin classloader (custom app chains deployed as
     * plugin jars on a stock yano distribution) and the context classloader.
     */
    private static AppStateMachine resolveStateMachine(String id, ClassLoader pluginClassLoader, Logger log) {
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
                        return provider.create();
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

    /** Transport config shared by inbound (server) and outbound (client) agents. */
    private AppMsgSubmissionConfig transportConfig() {
        return AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(config.chainId()))
                .maxMessageSize(Math.max(config.maxMessageBytes(),
                        // consensus proposals carry whole blocks — allow headroom
                        config.maxMessageBytes() * Math.max(1, Math.min(config.maxBlockMessages(), 64)) + 8192))
                .maxTtlSeconds(config.maxTtlSeconds())
                .validator(this::verifyEnvelope)
                .build();
    }

    /**
     * Envelope authentication: Ed25519 signature by a registered group member.
     * Structural checks (id recompute, size, TTL, chain) already ran in the agent.
     */
    AppMsgValidator.Result verifyEnvelope(AppMessage message) {
        if (message.getAuthScheme() != AuthScheme.ED25519.getValue())
            return AppMsgValidator.Result.reject("unsupported auth scheme: " + message.getAuthScheme());

        byte[] sender = message.getSender();
        if (sender == null || sender.length != 32)
            return AppMsgValidator.Result.reject("invalid sender key length");

        String senderHex = HexUtil.encodeHexString(sender).toLowerCase(Locale.ROOT);
        if (!memberKeys.contains(senderHex))
            return AppMsgValidator.Result.reject("sender not in app-chain member list: " + senderHex);

        byte[] proof = message.getAuthProof();
        if (proof == null || proof.length == 0)
            return AppMsgValidator.Result.reject("missing signature");

        if (!AppMessageSigner.verify(proof, message.signedBodyBytes(), sender))
            return AppMsgValidator.Result.reject("signature verification failed");

        return AppMsgValidator.Result.accept();
    }

    /**
     * Wire node-level L1 access (tx submission for anchoring, UTXO queries for
     * anchor input selection). Called by the runtime before start; optional —
     * without it, anchoring is unavailable even when configured.
     */
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
        for (AppPeerClient peerClient : peerClients) {
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
            if (!seenMessageIds.add(message.getMessageIdHex())) {
                duplicateCount.incrementAndGet();
                continue;
            }
            receivedCount.incrementAndGet();
            route(message, ReceivedAppMessage.Source.PEER);
            relay(message);
        }
    }

    private void route(AppMessage message, ReceivedAppMessage.Source source) {
        String topic = message.getTopic() != null ? message.getTopic() : "";
        if (topic.startsWith(SYSTEM_TOPIC_PREFIX)) {
            AppChainEngine currentEngine = engine;
            if (currentEngine != null) {
                currentEngine.onConsensusMessage(message);
            }
            return;
        }
        // Ordinary app message: pool for sequencing + observability surface
        AppLedgerStore currentLedger = ledger;
        if (currentLedger != null && currentLedger.messageHeight(message.getMessageId()).isPresent()) {
            duplicateCount.incrementAndGet();
            return; // already finalized in a block
        }
        pool.add(message);
        record(message, source);
    }

    private void relay(AppMessage message) {
        for (AppPeerClient peerClient : peerClients) {
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

    /** Build, sign and diffuse an envelope on the given topic (system or app). */
    private AppMessage buildAndDiffuse(String topic, byte[] body, long ttlSeconds) {
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

        seenMessageIds.add(message.getMessageIdHex());
        relay(message);
        return message;
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
        Objects.requireNonNull(body, "body");
        if (body.length == 0)
            throw new IllegalArgumentException("body must not be empty");
        if (body.length > config.maxMessageBytes())
            throw new IllegalArgumentException("body exceeds max message size ("
                    + body.length + " > " + config.maxMessageBytes() + ")");
        String effectiveTopic = topic != null ? topic : "";
        if (effectiveTopic.startsWith(SYSTEM_TOPIC_PREFIX))
            throw new IllegalArgumentException("Topics starting with '~' are reserved for the framework");

        AppMessage message = buildAndDiffuse(effectiveTopic, body, config.defaultTtlSeconds());
        submittedCount.incrementAndGet();
        pool.add(message);
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

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chainId", config.chainId());
        status.put("memberKey", signer.publicKeyHex());
        status.put("members", memberKeys.size());
        status.put("running", running.get());
        status.put("sequencing", config.sequencingEnabled());
        if (config.sequencingEnabled()) {
            status.put("role", engine != null && engine.isProposer() ? "proposer" : "member");
            status.put("tipHeight", tipHeight());
            status.put("stateRoot", HexUtil.encodeHexString(stateRoot()));
            status.put("stateMachine", stateMachine.id());
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor != null) {
            status.put("anchor", currentAnchor.status());
        }
        if (!webhookSinks.isEmpty()) {
            Map<String, Object> webhooks = new LinkedHashMap<>();
            for (WebhookStreamSink sink : webhookSinks) {
                Map<String, Object> sinkStatus = new LinkedHashMap<>();
                sinkStatus.put("cursor", sink.cursor());
                sinkStatus.put("delivered", sink.deliveredCount());
                if (sink.lastError() != null) {
                    sinkStatus.put("lastError", sink.lastError());
                }
                webhooks.put(sink.url(), sinkStatus);
            }
            status.put("webhooks", webhooks);
        }
        status.put("poolSize", pool.size());
        status.put("submitted", submittedCount.get());
        status.put("received", receivedCount.get());
        status.put("relayed", relayedCount.get());
        status.put("duplicates", duplicateCount.get());
        status.put("storedMessages", recentMessages.size());
        Map<String, Boolean> peers = new LinkedHashMap<>();
        for (AppPeerClient peerClient : peerClients) {
            peers.put(peerClient.peerId(), peerClient.isConnected());
        }
        status.put("peers", peers);
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
                config.chainId(), signer.publicKeyHex(), memberKeys.size(), config.peers(),
                config.sequencingEnabled());

        if (config.sequencingEnabled()) {
            AppLedgerStore ledgerStore = new AppLedgerStore(ledgerPath, log);
            this.ledger = ledgerStore;
            AppChainEngine chainEngine = new AppChainEngine(
                    config,
                    ledgerStore,
                    pool,
                    stateMachine,
                    signer,
                    memberKeys,
                    config.threshold(),
                    HexUtil.decodeHexString(config.proposerKeyHex()),
                    Math.max(config.blockIntervalMs() * 5, 10_000),
                    config.maxBlockMessages(),
                    config.maxMessageBytes() * 64L,
                    (topic, body) -> buildAndDiffuse(topic, body, Math.max(60, config.maxTtlSeconds() / 6)),
                    log);
            chainEngine.setOnBlockFinalized(this::onBlockFinalized);
            chainEngine.setL1RefSupplier(this::stableL1Ref);
            this.engine = chainEngine;

            if (config.anchoringEnabled()) {
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
                }
            }
            for (String url : config.webhookUrls()) {
                webhookSinks.add(new WebhookStreamSink(url, config.chainId(), ledgerStore, log));
            }
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
        exec.scheduleWithFixedDelay(pool::sweepExpired, 30, 30, TimeUnit.SECONDS);
        AppChainEngine currentEngine = engine;
        if (currentEngine != null && currentEngine.isProposer()) {
            exec.scheduleWithFixedDelay(currentEngine::proposeTick,
                    config.blockIntervalMs(), config.blockIntervalMs(), TimeUnit.MILLISECONDS);
            log.info("This node is the app-chain sequencer (proposer) for '{}'", config.chainId());
        }
        if (currentEngine != null) {
            exec.scheduleWithFixedDelay(this::catchUpTick, 5, 5, TimeUnit.SECONDS);
        }
        if (!webhookSinks.isEmpty()) {
            exec.scheduleWithFixedDelay(this::webhookTick, 5, 5, TimeUnit.SECONDS);
            log.info("App-chain webhook sinks enabled: {}", config.webhookUrls());
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor != null) {
            exec.scheduleWithFixedDelay(currentAnchor::tick, 10, 10, TimeUnit.SECONDS);
            log.info("App-chain L1 anchoring enabled (address: {}, every {} blocks, label {})",
                    currentAnchor.anchorAddress(), config.anchor().everyBlocks(),
                    config.anchor().metadataLabel());
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
            AnchorService currentAnchor = anchorService;
            if (currentAnchor != null && event.block() != null
                    && event.block().getTransactionBodies() != null) {
                List<String> txHashes = event.block().getTransactionBodies().stream()
                        .map(com.bloxbean.cardano.yaci.core.model.TransactionBody::getTxHash)
                        .toList();
                AnchorService.ConfirmedAnchor confirmed = currentAnchor.onL1Block(event.slot(), txHashes);
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
        } catch (Exception e) {
            log.warn("App-chain L1 rollback handling failed: {}", e.toString());
        }
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
    private final List<WebhookStreamSink> webhookSinks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public AutoCloseable subscribeFinalized(FinalizedBlockListener listener) {
        Objects.requireNonNull(listener, "listener");
        finalizedListeners.add(listener);
        return () -> finalizedListeners.remove(listener);
    }

    private void onBlockFinalized(AppBlock block, byte[] blockHash) {
        lastProgressAt = System.currentTimeMillis();
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
        for (AppPeerClient peerClient : peerClients) {
            try {
                peerClient.ensureConnected();
            } catch (Exception e) {
                log.debug("App-peer connect attempt failed for {}: {}", peerClient.peerId(), e.toString());
            }
        }
    }

    private void keepAliveTick() {
        if (!running.get())
            return;
        for (AppPeerClient peerClient : peerClients) {
            peerClient.keepAliveTick();
        }
    }

    private void webhookTick() {
        if (!running.get())
            return;
        for (WebhookStreamSink sink : webhookSinks) {
            try {
                sink.deliveryTick();
            } catch (Exception e) {
                log.warn("Webhook delivery tick failed for {}: {}", sink.url(), e.toString());
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
        anchorService = null;
        ScheduledExecutorService exec = scheduler;
        scheduler = null;
        if (exec != null) {
            exec.shutdownNow();
        }
        for (AppPeerClient peerClient : peerClients) {
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

    private static Set<String> boundedSet(int maxSize) {
        return Collections.synchronizedSet(Collections.newSetFromMap(
                new LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > maxSize;
                    }
                }));
    }
}
