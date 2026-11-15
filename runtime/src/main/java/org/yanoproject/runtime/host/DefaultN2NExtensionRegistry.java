package org.yanoproject.runtime.host;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.p2p.peer.PeerClientFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** One assembly's pre-connect extension plan. No agents, sockets or workers are created during registration. */
public final class DefaultN2NExtensionRegistry implements N2NExtensionRegistry {
    private final Map<Integer, AgentFactory> inbound = new LinkedHashMap<>();
    private final List<UnaryOperator<PeerClientFactory>> outbound = new ArrayList<>();
    private final boolean sharedOutbound;
    private boolean sealed;

    public DefaultN2NExtensionRegistry() { this(true); }
    public DefaultN2NExtensionRegistry(boolean sharedOutbound) { this.sharedOutbound = sharedOutbound; }
    @Override public boolean hasSharedOutboundSessions() { return sharedOutbound; }

    @Override
    public synchronized void registerInbound(int protocolId, AgentFactory factory) {
        requireOpen();
        if (protocolId < 100 || protocolId > 0x7fff) {
            throw new IllegalArgumentException("Extension protocol ID must be between 100 and 32767");
        }
        Objects.requireNonNull(factory, "factory");
        if (inbound.containsKey(protocolId)) throw new IllegalStateException("Duplicate N2N protocol ID " + protocolId);
        inbound.put(protocolId, () -> {
            Agent<?> agent = Objects.requireNonNull(factory.createAgent(), "Extension factory returned null");
            if (agent.getProtocolId() != protocolId) {
                throw new IllegalStateException("Extension factory returned protocol " + agent.getProtocolId()
                        + " for registered ID " + protocolId);
            }
            return agent;
        });
    }

    @Override
    public synchronized void decorateOutbound(UnaryOperator<PeerClientFactory> decorator) {
        requireOpen();
        if (!sharedOutbound) throw new UnsupportedOperationException("Shared Cardano peer sessions are unavailable");
        outbound.add(Objects.requireNonNull(decorator, "decorator"));
    }

    public synchronized void seal() { sealed = true; }

    public synchronized List<AgentFactory> inboundFactories() {
        requireSealed();
        return List.copyOf(inbound.values());
    }

    public synchronized PeerClientFactory outboundFactory(PeerClientFactory original) {
        requireSealed();
        PeerClientFactory result = Objects.requireNonNull(original, "original");
        for (UnaryOperator<PeerClientFactory> decorator : outbound) {
            result = Objects.requireNonNull(decorator.apply(result), "Extension returned null outbound factory");
        }
        return result;
    }

    private void requireOpen() {
        if (sealed) {
            throw new IllegalStateException("Register N2N extensions during initialization, before sessions start");
        }
    }
    private void requireSealed() {
        if (!sealed) throw new IllegalStateException("Seal N2N registration before constructing sessions");
    }
}
