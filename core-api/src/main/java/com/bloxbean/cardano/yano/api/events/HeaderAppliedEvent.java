package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Event published after a header is durably stored.
 *
 * <p>This event is for observability only. Body-fetch scheduling uses the
 * runtime-local header signal path and does not depend on this event being
 * delivered. It is best-effort and may be dropped under sustained load or
 * during shutdown; correctness-critical logic should use ordered body-apply
 * events such as {@link BlockAppliedEvent} instead.</p>
 */
public record HeaderAppliedEvent(long slot, long blockNumber, String blockHash) implements Event {
}
