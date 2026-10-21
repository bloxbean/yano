package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ForgingReadinessTest {

    private static final ChainTip LOCAL = tip(1_000, (byte) 1);

    private final AtomicBoolean initialSyncComplete = new AtomicBoolean(true);
    private final AtomicReference<ChainTip> headerTip = new AtomicReference<>(LOCAL);
    private final AtomicLong upstreamTipSlot = new AtomicLong(LOCAL.getSlot());
    private final ForgingReadiness readiness = ForgingReadiness.upstream(
            initialSyncComplete::get, headerTip::get, upstreamTipSlot::get);

    @Test
    void neverReadyDuringInitialSync() {
        initialSyncComplete.set(false);

        assertThat(readiness.isCaughtUp(LOCAL)).as("even at the upstream tip").isFalse();
    }

    @Test
    void readyWhenTheLocalTipIsTheUpstreamTip() {
        assertThat(readiness.isCaughtUp(LOCAL)).isTrue();
    }

    @Test
    void readyAfterForgingPastTheUpstreamTip() {
        ChainTip forged = tip(1_020, (byte) 2);

        assertThat(readiness.isCaughtUp(forged)).isTrue();
    }

    @Test
    void notReadyWhileUpstreamIsAhead() {
        upstreamTipSlot.set(1_001);

        assertThat(readiness.isCaughtUp(LOCAL)).isFalse();
    }

    @Test
    void notReadyWithAHeaderWaitingForItsBody() {
        headerTip.set(tip(1_005, (byte) 3));

        assertThat(readiness.isCaughtUp(LOCAL)).isFalse();
    }

    @Test
    void notReadyWhenTheBestHeaderIsAnotherBlockInTheSameSlot() {
        headerTip.set(tip(LOCAL.getSlot(), (byte) 4));

        assertThat(readiness.isCaughtUp(LOCAL)).isFalse();
    }

    @Test
    void readyWithoutAnyUpstreamTipOrHeaderOnceInitialSyncCompleted() {
        headerTip.set(null);
        upstreamTipSlot.set(-1);

        assertThat(readiness.isCaughtUp(LOCAL)).isTrue();
        assertThat(readiness.isCaughtUp(null)).as("no local chain yet").isFalse();
    }

    @Test
    void aStandaloneProducerIsAlwaysReady() {
        assertThat(ForgingReadiness.STANDALONE.isCaughtUp(LOCAL)).isTrue();
    }

    private static ChainTip tip(long slot, byte hashByte) {
        byte[] hash = new byte[32];
        hash[0] = hashByte;
        return new ChainTip(slot, hash, slot / 20);
    }
}
