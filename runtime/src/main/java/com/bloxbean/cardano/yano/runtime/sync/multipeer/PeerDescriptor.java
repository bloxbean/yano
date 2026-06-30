package com.bloxbean.cardano.yano.runtime.sync.multipeer;

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
        id = normalizeText(id, "id");
        host = normalizeText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
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
                entry.score());
    }

    public PeerStoreEntry toStoreEntry() {
        return new PeerStoreEntry(id, host, port, source.configValue(), trustable, score);
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
