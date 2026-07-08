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
                    AppLedgerStore ledger = subsystem != null ? subsystem.ledgerOrNull() : null;
                    if (ledger == null) {
                        return new AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0);
                    }
                    return new AppChainSyncServerAgent.BlockRangeProvider.Range(
                            ledger.blockBytesRange(fromHeight, toHeight), ledger.tipHeight());
                });
        return List.of(gossipFactory, catchUpFactory);
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
        for (AppChainSubsystem subsystem : chains.values()) {
            subsystem.start();
        }
    }

    @Override
    public void stop() {
        List<AppChainSubsystem> reversed = new ArrayList<>(chains.values());
        Collections.reverse(reversed);
        for (AppChainSubsystem subsystem : reversed) {
            try {
                subsystem.stop();
            } catch (Exception e) {
                log.warn("Error stopping app chain {}: {}", subsystem.chainId(), e.toString());
            }
        }
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
