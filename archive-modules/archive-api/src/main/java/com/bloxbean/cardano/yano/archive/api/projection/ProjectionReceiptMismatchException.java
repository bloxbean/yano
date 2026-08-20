package com.bloxbean.cardano.yano.archive.api.projection;

/**
 * A durable receipt exists for this range but describes a different job.
 *
 * <p>This is never retried into success. It means two differently shaped projections
 * claim the same canonical range, which the outbox cannot resolve on its own.
 */
public class ProjectionReceiptMismatchException extends ProjectionSinkException {
    public ProjectionReceiptMismatchException(String message) {
        super(message);
    }
}
