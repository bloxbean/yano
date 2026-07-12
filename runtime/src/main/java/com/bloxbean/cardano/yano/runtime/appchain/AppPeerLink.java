package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

/**
 * Outbound link to one app-group peer: message diffusion (protocol 100) and
 * finalized-block catch-up (protocol 103). Two transports implement it:
 * <ul>
 *   <li>{@link AppPeerClient} — a dedicated connection owned by the
 *       app-chain subsystem (the original M1 transport; full bandwidth
 *       isolation from L1 sync);</li>
 *   <li>{@link SharedAppPeerLink} — rides the node's L1 peer session
 *       (single TCP connection per peer pair, ADR 005 M1 unification),
 *       falling back to a dedicated dial when the L1 session is down.</li>
 * </ul>
 * Inbound app traffic is unaffected by the choice: it always arrives on the
 * peer's own connection to this node's N2N server, which multiplexes L1 and
 * app protocols already.
 */
interface AppPeerLink {

    /** Stable display id (host:port). */
    String peerId();

    boolean isConnected();

    /** Queue a message for diffusion; must never block on a dead peer. */
    void enqueue(AppMessage message);

    /** Request finalized app blocks [from..to]; false when busy or disconnected. */
    boolean requestCatchUp(String chainId, long fromHeight, long toHeight);

    /** Periodic: (re)establish transport as needed; must not block the caller. */
    void ensureConnectedAsync();

    /** Periodic keep-alive / maintenance hook. */
    void keepAliveTick();

    void shutdown();

    /** Transport in use right now — surfaced in status ("shared", "dedicated", ...). */
    String transport();
}
