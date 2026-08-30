package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.config.ResourceProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeerSessionResourceProfileTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(YanoPropertyKeys.BodyFetch.MAX_BATCH_SIZE);
    }

    @Test
    void lowMemoryProfileReducesBodyFetchBatchAndExplicitValueWins() {
        assertThat(PeerSession.configuredBodyFetchBatchSize(ResourceProfile.DEFAULT))
                .isEqualTo(5000);
        assertThat(PeerSession.configuredBodyFetchBatchSize(ResourceProfile.LOW_MEMORY))
                .isEqualTo(1000);

        System.setProperty(YanoPropertyKeys.BodyFetch.MAX_BATCH_SIZE, "256");
        assertThat(PeerSession.configuredBodyFetchBatchSize(ResourceProfile.LOW_MEMORY))
                .isEqualTo(256);
    }
}
