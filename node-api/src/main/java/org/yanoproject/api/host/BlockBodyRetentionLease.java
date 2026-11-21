package org.yanoproject.api.host;

import org.yanoproject.api.BlockBodyRetentionBoundary;
import java.util.OptionalLong;

/** A consumer's existing block-body retention registration, without a node storage implementation type. */
public interface BlockBodyRetentionLease extends BlockBodyRetentionBoundary, AutoCloseable {
    void update(OptionalLong required);
    @Override void close();
}
