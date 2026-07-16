package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgValidator;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.*;

/**
 * Hosts one {@link AppChainSubsystem} per configured chain (multi-chain per
 * node, ADR app-layer/006 E5.2) behind a single set of inbound server agents.
 * <p>
 * Inbound sessions cannot carry one agent per chain (all app-message agents
 * share protocol id 100), so the manager installs ONE gossip agent and ONE
 * catch-up agent per session, scoped to the union of the chain ids, and
 * dispatches every verified envelope / range request to the owning chain's
 * subsystem by {@code chain-id}. Outbound app-peer connections remain
 * per-chain (each chain has its own peer list).
 */
public final class AppChainManager implements Subsystem, AppChainGateways {

    private final Map<String, AppChainSubsystem> chains;
    private final List<ManagedChain> lifecycleChains;
    private final Logger log;

    public AppChainManager(List<AppChainSubsystem> subsystems, Logger log) {
        Map<String, AppChainSubsystem> byId = new LinkedHashMap<>();
        for (AppChainSubsystem subsystem : subsystems) {
            AppChainSubsystem previous = byId.put(subsystem.chainId(), subsystem);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate app chain id: " + subsystem.chainId());
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("At least one app chain is required");
        }
        this.chains = Collections.unmodifiableMap(byId);
        this.lifecycleChains = byId.values().stream()
                .<ManagedChain>map(SubsystemManagedChain::new)
                .toList();
        this.log = Objects.requireNonNull(log, "log");
    }

    // ------------------------------------------------------------------
    // AppChainGateways
    // ------------------------------------------------------------------

    @Override
    public Optional<AppChainGateway> byId(String chainId) {
        return Optional.ofNullable(chains.get(chainId));
    }

    @Override
    public Collection<AppChainGateway> all() {
        return Collections.unmodifiableCollection(chains.values());
    }

    // ------------------------------------------------------------------
    // Shared inbound front (one agent per protocol per session)
    // ------------------------------------------------------------------

