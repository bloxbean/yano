package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoadWorkflowGatesTest {
    @Test
    void serializesOneLaneWhileAllowingTheOtherLaneToOverlap() throws Exception {
        LoadWorkflowGates gates = new LoadWorkflowGates();
        CountDownLatch releaseEntered = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        CountDownLatch secondReleaseEntered = new CountDownLatch(1);
        CountDownLatch notificationEntered = new CountDownLatch(1);

        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<String> first = workers.submit(() -> gates.release(() -> {
                releaseEntered.countDown();
                await(allowRelease);
                return "first";
            }));
            assertThat(releaseEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> second = workers.submit(() -> gates.release(() -> {
                secondReleaseEntered.countDown();
                return "second";
            }));
            Future<?> notification = workers.submit(() -> gates.notification(
                    notificationEntered::countDown));

            assertThat(notificationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondReleaseEntered.getCount()).isEqualTo(1);

            allowRelease.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
            notification.get(5, TimeUnit.SECONDS);
        } finally {
            allowRelease.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void admitsConfiguredCapacityAndBackpressuresTheNextSubmission() throws Exception {
        LoadWorkflowGates gates = new LoadWorkflowGates(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = workers.submit(() -> gates.release(() -> {
                entered.countDown();
                await(release);
                return null;
            }));
            Future<?> second = workers.submit(() -> gates.release(() -> {
                entered.countDown();
                await(release);
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> third = workers.submit(() -> gates.release(() -> {
                thirdEntered.countDown();
                return null;
            }));

            assertThat(thirdEntered.getCount()).isEqualTo(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new DemoException(DemoError.INTERNAL_ERROR);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.INTERNAL_ERROR);
        }
    }
}
