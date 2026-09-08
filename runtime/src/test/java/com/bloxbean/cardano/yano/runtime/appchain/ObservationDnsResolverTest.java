package com.bloxbean.cardano.yano.runtime.appchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class ObservationDnsResolverTest {
    @Test
    void sharedWorkerDoesNotInheritCallerContext() throws Exception {
        InheritableThreadLocal<String> context = new InheritableThreadLocal<>();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        context.set("caller-generation");
        Thread.currentThread().setContextClassLoader(new ClassLoader(original) { });
        try (var resolver = new ObservationDnsResolver(1, 1, host -> {
            assertThat(context.get()).isNull();
            assertThat(Thread.currentThread().getContextClassLoader())
                    .isSameAs(ObservationDnsResolver.class.getClassLoader());
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        })) {
            assertThat(resolver.resolve("example.test", deadline(1000))).hasSize(1);
        } finally {
            context.remove();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void lookupFailureAndExpiredDeadlineFailClosed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var resolver = new ObservationDnsResolver(1, 1, host -> {
            calls.incrementAndGet();
            throw new UnknownHostException("fixture");
        })) {
            assertThatThrownBy(() -> resolver.resolve("example.test", System.nanoTime() - 1))
                    .isInstanceOf(IOException.class).hasMessageContaining("deadline");
            assertThat(calls).hasValue(0);
            assertThatThrownBy(() -> resolver.resolve("example.test", deadline(1000)))
                    .isInstanceOf(IOException.class).hasMessage("Observation DNS lookup failed")
                    .hasCauseInstanceOf(UnknownHostException.class);
            assertThat(calls).hasValue(1);
            assertThat(resolver.queuedLookups()).isZero();
        }
    }

    @Test
    void slowNativeLikeLookupCannotHoldCallerOrAccumulateCancelledWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var resolver = new ObservationDnsResolver(1, 2, host -> {
            calls.incrementAndGet();
            awaitIgnoringInterrupt(release);
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        })) {
            long started = System.nanoTime();
            for (int retry = 0; retry < 4; retry++) {
                assertThatThrownBy(() -> resolver.resolve("example.test", deadline(100)))
                        .isInstanceOf(IOException.class).hasMessageContaining("deadline");
                assertThat(resolver.queuedLookups()).isZero();
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void boundedCapacityRejectsExcessCallersAndReleaseRestoresProgress() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
             var resolver = new ObservationDnsResolver(1, 1, host -> {
                 entered.countDown();
                 awaitIgnoringInterrupt(release);
                 return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
             })) {
            try {
                var first = callers.submit(() -> resolver.resolve("first.test", deadline(5000)));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                var queued = callers.submit(() -> resolver.resolve("second.test", deadline(5000)));
                long waitUntil = deadline(1000);
                while (resolver.queuedLookups() == 0 && System.nanoTime() < waitUntil) Thread.sleep(1);
                assertThat(resolver.queuedLookups()).isEqualTo(1);
                assertThatThrownBy(() -> resolver.resolve("excess.test", deadline(1000)))
                        .isInstanceOf(IOException.class).hasMessageContaining("capacity");
                assertThat(resolver.queuedLookups()).isEqualTo(1);
                release.countDown();
                assertThat(first.get(1, TimeUnit.SECONDS)).hasSize(1);
                assertThat(queued.get(1, TimeUnit.SECONDS)).hasSize(1);
                assertThat(resolver.resolve("after.test", deadline(1000))).hasSize(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void callerInterruptionDoesNotWaitForNativeLikeResolution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var resolver = new ObservationDnsResolver(1, 1, host -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};
        })) {
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    resolver.resolve("example.test", deadline(5000));
                } catch (IOException expected) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(1000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(interrupted).isTrue();
            } finally {
                release.countDown();
                caller.interrupt();
                caller.join(1000);
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void resolutionDoesNotReuseAnUnvalidatedPriorAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var resolver = new ObservationDnsResolver(1, 1, host -> new InetAddress[]{
                InetAddress.getByAddress(calls.getAndIncrement() == 0
                        ? new byte[]{8, 8, 8, 8} : new byte[]{127, 0, 0, 1})})) {
            assertThat(RestrictedHttpsObservationProvider.validateResolvedAddresses(
                    resolver.resolve("changing.test", deadline(1000)))).hasSize(1);
            assertThatThrownBy(() -> RestrictedHttpsObservationProvider.validateResolvedAddresses(
                    resolver.resolve("changing.test", deadline(1000))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-public");
        }
    }

    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
