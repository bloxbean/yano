package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * App-peer link that rides the node's L1 peer session ({@link
 * SharedAppTransport}) and falls back to a dedicated {@link AppPeerClient}
 * dial when the shared session stays down beyond a grace period.
 * <p>
 * States (evaluated on the subsystem's periodic ticks — no extra threads):
 * <ul>
 *   <li><b>shared</b> — the L1 session to this peer is up and app-layer
 *       negotiated; messages go to the shared agents.</li>
 *   <li><b>grace</b> — shared just went down; messages queue locally
 *       (bounded) while we wait for the supervisor to bring it back.</li>
 *   <li><b>fallback</b> — shared stayed down past the grace period (or the
 *       upstream moved to a different peer); a dedicated {@link
 *       AppPeerClient} is dialed and carries the traffic until the shared
 *       session returns, at which point the fallback is shut down.</li>
 * </ul>
 * Messages buffered in a just-abandoned transport follow the same
 * best-effort gossip semantics as a dedicated reconnect: the partial-round
 * re-gossip and catch-up paths recover anything that mattered.
 */
final class SharedAppPeerLink implements AppPeerLink {

    /** Shared-session outage before a dedicated fallback connection is dialed. */
    static final long DEFAULT_FALLBACK_GRACE_MS = 15_000;
    private static final int REPLAY_QUEUE_LIMIT = 200;

    private final SharedAppTransport transport;
    private final String endpointKey;
    private final String peerId;
    private final Supplier<? extends AppPeerLink> fallbackFactory;
    private final long fallbackGraceMs;
    private final Logger log;

    /** Messages accepted while neither transport is available (grace window). */
    private final Deque<AppMessage> replayQueue = new ArrayDeque<>();

    private volatile long sharedDownSince;
    private volatile AppPeerLink fallback;
    private volatile boolean shutdown;

    SharedAppPeerLink(SharedAppTransport transport,
                      String endpointKey,
                      String peerId,
                      Supplier<? extends AppPeerLink> fallbackFactory,
                      Logger log) {
        this(transport, endpointKey, peerId, fallbackFactory, DEFAULT_FALLBACK_GRACE_MS, log);
    }

    /** Test seam: grace period injectable. */
    SharedAppPeerLink(SharedAppTransport transport,
                      String endpointKey,
                      String peerId,
                      Supplier<? extends AppPeerLink> fallbackFactory,
                      long fallbackGraceMs,
                      Logger log) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpointKey = Objects.requireNonNull(endpointKey, "endpointKey");
        this.peerId = Objects.requireNonNull(peerId, "peerId");
        this.fallbackFactory = Objects.requireNonNull(fallbackFactory, "fallbackFactory");
        this.fallbackGraceMs = fallbackGraceMs;
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public String peerId() {
        return peerId;
    }

    @Override
    public boolean isConnected() {
        if (transport.isUp(endpointKey)) {
            return true;
        }
        AppPeerLink current = fallback;
        return current != null && current.isConnected();
    }

    @Override
    public void enqueue(AppMessage message) {
        if (shutdown) {
            return;
        }
        if (transport.isUp(endpointKey)) {
            flushReplayToShared();
            if (transport.enqueue(endpointKey, message)) {
                return;
            }
            // Session died between the check and the enqueue — fall through.
        }
        AppPeerLink current = fallback;
        if (current != null) {
            current.enqueue(message);
            return;
        }
        synchronized (replayQueue) {
            if (replayQueue.size() >= REPLAY_QUEUE_LIMIT) {
                replayQueue.pollFirst();
            }
            replayQueue.addLast(message);
        }
    }

    @Override
    public boolean requestCatchUp(String chainId, long fromHeight, long toHeight) {
        if (shutdown) {
            return false;
        }
        if (transport.isUp(endpointKey)) {
            return transport.requestCatchUp(endpointKey, chainId, fromHeight, toHeight,
                    catchUpHandler());
        }
        AppPeerLink current = fallback;
        return current != null && current.requestCatchUp(chainId, fromHeight, toHeight);
    }

    /** The catch-up handler is owned by the subsystem; injected at creation. */
    private volatile AppPeerClient.CatchUpHandler catchUpHandler;

    void wireCatchUpHandler(AppPeerClient.CatchUpHandler handler) {
        this.catchUpHandler = handler;
    }

    private AppPeerClient.CatchUpHandler catchUpHandler() {
        return catchUpHandler;
    }

    /**
     * Periodic state evaluation (subsystem connect tick): flush the grace
     * queue when shared is back, engage/retire the dedicated fallback.
     */
    @Override
    public void ensureConnectedAsync() {
        if (shutdown) {
            return;
        }
        if (transport.isUp(endpointKey)) {
            sharedDownSince = 0;
            retireFallback();
            flushReplayToShared();
            return;
        }
        long now = System.currentTimeMillis();
        if (sharedDownSince == 0) {
            sharedDownSince = now;
            return; // grace window starts
        }
        if (fallback == null && now - sharedDownSince >= fallbackGraceMs) {
            AppPeerLink created = fallbackFactory.get();
            fallback = created;
            log.info("App peer {}: shared L1 session down for {}s — dialing a dedicated "
                    + "fallback connection", peerId, (now - sharedDownSince) / 1000);
            drainReplayTo(created);
        }
        AppPeerLink current = fallback;
        if (current != null) {
            current.ensureConnectedAsync();
        }
    }

    @Override
    public void keepAliveTick() {
        AppPeerLink current = fallback;
        if (current != null) {
            current.keepAliveTick();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        retireFallback();
        synchronized (replayQueue) {
            replayQueue.clear();
        }
    }

    @Override
    public String transport() {
        if (transport.isUp(endpointKey)) {
            return "shared";
        }
        return fallback != null ? "dedicated-fallback" : "shared-down";
    }

    private void retireFallback() {
        AppPeerLink current = fallback;
        if (current != null) {
            fallback = null;
            log.info("App peer {}: shared L1 session restored — retiring the dedicated "
                    + "fallback connection", peerId);
            try {
                current.shutdown();
            } catch (Exception e) {
                log.debug("Fallback shutdown for {} failed: {}", peerId, e.toString());
            }
        }
    }

    private void flushReplayToShared() {
        while (true) {
            AppMessage queued;
            synchronized (replayQueue) {
                queued = replayQueue.pollFirst();
            }
            if (queued == null) {
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            if (queued.isExpired(now)) {
                continue;
            }
            if (!transport.enqueue(endpointKey, queued)) {
                synchronized (replayQueue) {
                    replayQueue.addFirst(queued);
                }
                return; // shared went down again — keep the rest queued
            }
        }
    }

    private void drainReplayTo(AppPeerLink target) {
        while (true) {
            AppMessage queued;
            synchronized (replayQueue) {
                queued = replayQueue.pollFirst();
            }
            if (queued == null) {
                return;
            }
            target.enqueue(queued);
        }
    }
}
