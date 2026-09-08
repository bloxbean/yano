package com.bloxbean.cardano.yano.app.archive;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionHistoryDrainControlTest {

    @Test
    void stopWakesAnIdleDrainWithoutInterruptingIt() throws Exception {
        var control = new ProjectionHistoryService.DrainControl();
        var waiting = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        control.start();

        var drain = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try {
                control.await(Duration.ofMinutes(1).toMillis());
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        control.stop();
        drain.join(5_000);

        assertThat(drain.isAlive()).isFalse();
        assertThat(interrupted).isFalse();
        assertThat(control.isRunning()).isFalse();
    }

    @Test
    void stopLeavesInFlightDriverWorkUninterrupted() throws Exception {
        var control = new ProjectionHistoryService.DrainControl();
        var inDriver = new CountDownLatch(1);
        var releaseDriver = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        control.start();

        var drain = Thread.ofVirtual().start(() -> {
            inDriver.countDown();
            try {
                releaseDriver.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        assertThat(inDriver.await(5, TimeUnit.SECONDS)).isTrue();
        control.stop();

        assertThat(drain.isAlive()).isTrue();
        assertThat(interrupted).isFalse();

        releaseDriver.countDown();
        drain.join(5_000);
        assertThat(interrupted).isFalse();
    }
}
