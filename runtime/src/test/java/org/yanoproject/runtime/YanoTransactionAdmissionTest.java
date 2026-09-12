package org.yanoproject.runtime;

import org.yanoproject.runtime.internal.RuntimeNode;

import org.yanoproject.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoTransactionAdmissionTest {

    @Test
    void stoppedFacadeRejectsTransactionSubmission() {
        RuntimeNode yano = new RuntimeNode(YanoConfig.builder()
                .protocolMagic(42L)
                .serverPort(0)
                .enableClient(false)
                .enableServer(false)
                .useRocksDB(false)
                .build());

        try {
            assertThatThrownBy(() -> yano.submitTransaction(new byte[] {1, 2, 3}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot submit transaction while node is not running");
        } finally {
            yano.close();
        }
    }
}
