package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yano.api.config.UpstreamGovernorConfig;
import com.bloxbean.cardano.yano.runtime.connection.ConnectionState;
import com.bloxbean.cardano.yano.runtime.connection.RelayConnectionEvent;
import com.bloxbean.cardano.yano.runtime.connection.RelayConnectionListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Relay peer-governor policy surface. It owns bounded peer admission and
 * cold/warm/hot/backoff state, while sockets remain owned by the connection
 * manager and canonical sync remains owned by the sync supervisor.
 */
public final class PeerGovernor implements RelayConnectionListener {
    private static final long DEFAULT_BACKOFF_MILLIS = 60_000L;

    private final PeerStore peerStore;
    private final int targetKnown;
    private final int targetWarm;
    private final int targetHot;
    private final Object lock = new Object();
    private final Map<String, GovernedPeer> peers = new LinkedHashMap<>();
    private long lastReconcileAtMillis;

    public PeerGovernor(PeerStore peerStore) {
        this(peerStore, UpstreamGovernorConfig.builder().build());
    }

    public PeerGovernor(PeerStore peerStore, UpstreamGovernorConfig config) {
        this.peerStore = Objects.requireNonNull(peerStore, "peerStore");
        UpstreamGovernorConfig effective = config != null ? config : UpstreamGovernorConfig.builder().build();
        this.targetKnown = Math.max(1, effective.getTargetCold());
        this.targetWarm = Math.max(0, effective.getTargetWarm());
        this.targetHot = Math.max(1, effective.getTargetHot());
        for (PeerStoreEntry entry : peerStore.all()) {
            addOrUpdateInternal(PeerDescriptor.fromStore(entry), false);
        }
        reconcileInternal();
    }

    public PeerStoreEntry addOrUpdatePeer(PeerStoreEntry entry) {
        Objects.requireNonNull(entry, "entry");
        PeerDescriptor admitted = addOrUpdatePeer(PeerDescriptor.fromStore(entry));
        return admitted != null ? admitted.toStoreEntry() : null;
    }

    public PeerDescriptor addOrUpdatePeer(PeerDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        synchronized (lock) {
            PeerDescriptor admitted = addOrUpdateInternal(descriptor, true);
            reconcileInternal();
            persistInternal();
            return peers.containsKey(admitted.id()) ? admitted : null;
        }
    }

    public List<PeerStoreEntry> selectHotPeers(int targetHot) {
        if (targetHot <= 0) {
            return List.of();
        }
        synchronized (lock) {
            reconcileInternal();
            return orderedUsablePeers().stream()
                    .limit(targetHot)
                    .map(GovernedPeer::toStoreEntry)
                    .toList();
        }
    }

