package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.helper.AppProtocolManager;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rides the app-layer protocols (100 app-message diffusion, 103 app-chain
 * catch-up) on the node's L1 peer sessions instead of dedicated connections
 * (ADR 005 M1 unification — one TCP connection per peer pair).
 * <p>
 * Wraps the {@link PeerClientFactory} the sync subsystem uses: whenever a new
 * L1 session is dialed to an endpoint that is also an app-group peer, the
 * created {@link PeerClient} is armed with the union transport config before
 * connect ({@code enableAppMsg} + {@code enableAppChainSync}); the handle is
 * (re)registered on every session (re)creation, so supervisor restarts renew
 * the agents transparently. {@link SharedAppPeerLink}s consult the CURRENT
 * handle on every operation — a stale session simply reads as down.
 * <p>
 * Catch-up routing: one 103 agent serves ALL hosted chains on a connection
 * and allows a single in-flight request, so requests carry an owner
 * (chain + handler) that the next {@code blocksReceived} is delivered to.
 */
class SharedAppTransport {

    /** How long a catch-up owner may sit unanswered before it is discarded. */
    private static final long CATCH_UP_STALE_MS = 60_000;

    private record Handle(PeerClient client, AppProtocolManager manager) {
        boolean isUp() {
            return client.isRunning() && manager.isAppLayerNegotiated();
        }
    }

    private static final class CatchUpOwner {
        final String chainId;
        final AppPeerClient.CatchUpHandler handler;
        final long since = System.currentTimeMillis();

        CatchUpOwner(String chainId, AppPeerClient.CatchUpHandler handler) {
            this.chainId = chainId;
            this.handler = handler;
        }
    }

    private final AppMsgSubmissionConfig unionConfig;
    private final Set<String> appPeerEndpoints;
    private final Logger log;

    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final Map<String, CatchUpOwner> catchUpOwners = new ConcurrentHashMap<>();

    SharedAppTransport(AppMsgSubmissionConfig unionConfig, Set<String> appPeerEndpoints, Logger log) {
        this.unionConfig = Objects.requireNonNull(unionConfig, "unionConfig");
        this.appPeerEndpoints = Set.copyOf(appPeerEndpoints);
        this.log = Objects.requireNonNull(log, "log");
    }

    static String key(String host, int port) {
        return host.trim().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * Decorate the sync subsystem's factory: L1 sessions to app-group peers
     * get the app-layer protocols armed pre-connect. Called once per session
     * (including supervisor restarts), which is exactly the re-registration
     * cadence the shared links need.
     */
    PeerClientFactory wrap(PeerClientFactory delegate) {
        return (endpoint, startPoint) -> {
            PeerClient client = delegate.create(endpoint, startPoint);
            String endpointKey = key(endpoint.host(), endpoint.port());
            if (appPeerEndpoints.contains(endpointKey)) {
                arm(endpointKey, client);
            }
            return client;
        };
    }

    private void arm(String endpointKey, PeerClient client) {
        try {
            client.enableAppMsg(unionConfig);
            client.enableAppChainSync();
            AppProtocolManager manager = client.getAppProtocolManager();
            AppChainSyncClientAgent syncAgent = manager.getAppChainSyncAgent();
            syncAgent.addListener(new AppChainSyncListener() {
                @Override
                public void blocksReceived(List<byte[]> blocks, long serverTipHeight) {
                    deliverCatchUp(endpointKey, blocks, serverTipHeight);
                }
            });
            handles.put(endpointKey, new Handle(client, manager));
            // A previous session's unanswered catch-up can never be answered now.
            catchUpOwners.remove(endpointKey);
            log.info("App-layer protocols (100/103) armed on the L1 peer session to {} "
                    + "(shared transport)", endpointKey);
        } catch (Exception e) {
            // Fail open: the L1 session must come up even if app arming fails —
            // the shared links then fall back to dedicated dials.
            log.warn("Failed to arm app-layer protocols on the L1 session to {}: {}",
                    endpointKey, e.toString());
        }
    }

    boolean isUp(String endpointKey) {
        Handle handle = handles.get(endpointKey);
        return handle != null && handle.isUp();
    }

    /** Enqueue for diffusion on the shared session; false when it is down. */
    boolean enqueue(String endpointKey, AppMessage message) {
        Handle handle = handles.get(endpointKey);
        if (handle == null || !handle.isUp()) {
            return false;
        }
        return handle.manager().getAppMsgSubmissionAgent().enqueueMessage(message);
    }

    /**
     * Request a finalized-block range over the shared session. One in-flight
     * request per connection across ALL chains (the 103 agent is
     * single-request); false when busy, down, or the send could not start.
     */
    boolean requestCatchUp(String endpointKey, String chainId, long fromHeight, long toHeight,
                           AppPeerClient.CatchUpHandler handler) {
        Handle handle = handles.get(endpointKey);
        if (handle == null || !handle.isUp() || handler == null) {
            return false;
        }
        CatchUpOwner existing = catchUpOwners.get(endpointKey);
        if (existing != null) {
            if (System.currentTimeMillis() - existing.since < CATCH_UP_STALE_MS) {
                return false; // busy (possibly another chain's request)
            }
            catchUpOwners.remove(endpointKey, existing); // stale — reclaim
        }
        CatchUpOwner owner = new CatchUpOwner(chainId, handler);
        if (catchUpOwners.putIfAbsent(endpointKey, owner) != null) {
            return false; // lost the race to another chain
        }
        boolean requested = handle.manager().getAppChainSyncAgent()
                .requestRange(chainId, fromHeight, toHeight);
        if (!requested) {
            catchUpOwners.remove(endpointKey, owner);
        }
        return requested;
    }

    private void deliverCatchUp(String endpointKey, List<byte[]> blocks, long serverTipHeight) {
        CatchUpOwner owner = catchUpOwners.remove(endpointKey);
        if (owner == null) {
            log.debug("Unowned catch-up reply from {} ({} block(s)) — dropped",
                    endpointKey, blocks.size());
            return;
        }
        try {
            owner.handler.onBlocks(endpointKey, blocks, serverTipHeight);
        } catch (Exception e) {
            log.warn("Catch-up handler failed for {} (chain {}): {}",
                    endpointKey, owner.chainId, e.toString());
        }
    }
}