    /** Server agent factories serving ALL hosted chains; install into ServeSubsystem. */
    public List<AgentFactory> serverAgentFactories() {
        AgentFactory gossipFactory = () -> {
            AppMsgSubmissionServerAgent serverAgent =
                    new AppMsgSubmissionServerAgent(unionTransportConfig());
            serverAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {
                    dispatchInbound(reply.getMessages());
                }
            });
            return serverAgent;
        };

        boolean anySequencing = chains.values().stream()
                .anyMatch(s -> s.chainConfig().sequencingEnabled());
        if (!anySequencing) {
            return List.of(gossipFactory);
        }
        AgentFactory catchUpFactory = () -> new AppChainSyncServerAgent(
                (chainId, fromHeight, toHeight) -> {
                    AppChainSubsystem subsystem = chains.get(chainId);
                    if (subsystem == null) {
                        return new AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0);
                    }
                    return subsystem.catchUpRange(chainId, fromHeight, toHeight);
                });
        return List.of(gossipFactory, catchUpFactory);
    }

    /**
     * Ride the app-layer protocols on the node's L1 upstream session when the
     * configured remote is also an app-group peer (ADR 005 M1 unification —
     * one TCP connection per peer pair instead of an extra dedicated dial).
     * <p>
     * Decorates the sync subsystem's {@link PeerClientFactory}: sessions to
     * the matching endpoint are armed with protocols 100/103 pre-connect, and
     * the matching chains' peer links prefer the shared session (with an
     * automatic dedicated-dial fallback while it is down). Returns the
     * delegate unchanged when {@code transportMode} is {@code dedicated}, no
     * remote is configured, or no hosted chain peers with the remote.
     */
    public com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory wrapPeerClientFactory(
            com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory delegate,
            String transportMode, String remoteHost, int remotePort) {
        if (!"shared".equalsIgnoreCase(transportMode == null ? "" : transportMode.trim())
                || remoteHost == null || remoteHost.isBlank() || remotePort <= 0) {
            return delegate;
        }
        String remoteKey = SharedAppTransport.key(remoteHost, remotePort);
        List<AppChainSubsystem> matching = chains.values().stream()
                .filter(subsystem -> subsystem.chainConfig().peers().stream()
                        .anyMatch(p -> SharedAppTransport.key(p.host(), p.port()).equals(remoteKey)))
                .toList();
        if (matching.isEmpty()) {
            log.debug("App transport 'shared' requested but the L1 remote {} is not an "
                    + "app-group peer of any hosted chain — keeping dedicated dials", remoteKey);
            return delegate;
        }
        SharedAppTransport transport =
                new SharedAppTransport(unionTransportConfig(), Set.of(remoteKey), log);
        for (AppChainSubsystem subsystem : matching) {
            subsystem.wireSharedTransport(transport, Set.of(remoteKey));
        }
        log.info("App transport: chains {} reuse the L1 peer session to {} for app "
                        + "diffusion/catch-up (protocols 100/103); dedicated fallback engages "
                        + "if the session stays down",
                matching.stream().map(AppChainSubsystem::chainId).toList(), remoteKey);
        return transport.wrap(delegate);
    }

    /** Union transport config: all chain ids, most permissive size/TTL, per-chain auth dispatch. */
    private AppMsgSubmissionConfig unionTransportConfig() {
        Set<String> chainIds = new HashSet<>();
        int maxMessageSize = 0;
        long maxTtlSeconds = 0;
        for (AppChainSubsystem subsystem : chains.values()) {
            AppMsgSubmissionConfig chainConfig = subsystem.chainTransportConfig();
            chainIds.addAll(chainConfig.getChainIds());
            maxMessageSize = Math.max(maxMessageSize, chainConfig.getMaxMessageSize());
            maxTtlSeconds = Math.max(maxTtlSeconds, chainConfig.getMaxTtlSeconds());
        }
        return AppMsgSubmissionConfig.builder()
                .chainIds(chainIds)
                .maxMessageSize(maxMessageSize)
                .maxTtlSeconds(maxTtlSeconds)
                .validator(this::verifyByChain)
                .build();
    }

    private AppMsgValidator.Result verifyByChain(AppMessage message) {
        AppChainSubsystem subsystem = chains.get(message.getChainId());
        if (subsystem == null) {
            return AppMsgValidator.Result.reject("chain not hosted: " + message.getChainId());
        }
        // The shared inbound agent enforces structural limits against the UNION
        // (most permissive) config, so re-apply this chain's own size/TTL bounds
        // here — otherwise a stricter chain would accept messages a single-chain
        // deployment of it would reject.
        //
        // This first pass is deliberately coarse: a framework message on a
        // reserved '~' topic — notably ~consensus/propose, whose body is the
        // serialized block — may legitimately exceed max-message-bytes. The
        // subsystem verification immediately below applies the exact per-topic
        // proposal/vote/certificate limit and keeps every other topic at the
        // ordinary max-message-bytes limit.
        var config = subsystem.chainConfig();
        String topic = message.getTopic();
        boolean systemTopic = topic != null && topic.startsWith("~");
        long sizeCap = systemTopic ? config.blockMaxBytes() : config.maxMessageBytes();
        if (message.getSize() > sizeCap) {
            return AppMsgValidator.Result.reject("body exceeds chain max size ("
                    + message.getSize() + " > " + sizeCap + ")");
        }
        long now = System.currentTimeMillis() / 1000;
        if (config.maxTtlSeconds() > 0 && message.getExpiresAt() > now + config.maxTtlSeconds()) {
            return AppMsgValidator.Result.reject("expiresAt too far in the future (chain max TTL "
                    + config.maxTtlSeconds() + "s)");
        }
        return subsystem.verifyEnvelope(message);
    }

    private void dispatchInbound(List<AppMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        Map<String, List<AppMessage>> byChain = new LinkedHashMap<>();
        for (AppMessage message : messages) {
            byChain.computeIfAbsent(message.getChainId(), k -> new ArrayList<>()).add(message);
        }
        byChain.forEach((chainId, chainMessages) -> {
            AppChainSubsystem subsystem = chains.get(chainId);
            if (subsystem != null) {
                subsystem.onInboundMessages(chainMessages);
            } else {
                log.warn("Dropping {} message(s) for unhosted chain {}", chainMessages.size(), chainId);
            }
        });
    }

    // ------------------------------------------------------------------
    // Subsystem lifecycle (delegates to all chains)
    // ------------------------------------------------------------------

    @Override
    public String name() {
        return "app-chain";
    }

    @Override
    public void start() {
        startManagedChains(lifecycleChains, log);
    }

    /** Package-private deterministic lifecycle seam for adversarial tests. */
    static void startManagedChains(List<? extends ManagedChain> chains, Logger log) {
        List<ManagedChain> started = new ArrayList<>();
        try {
            for (ManagedChain subsystem : chains) {
                subsystem.start();
                started.add(subsystem);
            }
        } catch (RuntimeException | Error e) {
            // Roll back the chains we already started so a partial failure never
            // leaves orphan chains running (the kernel won't call our stop() —
            // the manager wasn't in its started list yet).
            Throwable failure = e;
            for (int i = started.size() - 1; i >= 0; i--) {
                try {
                    started.get(i).stop();
                } catch (Throwable stopError) {
                    failure = LifecycleFailures.merge(failure, stopError);
                    log.warn("Error rolling back app chain {} (errorType={})",
                            started.get(i).chainId(), stopError.getClass().getName());
                }
            }
            throw propagateLifecycleFailure(failure, "App-chain manager startup failed");
        }
    }

    @Override
    public void stop() {
        stopManagedChains(lifecycleChains, log);
    }

    /**
     * Terminally close every hosted chain in reverse order. This is distinct
     * from restartable {@link #stop()}: direct compatibility chains own their
     * legacy provider registries and release them from
     * {@link AppChainSubsystem#close()}.
     */
    @Override
    public void close() {
        List<AppChainSubsystem> reversed = new ArrayList<>(chains.values());
        Collections.reverse(reversed);
        Throwable failure = null;
        for (AppChainSubsystem subsystem : reversed) {
            try {
                subsystem.close();
            } catch (Throwable closeError) {
                failure = LifecycleFailures.merge(failure, closeError);
                log.warn("Error closing app chain {} (errorType={})",
                        subsystem.chainId(), closeError.getClass().getName());
            }
        }
        if (failure != null) {
            throw propagateLifecycleFailure(failure, "App-chain manager close failed");
        }
    }

    /** Package-private deterministic lifecycle seam for adversarial tests. */
    static void stopManagedChains(List<? extends ManagedChain> chains, Logger log) {
        List<ManagedChain> reversed = new ArrayList<>(chains);
        Collections.reverse(reversed);
        Throwable failure = null;
        for (ManagedChain subsystem : reversed) {
            try {
                subsystem.stop();
            } catch (Throwable stopError) {
                failure = LifecycleFailures.merge(failure, stopError);
                log.warn("Error stopping app chain {} (errorType={})",
                        subsystem.chainId(), stopError.getClass().getName());
            }
        }
        if (failure != null) {
            throw propagateLifecycleFailure(failure, "App-chain manager stop failed");
        }
    }

    interface ManagedChain {
        String chainId();

        void start();

        void stop();
    }

    private record SubsystemManagedChain(AppChainSubsystem subsystem) implements ManagedChain {
        @Override
        public String chainId() {
            return subsystem.chainId();
        }

        @Override
        public void start() {
            subsystem.start();
        }

        @Override
        public void stop() {
            subsystem.stop();
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

    @Override
    public SubsystemHealth health() {
        for (AppChainSubsystem subsystem : chains.values()) {
            SubsystemHealth health = subsystem.health();
            if (health.status() != SubsystemHealth.Status.UP) {
                return SubsystemHealth.down(name(), "chain " + subsystem.chainId() + ": "
                        + health.status());
            }
        }
        return SubsystemHealth.up(name());
    }
}
