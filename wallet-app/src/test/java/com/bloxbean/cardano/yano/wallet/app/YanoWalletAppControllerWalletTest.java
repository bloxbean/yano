package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.ui.WalletRuntimeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class YanoWalletAppControllerWalletTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code";

    @TempDir
    Path tempDir;

    @Test
    void createsAndActivatesAdditionalAccountForUnlockedWallet() throws Exception {
        YanoWalletAppController controller = new YanoWalletAppController(
                new FilePendingTransactionStore(tempDir.resolve("pending.json")),
                new FileStoredWalletRepository(tempDir.resolve("preprod"), WalletNetwork.PREPROD));
        try {
            controller.importPreprodWallet("Wallet", MNEMONIC, "passphrase")
                    .get(10, TimeUnit.SECONDS);

            WalletRuntimeController.AccountSnapshot account0 = controller.refreshActiveWalletAccount(1)
                    .get(10, TimeUnit.SECONDS);
            assertThat(account0.accountIndex()).isZero();

            WalletRuntimeController.WalletSnapshot created = controller.createAccountForActiveWallet("Trading")
                    .get(10, TimeUnit.SECONDS);
            WalletRuntimeController.AccountSnapshot account1 = controller.refreshActiveWalletAccount(1)
                    .get(10, TimeUnit.SECONDS);
            List<WalletRuntimeController.StoredWalletSnapshot> storedWallets = controller.listStoredWallets()
                    .get(10, TimeUnit.SECONDS);

            assertThat(created.message()).contains("Account 1");
            assertThat(account1.accountIndex()).isEqualTo(1);
            assertThat(account1.name()).isEqualTo("Trading");
            assertThat(account1.receiveAddresses().getFirst().baseAddress()).isEqualTo(created.address());
            assertThat(storedWallets).hasSize(2);
            assertThat(storedWallets)
                    .extracting(WalletRuntimeController.StoredWalletSnapshot::accountIndex)
                    .containsExactly(0, 1);
            assertThat(storedWallets.get(1).seedId()).isEqualTo(storedWallets.getFirst().seedId());
        } finally {
            controller.close();
        }
    }
}
