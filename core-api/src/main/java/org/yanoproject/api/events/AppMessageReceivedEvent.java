package org.yanoproject.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import org.yanoproject.api.appchain.ReceivedAppMessage;

/**
 * Event published when an app-chain message has been verified (envelope
 * integrity, signature, membership) and accepted by this node — from a local
 * submission or from peer diffusion.
 */
public final class AppMessageReceivedEvent implements Event {
    private final ReceivedAppMessage message;

    public AppMessageReceivedEvent(ReceivedAppMessage message) {
        this.message = message;
    }

    public ReceivedAppMessage message() {
        return message;
    }
}
