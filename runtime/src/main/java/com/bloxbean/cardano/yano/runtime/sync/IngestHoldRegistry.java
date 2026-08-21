package com.bloxbean.cardano.yano.runtime.sync;

import java.util.function.BooleanSupplier;

/**
 * Remembers an external hold on canonical ingestion and applies it to whichever header sync
 * manager is current (ADR-039 disk backpressure).
 *
 * <p>Exists because the obvious implementation is wrong in two ways that both fail silently:
 *
 * <ul>
 *   <li>the hold is registered during archive initialisation, which runs <em>before</em> the
 *       node starts and therefore before any manager exists — a direct install simply does
 *       nothing;</li>
 *   <li>the manager belongs to a peer session that is <em>recreated on every reconnect</em>,
 *       so even a correctly ordered install is lost the first time upstream drops — precisely
 *       when a backlog is most likely to have grown.</li>
 * </ul>
 *
 * <p>Registration is therefore decoupled from application: the hold is stored whenever it
 * arrives, and applied whenever a manager appears.
 */
final class IngestHoldRegistry {

    private volatile BooleanSupplier hold;
    private volatile String reason;

    /**
     * Whatever can accept a hold. Narrow on purpose: the registry has no reason to know about
     * the sync manager's other 40-odd methods, and depending on the concrete type would make
     * this logic untestable without the whole peer-session machinery.
     */
    @FunctionalInterface
    interface HoldTarget {
        void setIngestHold(BooleanSupplier hold, String reason);
    }

    /** Store a hold. Safe before the node starts; applied when a target appears. */
    void register(BooleanSupplier hold, String reason) {
        this.hold = hold;
        this.reason = reason;
    }

    /** Apply the stored hold, if any. Called on registration and again per peer session. */
    void applyTo(HoldTarget target) {
        BooleanSupplier current = hold;
        if (target != null && current != null) target.setIngestHold(current, reason);
    }

    boolean isRegistered() {
        return hold != null;
    }

    String reason() {
        return reason;
    }
}
