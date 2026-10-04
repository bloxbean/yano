package org.yanoproject.runtime.chain;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalMutationSequenceTest {

    /** Overlapping writers on two threads keep the sequence odd until the last one ends (PR #167 review, I2). */
    @Test
    void overlappingWritersKeepTheSequenceOddUntilTheLastEnds() throws Exception {
        CanonicalMutationSequence sequence = new CanonicalMutationSequence();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        Thread first = new Thread(() -> mutate(sequence, started, releaseFirst));
        Thread second = new Thread(() -> mutate(sequence, started, releaseSecond));
        first.start();
        second.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sequence.current() & 1L).as("both writers active").isEqualTo(1L);

        releaseFirst.countDown();
        first.join();
        assertThat(sequence.current() & 1L).as("one writer still active").isEqualTo(1L);

        releaseSecond.countDown();
        second.join();
        assertThat(sequence.current()).as("even again, and changed").isEqualTo(2L);
    }

    @Test
    void nestedMutationsOnOneThreadCountOnce() {
        CanonicalMutationSequence sequence = new CanonicalMutationSequence();
        sequence.begin();
        sequence.begin();
        assertThat(sequence.current()).isEqualTo(1L);
        sequence.end();
        assertThat(sequence.current()).isEqualTo(1L);
        sequence.end();
        assertThat(sequence.current()).isEqualTo(2L);
    }

    private static void mutate(CanonicalMutationSequence sequence, CountDownLatch started, CountDownLatch release) {
        sequence.begin();
        try {
            started.countDown();
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            sequence.end();
        }
    }
}
