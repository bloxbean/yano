package org.yanoproject.api.host;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import org.yanoproject.p2p.peer.PeerClientFactory;

import java.util.function.UnaryOperator;

/**
 * Session extension registration during plugin initialization and host assembly, before
 * any listener or dial starts. The host then seals registration. Factories create fresh agents per session;
 * implementations of mini-protocols remain in Yaci. Registration persists across stop/start.
 */
public interface N2NExtensionRegistry {
    /** Whether this host composes shared Cardano outbound peer sessions. Dedicated app peers remain Yaci-owned. */
    boolean hasSharedOutboundSessions();

    /** Register one inbound extension ID (100..32767). Duplicate IDs and late registration fail. */
    void registerInbound(int protocolId, AgentFactory factory);

    /**
     * Decorate the existing outbound factory before clients connect. The decorator can use
     * Yaci's pre-connect extension API and attach its existing connection lifecycle listeners.
     * This preserves the shared-session path without creating another peer runtime.
     */
    void decorateOutbound(UnaryOperator<PeerClientFactory> decorator);
}
