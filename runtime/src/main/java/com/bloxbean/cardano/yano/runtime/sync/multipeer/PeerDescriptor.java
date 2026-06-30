package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yano.runtime.connection.ConnectionKey;

import java.util.Objects;

/**
 * Incoming peer descriptor shared by discovery and peer-governor admission.
 */
public record PeerDescriptor(
        String id,
        String host,
        int port,
        PeerSource source,
        String sourceId,
        boolean trustable,
        boolean advertise,
        boolean sharable,
        int hotValency,
        int warmValency,
        long firstSeenMillis,
        long lastSeenMillis,
        Long expiresAtMillis,
        int score) {

    public PeerDescriptor {
        host = normalizeText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        id = endpointId(host, port);
        source = source != null ? source : PeerSource.GOSSIP;
        sourceId = sourceId != null ? sourceId : source.configValue();
        firstSeenMillis = firstSeenMillis > 0 ? firstSeenMillis : System.currentTimeMillis();
        lastSeenMillis = Math.max(lastSeenMillis, firstSeenMillis);
        hotValency = Math.max(0, hotValency);
        warmValency = Math.max(0, warmValency);
    }

    public static PeerDescriptor fromStore(PeerStoreEntry entry) {
        Objects.requireNonNull(entry, "entry");
        long now = System.currentTimeMillis();
        PeerSource source = PeerSource.from(entry.source());
        return new PeerDescriptor(
                entry.id(),
                entry.host(),
                entry.port(),
                source,
                source.configValue(),
                entry.trusted(),
                source == PeerSource.LOCAL_ROOT || source == PeerSource.STATIC_UPSTREAM,
                source != PeerSource.INBOUND,
                1,
                1,
                now,
                now,
                null,
                scoreForSource(source, entry.trusted()));
    }

    public PeerStoreEntry toStoreEntry() {
        return new PeerStoreEntry(id, host, port, source.configValue(), trustable);
    }

    public static String endpointId(String host, int port) {
        return ConnectionKey.of(host, port).displayName();
    }

    static int scoreForSource(PeerSource source, boolean trusted) {
        int base = switch (source != null ? source : PeerSource.GOSSIP) {
            case STATIC_UPSTREAM, LOCAL_ROOT -> 20_000;
            case BOOTSTRAP, PUBLIC_ROOT -> 10_000;
            case LEDGER -> 8_000;
            case GOSSIP -> 1_000;
            case INBOUND -> 0;
        };
        return trusted ? base + 10_000 : base;
    }

    private static String normalizeText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
