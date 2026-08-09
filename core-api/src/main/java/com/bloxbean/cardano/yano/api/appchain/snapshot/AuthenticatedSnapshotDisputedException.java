package com.bloxbean.cardano.yano.api.appchain.snapshot;

/** Durable fail-closed state after L1 history moved below a finalized snapshot boundary. */
public final class AuthenticatedSnapshotDisputedException extends IllegalStateException {
    public AuthenticatedSnapshotDisputedException(String message) {
        super(message);
    }
}
