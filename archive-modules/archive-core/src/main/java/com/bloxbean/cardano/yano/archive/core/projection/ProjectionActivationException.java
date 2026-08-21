package com.bloxbean.cardano.yano.archive.core.projection;

/**
 * Projection history cannot start with the state it was pointed at.
 *
 * <p>Every case this covers is a refusal to adopt data whose completeness cannot be
 * proven. It is deliberately not recoverable at runtime: the operator chooses a fresh
 * sync, an older release, or a separately designed offline migration (ADR-039 §1).
 */
public class ProjectionActivationException extends RuntimeException {
    public ProjectionActivationException(String message) {
        super(message);
    }
}
