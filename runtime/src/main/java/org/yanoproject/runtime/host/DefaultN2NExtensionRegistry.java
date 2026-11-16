package org.yanoproject.runtime.host;

import com.bloxbean.cardano.yaci.core.common.NetworkType;
import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.p2p.peer.PeerClientFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One assembly's pre-connect extension plan. No agents, sockets or workers are created during registration. */
public final class DefaultN2NExtensionRegistry implements N2NExtensionRegistry {
    // Public Cardano networks plus the existing Yano devnet magic (YanoConfig.devnetDefault).
    private static final Set<Long> CARDANO_NETWORK_MAGICS = Stream.concat(
            Stream.of(NetworkType.values()).map(NetworkType::getProtocolMagic),
            Stream.of(42L)).collect(Collectors.toUnmodifiableSet());
    private final Map<Integer, AgentFactory> inbound = new LinkedHashMap<>();
    private final List<UnaryOperator<PeerClientFactory>> outbound = new ArrayList<>();
    private final boolean sharedOutbound;
    private Long appOnlyNetwork;
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

    @Override
    public synchronized void requireAppOnlyNetwork(long protocolMagic) {
        requireOpen();
        if (sharedOutbound) throw new UnsupportedOperationException("This host serves Cardano N2N sessions");
        if (protocolMagic <= 0 || protocolMagic > 0xffff_ffffL) {
            throw new IllegalArgumentException("App-only network magic must be an unsigned 32-bit value");
        }
        if (CARDANO_NETWORK_MAGICS.contains(protocolMagic)) {
            throw new IllegalArgumentException("App-only network magic " + protocolMagic
                    + " is a Cardano network magic; use a distinct app network identity");
        }
        if (appOnlyNetwork != null && appOnlyNetwork != protocolMagic) {
            throw new IllegalStateException("Plugins supplied conflicting app-only network magics "
                    + appOnlyNetwork + " and " + protocolMagic);
        }
        appOnlyNetwork = protocolMagic;
    }

    /** The app-only network magic a plugin supplied, if any. */
    public synchronized OptionalLong appOnlyNetwork() {
        return appOnlyNetwork == null ? OptionalLong.empty() : OptionalLong.of(appOnlyNetwork);
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
