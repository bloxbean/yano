package com.bloxbean.cardano.yano.archive.ducklake;

import java.sql.SQLException;

/**
 * A bounded request-facing acquisition gave up waiting for DuckDB capacity.
 *
 * <p>This is ordinary saturation, not a backend fault: every permit was simply
 * in use for the caller's short acquisition timeout. It must not degrade archive
 * health, or a burst of concurrent API reads would leave the archive reported
 * unhealthy long after capacity freed up.
 *
 * <p>It is deliberately distinct from an {@link
 * com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException} raised on
 * a worker path, which means a wait exceeded the operator's stuck threshold and
 * is a genuine operational problem.
 */
public final class DuckDbCapacityTimeoutException extends SQLException {
    public DuckDbCapacityTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
