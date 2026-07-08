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
    private final ClassLoader pluginClassLoader;

    private final com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider signer;
    private final MemberGroup group;
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
        this.seenMessageIds = boundedSet(SEEN_IDS_LIMIT);
        this.pool = new AppMsgPool(10_000);
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
            throw new IllegalArgumentException(
                    "This node's app-chain public key " + signer.publicKeyHex()
                            + " is not in the configured member list (yano.app-chain.members)");
        }
        if (config.sequencingEnabled()) {
            String proposer = config.proposerKeyHex().toLowerCase(Locale.ROOT);
            if (!group.contains(proposer)) {
                throw new IllegalArgumentException(
                        "Configured proposer " + proposer + " is not in the member list");
            }
            if (config.threshold() > group.size()) {
                throw new IllegalArgumentException("Finality threshold " + config.threshold()
                        + " exceeds member count " + group.size());
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
        if (!group.contains(senderHex))
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
        AppLedgerStore currentLedger = ledger;
        return currentLedger != null
                ? currentLedger.messagesBySender(sender, fromHeight, Math.max(1, Math.min(limit, 1000)))
                : List.of();
    }

    // ------------------------------------------------------------------
    // Key rotation (ADR 006 E4.5): staged member add / re-threshold / retire.
    // Operator-coordinated: apply the same steps on EVERY node (runbook in the
    // user guide). Rotated state persists in ledger meta and wins over config.
    // ------------------------------------------------------------------

    private static final String META_MEMBERS_OVERRIDE = "members_override";
    private static final String META_THRESHOLD_OVERRIDE = "threshold_override";

    private void loadMemberOverride(AppLedgerStore ledgerStore) {
        String membersCsv = ledgerStore.metaString(META_MEMBERS_OVERRIDE);
        if (membersCsv == null || membersCsv.isBlank()) {
            return;
        }
        Set<String> members = new HashSet<>();
        for (String key : membersCsv.split(",")) {
            if (!key.isBlank()) members.add(key.trim().toLowerCase(Locale.ROOT));
        }
        long threshold = ledgerStore.metaLong(META_THRESHOLD_OVERRIDE, group.threshold());
        group.update(members, (int) threshold);
        log.info("App-chain '{}' member override loaded: {} member(s), threshold {} "
                + "(rotated state overrides config)", config.chainId(), members.size(), threshold);
        if (!group.contains(signer.publicKeyHex())) {
            log.warn("This node's key {} is NOT in the rotated member set — it can observe but "
                    + "its submissions/votes will be rejected by peers", signer.publicKeyHex());
        }
    }

    private void persistMemberOverride() {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger != null) {
            currentLedger.metaPutString(META_MEMBERS_OVERRIDE, String.join(",", group.members()));
            currentLedger.metaPutLong(META_THRESHOLD_OVERRIDE, group.threshold());
        }
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
        String normalized = normalizeMemberKeys(Set.of(publicKeyHex)).iterator().next();
        Set<String> updated = new HashSet<>(group.members());
        if (!updated.add(normalized)) {
            return; // already a member — idempotent
        }
        group.update(updated, group.threshold());
        persistMemberOverride();
        log.info("App-chain '{}' member ADDED: {} ({} member(s), threshold {})",
                config.chainId(), normalized, group.size(), group.threshold());
    }

    @Override
    public void removeMember(String publicKeyHex) {
        String normalized = publicKeyHex.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(config.proposerKeyHex().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Cannot remove the configured proposer "
                    + "(rotate the proposer key via config + restart, or wait for S2 rotation)");
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
        group.update(updated, group.threshold());
        persistMemberOverride();
        log.info("App-chain '{}' member RETIRED: {} ({} member(s), threshold {})",
                config.chainId(), normalized, group.size(), group.threshold());
    }

    @Override
    public void setThreshold(int threshold) {
        if (threshold < 1 || threshold > group.size()) {
            throw new IllegalArgumentException("Threshold must be in [1, " + group.size() + "]");
        }
        group.update(group.members(), threshold);
        persistMemberOverride();
        log.info("App-chain '{}' threshold set to {} ({} member(s))",
                config.chainId(), threshold, group.size());
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
        AnchorService currentAnchor = anchorService;
        if (currentAnchor == null) {
            return false; // anchoring disabled
        }
        return currentAnchor.forceAnchorNow();
    }

    @Override
    public long snapshot(String snapshotPath) {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            throw new IllegalStateException("App chain has no ledger (sequencing disabled)");
        }
        currentLedger.createSnapshot(snapshotPath);
        return currentLedger.tipHeight();
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

        List<String> members = new ArrayList<>(group.members());
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
        boolean anchored = haveAnchor && (anchoredHeight - messageHeight) <= MAX_EVIDENCE_CHAIN_BLOCKS;
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
                group.threshold(), anchorRef));
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
            status.put("role", engine != null && engine.isProposer() ? "proposer" : "member");
            status.put("tipHeight", tipHeight());
            status.put("stateRoot", HexUtil.encodeHexString(stateRoot()));
            status.put("stateMachine", stateMachine.id());
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor != null) {
            status.put("anchor", currentAnchor.status());
        }
        if (!sinkRunners.isEmpty()) {
            Map<String, Object> sinks = new LinkedHashMap<>();
            Map<String, Object> webhooks = new LinkedHashMap<>(); // back-compat (keyed by URL)
            for (SinkRunner runner : sinkRunners) {
                Map<String, Object> sinkStatus = new LinkedHashMap<>();
                sinkStatus.put("cursor", runner.cursor());
                sinkStatus.put("delivered", runner.deliveredCount());
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
        status.put("submissionsPaused", submissionsPaused.get());
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
                config.chainId(), signer.publicKeyHex(), group.size(), config.peers(),
                config.sequencingEnabled());

        if (config.sequencingEnabled()) {
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
            AppChainEngine chainEngine = new AppChainEngine(
                    config,
                    ledgerStore,
                    pool,
                    stateMachine,
                    signer,
                    group,
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
