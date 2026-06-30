package com.bloxbean.cardano.yano.runtime.connection;

import com.bloxbean.cardano.yaci.core.network.server.ServerConnectionDecision;
import com.bloxbean.cardano.yaci.core.network.server.ServerConnectionListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.runtime.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.runtime.peer.PeerFailureMessage;
import io.netty.channel.Channel;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultRelayConnectionManager implements RelayConnectionManager {
    public static final int DEFAULT_MAX_INBOUND_CONNECTIONS = 100;
    public static final int DEFAULT_MAX_CONNECTIONS_PER_IP = 5;

    private final int maxInboundConnections;
    private final int maxConnectionsPerIp;
    private final Logger log;
    private final Object lock = new Object();
    private final Map<String, ConnectionRecord> connections = new HashMap<>();
    private final Map<ConnectionKey, String> outboundByKey = new HashMap<>();
    private final CopyOnWriteArrayList<RelayConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong rejectedInboundConnections = new AtomicLong();
    private final AtomicLong failedOutboundConnections = new AtomicLong();

    public DefaultRelayConnectionManager(int maxInboundConnections, int maxConnectionsPerIp, Logger log) {
        this.maxInboundConnections = Math.max(1, maxInboundConnections);
        this.maxConnectionsPerIp = Math.max(1, maxConnectionsPerIp);
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public ServerConnectionListener yaciServerConnectionListener() {
        return new ServerConnectionListener() {
            @Override
            public ServerConnectionDecision onAccept(Channel channel) {
                return ConnectionKey.from(channel.remoteAddress())
                        .map(key -> reserveInbound(channelId(channel), key))
                        .orElseGet(() -> {
                            rejectedInboundConnections.incrementAndGet();
                            return ServerConnectionDecision.reject("unsupported remote address");
                        });
            }

            @Override
            public void onHandshakeComplete(Channel channel, AcceptVersion acceptedVersion) {
                markInboundEstablished(channelId(channel), acceptedVersion);
            }

            @Override
            public void onHandshakeFailed(Channel channel, Throwable cause) {
                markInboundFailed(channelId(channel), cause);
            }

            @Override
            public void onClosed(Channel channel) {
                markClosed(channelId(channel), "closed");
            }
        };
    }

    @Override
    public PeerClientFactory wrapPeerClientFactory(PeerClientFactory delegate) {
        return new ConnectionTrackingPeerClientFactory(delegate, this);
    }

    @Override
    public RelayConnectionSnapshot snapshot() {
        synchronized (lock) {
            int inbound = 0;
            int outbound = 0;
            int established = 0;
            int connecting = 0;
            List<RelayConnectionInfo> connectionInfos = connections.values().stream()
                    .filter(connection -> !connection.terminal())
                    .map(ConnectionRecord::info)
                    .toList();
            for (ConnectionRecord connection : connections.values()) {
                if (connection.terminal()) {
                    continue;
                }
                if (connection.direction == ConnectionDirection.INBOUND) {
                    inbound++;
                } else {
                    outbound++;
                }
                if (connection.state == ConnectionState.ESTABLISHED) {
                    established++;
                } else if (connection.state == ConnectionState.CONNECTING
                        || connection.state == ConnectionState.HANDSHAKING) {
                    connecting++;
                }
            }
            return new RelayConnectionSnapshot(
                    inbound,
                    outbound,
                    established,
                    connecting,
                    rejectedInboundConnections.get(),
                    failedOutboundConnections.get(),
                    maxConnectionsPerIp,
                    connectionInfos);
        }
    }

    @Override
    public void addListener(RelayConnectionListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeListener(RelayConnectionListener listener) {
        listeners.remove(listener);
    }

    String reserveOutbound(PeerEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        ConnectionKey key = ConnectionKey.of(endpoint.host(), endpoint.port());
        RelayConnectionEvent event;
        String id;
        synchronized (lock) {
            String existing = outboundByKey.get(key);
            if (existing != null && isActive(existing)) {
                throw new IllegalStateException("outbound connection already active: " + key.displayName());
            }
            id = "out-" + sequence.incrementAndGet();
            ConnectionRecord record = new ConnectionRecord(
                    id,
                    ConnectionDirection.OUTBOUND,
                    key,
                    ConnectionState.CONNECTING,
                    ProtocolCapabilities.unknown(),
                    null,
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
            connections.put(id, record);
            outboundByKey.put(key, id);
            event = record.event();
        }
        publish(event);
        return id;
    }

    void markOutboundEstablished(String id, AcceptVersion acceptedVersion) {
        markEstablished(id, acceptedVersion);
    }

    void markOutboundFailed(String id, Throwable cause) {
        failedOutboundConnections.incrementAndGet();
        markFailed(id, cause);
    }

    void markClosed(String id, String reason) {
        RelayConnectionEvent event = null;
        synchronized (lock) {
            ConnectionRecord record = connections.remove(id);
            if (record != null) {
                if (record.direction == ConnectionDirection.OUTBOUND) {
                    outboundByKey.remove(record.key);
                }
                record.state = ConnectionState.CLOSED;
                record.reason = reason;
                record.updatedAtMillis = System.currentTimeMillis();
                event = record.event();
            }
        }
        publish(event);
    }

    ServerConnectionDecision reserveInbound(String id, ConnectionKey key) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        RelayConnectionEvent event;
        synchronized (lock) {
            int inbound = 0;
            int perIp = 0;
            for (ConnectionRecord connection : connections.values()) {
                if (connection.terminal() || connection.direction != ConnectionDirection.INBOUND) {
                    continue;
                }
                inbound++;
                if (connection.key.ipKey().equals(key.ipKey())) {
                    perIp++;
                }
            }
            if (inbound >= maxInboundConnections) {
                rejectedInboundConnections.incrementAndGet();
                return ServerConnectionDecision.reject("max inbound connections reached");
            }
            if (perIp >= maxConnectionsPerIp) {
                rejectedInboundConnections.incrementAndGet();
                return ServerConnectionDecision.reject("max inbound connections per IP reached");
            }
            ConnectionRecord record = new ConnectionRecord(
                    id,
                    ConnectionDirection.INBOUND,
                    key,
                    ConnectionState.HANDSHAKING,
                    ProtocolCapabilities.unknown(),
                    null,
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
            connections.put(id, record);
            event = record.event();
        }
        publish(event);
        return ServerConnectionDecision.accept();
    }

    void markInboundEstablished(String id, AcceptVersion acceptedVersion) {
        markEstablished(id, acceptedVersion);
    }

    void markInboundFailed(String id, Throwable cause) {
        markFailed(id, cause);
    }

    private void markEstablished(String id, AcceptVersion acceptedVersion) {
        RelayConnectionEvent event = null;
        synchronized (lock) {
            ConnectionRecord record = connections.get(id);
            if (record != null && !record.terminal()) {
                record.state = ConnectionState.ESTABLISHED;
                record.capabilities = ProtocolCapabilities.from(acceptedVersion);
                record.reason = null;
                record.updatedAtMillis = System.currentTimeMillis();
                event = record.event();
            }
        }
        publish(event);
    }

    private void markFailed(String id, Throwable cause) {
        RelayConnectionEvent event = null;
        synchronized (lock) {
            ConnectionRecord record = connections.remove(id);
            if (record != null) {
                if (record.direction == ConnectionDirection.OUTBOUND) {
                    outboundByKey.remove(record.key);
                }
                record.state = ConnectionState.FAILED;
                record.reason = cause != null ? PeerFailureMessage.summarize(cause) : "failed";
                record.updatedAtMillis = System.currentTimeMillis();
                event = record.event();
            }
        }
        publish(event);
    }

    private boolean isActive(String id) {
        ConnectionRecord connection = connections.get(id);
        return connection != null && !connection.terminal();
    }

    private void publish(RelayConnectionEvent event) {
        if (event == null) {
            return;
        }
        for (RelayConnectionListener listener : listeners) {
            try {
                listener.onConnectionEvent(event);
            } catch (Exception e) {
                log.debug("Relay connection listener failed", e);
            }
        }
    }

    private String channelId(Channel channel) {
        return "in-" + channel.id().asLongText();
    }

    private static final class ConnectionRecord {
        private final String id;
        private final ConnectionDirection direction;
        private final ConnectionKey key;
        private final long createdAtMillis;
        private ConnectionState state;
        private ProtocolCapabilities capabilities;
        private String reason;
        private long updatedAtMillis;

        private ConnectionRecord(String id,
                                 ConnectionDirection direction,
                                 ConnectionKey key,
                                 ConnectionState state,
                                 ProtocolCapabilities capabilities,
                                 String reason,
                                 long createdAtMillis,
                                 long updatedAtMillis) {
            this.id = id;
            this.direction = direction;
            this.key = key;
            this.state = state;
            this.capabilities = capabilities;
            this.reason = reason;
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
        }

        private boolean terminal() {
            return state == ConnectionState.FAILED || state == ConnectionState.CLOSED;
        }

        private RelayConnectionEvent event() {
            return new RelayConnectionEvent(
                    id,
                    direction,
                    state,
                    key,
                    capabilities,
                    reason,
                    System.currentTimeMillis());
        }

        private RelayConnectionInfo info() {
            return new RelayConnectionInfo(
                    id,
                    direction,
                    state,
                    key,
                    capabilities,
                    reason,
                    createdAtMillis,
                    updatedAtMillis);
        }
    }
}
