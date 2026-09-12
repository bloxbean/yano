package org.yanoproject.api;

import java.util.OptionalLong;

/** Read-only low watermark supplied by optional block-body consumers. */
@FunctionalInterface
public interface BlockBodyRetentionBoundary {
    BlockBodyRetentionBoundary NONE = OptionalLong::empty;

    /** Oldest block number whose body must remain available. */
    OptionalLong oldestRequiredBlockNumber();
}
