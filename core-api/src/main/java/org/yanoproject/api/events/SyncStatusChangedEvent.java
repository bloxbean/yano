package org.yanoproject.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import org.yanoproject.api.SyncPhase;

public final class SyncStatusChangedEvent implements Event {
    private final SyncPhase previous;
    private final SyncPhase current;

    public SyncStatusChangedEvent(SyncPhase previous, SyncPhase current) {
        this.previous = previous;
        this.current = current;
    }

    public SyncPhase previous() { return previous; }
    public SyncPhase current() { return current; }
}
