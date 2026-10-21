package org.yanoproject.runtime.chain;

import org.yanoproject.api.BlockBodyRetentionBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * The block-body retention boundary seen by {@link BlockPruner}: the minimum of every registered consumer's
 * requirement (app-layer ADR-038, D7). Registration, updates, removal and each prune batch synchronize on this
 * registry. So once a registration or an update returns, no later batch deletes a body at or above its value (I15).
 */
public final class BlockBodyRetentionRegistry implements BlockBodyRetentionBoundary {
    private final List<BlockBodyRetentionBoundary> boundaries = new ArrayList<>();

    /** Registers a consumer whose requirement starts at {@code initial}; empty means no requirement. */
    public synchronized Registration register(OptionalLong initial) {
        Registration registration = new Registration(Objects.requireNonNull(initial, "initial"));
        boundaries.add(registration);
        return registration;
    }

    /** Registers a consumer-supplied boundary; it is read under the registry lock and must not block. */
    public synchronized AutoCloseable register(BlockBodyRetentionBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        boundaries.add(boundary);
        return () -> remove(boundary);
    }

    @Override
    public synchronized OptionalLong oldestRequiredBlockNumber() {
        OptionalLong oldest = OptionalLong.empty();
        for (BlockBodyRetentionBoundary boundary : boundaries) {
            OptionalLong required = boundary.oldestRequiredBlockNumber();
            if (required.isPresent() && (oldest.isEmpty() || required.getAsLong() < oldest.getAsLong())) {
                oldest = required;
            }
        }
        return oldest;
    }

    private synchronized void remove(BlockBodyRetentionBoundary boundary) {
        boundaries.remove(boundary);
    }

    /** One consumer's requirement. Updates take the registry lock, so they wait for an in-flight prune batch. */
    public final class Registration implements BlockBodyRetentionBoundary, AutoCloseable {
        private OptionalLong value;

        private Registration(OptionalLong value) {
            this.value = value;
        }

        public void update(OptionalLong required) {
            Objects.requireNonNull(required, "required");
            synchronized (BlockBodyRetentionRegistry.this) {
                value = required;
            }
        }

        @Override
        public OptionalLong oldestRequiredBlockNumber() {
            synchronized (BlockBodyRetentionRegistry.this) {
                return value;
            }
        }

        @Override
        public void close() {
            remove(this);
        }
    }
}