    public List<PeerDescriptor> hotPeers(PeerUse use, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            reconcileInternal();
            return orderedUsablePeers().stream()
                    .filter(peer -> peer.state == PeerState.HOT || peer.state == PeerState.WARM)
                    .limit(limit)
                    .map(GovernedPeer::descriptor)
                    .toList();
        }
    }

    public List<PeerStoreEntry> sharablePeers(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            reconcileInternal();
            return orderedUsablePeers().stream()
                    .filter(GovernedPeer::sharable)
                    .limit(limit)
                    .map(GovernedPeer::toStoreEntry)
                    .toList();
        }
    }

    public List<PeerStoreEntry> peerStoreEntries() {
        synchronized (lock) {
            return orderedPeersForPersistence().stream()
                    .map(GovernedPeer::toStoreEntry)
                    .toList();
        }
    }

    public PeerGovernorSnapshot snapshot() {
        synchronized (lock) {
            reconcileInternal();
            int cold = 0;
            int warm = 0;
            int hot = 0;
            int backoff = 0;
            int quarantined = 0;
            int sharable = 0;
            int inbound = 0;
            int gossip = 0;
            int ledger = 0;
            int bootstrap = 0;
            for (GovernedPeer peer : peers.values()) {
                switch (peer.state) {
                    case COLD -> cold++;
                    case WARM -> warm++;
                    case HOT -> hot++;
                    case BACKOFF -> backoff++;
                    case QUARANTINED -> quarantined++;
                }
                if (peer.sharable()) {
                    sharable++;
                }
                if (peer.source == PeerSource.INBOUND) {
                    inbound++;
                } else if (peer.source == PeerSource.GOSSIP) {
                    gossip++;
                } else if (peer.source == PeerSource.LEDGER) {
                    ledger++;
                } else if (peer.source == PeerSource.BOOTSTRAP) {
                    bootstrap++;
                }
            }
            return new PeerGovernorSnapshot(
                    peers.size(),
                    cold,
                    warm,
                    hot,
                    backoff,
                    quarantined,
                    sharable,
                    inbound,
                    gossip,
                    ledger,
                    bootstrap,
                    targetKnown,
                    targetWarm,
                    targetHot,
                    lastReconcileAtMillis,
                    orderedPeersForPersistence().stream()
                            .map(GovernedPeer::descriptor)
                            .toList());
        }
    }

    @Override
    public void onConnectionEvent(RelayConnectionEvent event) {
        if (event == null || event.key() == null) {
            return;
        }
        synchronized (lock) {
            GovernedPeer peer = peers.get(endpointId(event.key().host(), event.key().port()));
            if (peer == null) {
                PeerSource source = event.direction() != null && event.direction().name().equals("INBOUND")
                        ? PeerSource.INBOUND
                        : PeerSource.GOSSIP;
                peer = GovernedPeer.from(new PeerDescriptor(
                        endpointId(event.key().host(), event.key().port()),
                        event.key().host(),
                        event.key().port(),
                        source,
                        source.configValue(),
                        false,
                        false,
                        source != PeerSource.INBOUND,
                        1,
                        1,
                        event.timestampMillis(),
                        event.timestampMillis(),
                        null,
                        0));
                peers.put(peer.id, peer);
            }
            peer.lastSeenMillis = Math.max(peer.lastSeenMillis, event.timestampMillis());
            if (event.state() == ConnectionState.ESTABLISHED) {
                peer.state = PeerState.WARM;
                peer.backoffUntilMillis = 0L;
                peer.score += 25;
            } else if (event.state() == ConnectionState.FAILED) {
                peer.state = PeerState.BACKOFF;
                peer.backoffUntilMillis = System.currentTimeMillis() + DEFAULT_BACKOFF_MILLIS;
                peer.score -= 100;
            }
            reconcileInternal();
            persistInternal();
        }
    }

    private PeerDescriptor addOrUpdateInternal(PeerDescriptor descriptor, boolean enforceLimit) {
        String id = descriptor.id();
        GovernedPeer existing = peers.get(id);
        if (existing == null) {
            existing = GovernedPeer.from(descriptor);
            peers.put(id, existing);
        } else {
            existing.host = descriptor.host();
            existing.port = descriptor.port();
            existing.source = descriptor.source();
            existing.sourceId = descriptor.sourceId();
            existing.trustable = descriptor.trustable();
            existing.advertise = descriptor.advertise();
            existing.sharable = descriptor.sharable();
            existing.hotValency = descriptor.hotValency();
            existing.warmValency = descriptor.warmValency();
            existing.firstSeenMillis = Math.min(existing.firstSeenMillis, descriptor.firstSeenMillis());
            existing.lastSeenMillis = Math.max(existing.lastSeenMillis, descriptor.lastSeenMillis());
            existing.expiresAtMillis = descriptor.expiresAtMillis();
            existing.score = Math.max(existing.score, descriptor.score());
        }
        if (enforceLimit) {
            enforceKnownLimitInternal();
        }
        return existing.descriptor();
    }

    private void reconcileInternal() {
        long now = System.currentTimeMillis();
        lastReconcileAtMillis = now;
        for (GovernedPeer peer : peers.values()) {
            if (peer.state == PeerState.BACKOFF && peer.backoffUntilMillis > 0 && now >= peer.backoffUntilMillis) {
                peer.state = PeerState.COLD;
                peer.backoffUntilMillis = 0L;
            }
            if (peer.expiresAtMillis != null && peer.expiresAtMillis > 0
                    && now >= peer.expiresAtMillis
                    && peer.state != PeerState.HOT
                    && peer.source == PeerSource.GOSSIP) {
                peer.state = PeerState.QUARANTINED;
            }
        }

        int hot = 0;
        int warm = 0;
        for (GovernedPeer peer : orderedUsablePeers()) {
            if (hot < targetHot) {
                peer.state = PeerState.HOT;
                hot++;
            } else if (warm < targetWarm) {
                peer.state = PeerState.WARM;
                warm++;
            } else if (peer.state == PeerState.HOT || peer.state == PeerState.WARM) {
                peer.state = PeerState.COLD;
            }
        }
    }

    private void enforceKnownLimitInternal() {
        if (peers.size() <= targetKnown) {
            return;
        }
        List<GovernedPeer> ordered = orderedPeersForEviction();
        while (peers.size() > targetKnown && !ordered.isEmpty()) {
            GovernedPeer candidate = ordered.remove(0);
            if (candidate.preserved()) {
                continue;
            }
            peers.remove(candidate.id);
        }
    }

    private void persistInternal() {
        peerStore.replaceAll(orderedPeersForPersistence().stream()
                .map(GovernedPeer::toStoreEntry)
                .toList());
    }

    private List<GovernedPeer> orderedUsablePeers() {
        return peers.values().stream()
                .filter(peer -> peer.state != PeerState.BACKOFF && peer.state != PeerState.QUARANTINED)
                .sorted(Comparator
                        .comparing(GovernedPeer::trustable).reversed()
                        .thenComparing(Comparator.comparingInt((GovernedPeer peer) -> peer.score).reversed())
                        .thenComparing(GovernedPeer::sourceRank)
                        .thenComparing(peer -> peer.id))
                .toList();
    }

    private List<GovernedPeer> orderedPeersForPersistence() {
        return peers.values().stream()
                .sorted(Comparator.comparing(peer -> peer.id))
                .toList();
    }

    private List<GovernedPeer> orderedPeersForEviction() {
        return new ArrayList<>(peers.values().stream()
                .sorted(Comparator
                        .comparing(GovernedPeer::preserved)
                        .thenComparing(Comparator.comparingInt(peer -> peer.score))
                        .thenComparingLong(peer -> peer.lastSeenMillis))
                .toList());
    }

    private static String endpointId(String host, int port) {
        return host.trim().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    private static final class GovernedPeer {
        private final String id;
        private String host;
        private int port;
        private PeerSource source;
        private String sourceId;
        private boolean trustable;
        private boolean advertise;
        private boolean sharable;
        private int hotValency;
        private int warmValency;
        private long firstSeenMillis;
        private long lastSeenMillis;
        private Long expiresAtMillis;
        private int score;
        private PeerState state = PeerState.COLD;
        private long backoffUntilMillis;

        private GovernedPeer(String id) {
            this.id = id;
        }

        private static GovernedPeer from(PeerDescriptor descriptor) {
            GovernedPeer peer = new GovernedPeer(descriptor.id());
            peer.host = descriptor.host();
            peer.port = descriptor.port();
            peer.source = descriptor.source();
            peer.sourceId = descriptor.sourceId();
            peer.trustable = descriptor.trustable();
            peer.advertise = descriptor.advertise();
            peer.sharable = descriptor.sharable();
            peer.hotValency = descriptor.hotValency();
            peer.warmValency = descriptor.warmValency();
            peer.firstSeenMillis = descriptor.firstSeenMillis();
            peer.lastSeenMillis = descriptor.lastSeenMillis();
            peer.expiresAtMillis = descriptor.expiresAtMillis();
            peer.score = descriptor.score();
            return peer;
        }

        private boolean trustable() {
            return trustable;
        }

        private boolean sharable() {
            return sharable
                    && state != PeerState.BACKOFF
                    && state != PeerState.QUARANTINED
                    && source != PeerSource.INBOUND;
        }

        private boolean preserved() {
            return source == PeerSource.STATIC_UPSTREAM
                    || source == PeerSource.LOCAL_ROOT
                    || source == PeerSource.BOOTSTRAP;
        }

        private int sourceRank() {
            return switch (source) {
                case STATIC_UPSTREAM, LOCAL_ROOT -> 0;
                case BOOTSTRAP, PUBLIC_ROOT -> 1;
                case LEDGER -> 2;
                case GOSSIP -> 3;
                case INBOUND -> 4;
            };
        }

        private PeerDescriptor descriptor() {
            return new PeerDescriptor(
                    id,
                    host,
                    port,
                    source,
                    sourceId,
                    trustable,
                    advertise,
                    sharable,
                    hotValency,
                    warmValency,
                    firstSeenMillis,
                    lastSeenMillis,
                    expiresAtMillis,
                    score);
        }

        private PeerStoreEntry toStoreEntry() {
            return descriptor().toStoreEntry();
        }
    }
}
