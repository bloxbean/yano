package org.yanoproject.runtime.chain;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.host.BlockBodyRetentionLease;

import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockBodyRetentionRegistryTest {
    @Test
    void hostLeasePreservesOtherConsumersAndCannotReregisterAfterClose() {
        var registry = new BlockBodyRetentionRegistry();
        BlockBodyRetentionLease first = registry.register(OptionalLong.of(10));
        BlockBodyRetentionLease second = registry.register(OptionalLong.of(20));
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(10);
        first.update(OptionalLong.of(30));
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(20);
        second.close();
        second.close();
        second.update(OptionalLong.of(1));
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(30);
        first.close();
        assertThat(registry.oldestRequiredBlockNumber()).isEmpty();
    }

    @Test
    void leaseUpdateWaitsForThePruneBatchLock() throws Exception {
        var registry = new BlockBodyRetentionRegistry();
        try (BlockBodyRetentionLease lease = registry.register(OptionalLong.of(100));
             var worker = Executors.newSingleThreadExecutor()) {
            var attempted = new CountDownLatch(1);
            Future<?> update;
            synchronized (registry) {
                update = worker.submit(() -> {
                    attempted.countDown();
                    lease.update(OptionalLong.of(50));
                });
                assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> update.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(registry.oldestRequiredBlockNumber()).hasValue(100);
            }
            update.get(2, TimeUnit.SECONDS);
            assertThat(registry.oldestRequiredBlockNumber()).hasValue(50);
        }
    }
}
