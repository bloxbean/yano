package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.bridge.LocalBridgeHttpServer;
import com.bloxbean.cardano.yano.wallet.ui.WalletRuntimeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class YanoWalletAppControllerBridgeTest {
    @TempDir
    Path tempDir;

    @Test
    void startsAndStopsLoopbackBridge() throws Exception {
        YanoWalletAppController controller = new YanoWalletAppController(
                new FilePendingTransactionStore(tempDir.resolve("pending.json")),
                new FileStoredWalletRepository(tempDir.resolve("preprod"), WalletNetwork.PREPROD));
        try {
            WalletRuntimeController.BridgeSnapshot started = controller.startBridge(
                            (origin, permissions) -> true,
                            request -> true)
                    .get(10, TimeUnit.SECONDS);

            assertThat(started.running()).isTrue();
            assertThat(started.endpoint()).isEqualTo(LocalBridgeHttpServer.defaultEndpointUri().toString());
            assertThat(started.sessionCount()).isZero();

            WalletRuntimeController.BridgeSnapshot refreshed = controller.refreshBridgeStatus()
                    .get(10, TimeUnit.SECONDS);
            assertThat(refreshed.running()).isTrue();
            assertThat(refreshed.endpoint()).isEqualTo(started.endpoint());

            WalletRuntimeController.BridgeSnapshot stopped = controller.stopBridge()
                    .get(10, TimeUnit.SECONDS);
            assertThat(stopped.running()).isFalse();
            assertThat(stopped.endpoint()).isNull();
        } finally {
            controller.close();
        }
    }
}
