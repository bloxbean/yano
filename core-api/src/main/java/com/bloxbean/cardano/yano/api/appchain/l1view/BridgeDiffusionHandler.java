package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

/**
 * ADR-UTXO-009 SP-M6: receiver for {@code ~bridge/*} diffusion-only app
 * messages — node-to-node coordination traffic (settlement co-signing) that
 * is relayed between members but never pooled or sequenced into blocks,
 * mirroring the {@code ~anchor/*} co-sign channel. Extensions register a
 * handler with the app-chain subsystem; the subsystem invokes it on first
 * sighting of each {@code ~bridge/*} envelope.
 *
 * <p>Handlers must be non-blocking and tolerate duplicate or unordered
 * delivery; envelope signatures are verified by the subsystem before
 * delivery, and {@code message.getSender()} carries the member identity.
 */
public interface BridgeDiffusionHandler {

    /** Reserved diffusion prefix for bridge coordination traffic. */
    String TOPIC_PREFIX = "~bridge/";

    void onBridgeMessage(AppMessage message);
}
