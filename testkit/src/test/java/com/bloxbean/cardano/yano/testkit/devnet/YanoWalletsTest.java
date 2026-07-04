package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoWalletsTest {
    private static final NodeStatus HEALTHY = TestkitFakes.status(true, false);

    @Test
    void createsRandomWalletForDevnetNetwork() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            TestWallet wallet = kit.wallets().newWallet();
            TestWallet another = kit.wallets().newWallet();

            assertEquals(42L, kit.wallets().network().getProtocolMagic());
            assertTrue(wallet.baseAddress().startsWith("addr_test"));
            assertTrue(wallet.enterpriseAddress().startsWith("addr_test"));
            assertTrue(wallet.stakeAddress().startsWith("stake_test"));
            assertNotEquals(wallet.address(), another.address());
        }
    }

    @Test
    void deterministicWalletsAreStableByIndex() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            TestWallet first = kit.wallets().deterministicWallet(0);
            TestWallet repeated = kit.wallets().deterministicWallet(0);
            TestWallet second = kit.wallets().deterministicWallet(1);

            assertEquals(first.address(), repeated.address());
            assertNotEquals(first.address(), second.address());
        }
    }

    @Test
    void validatesMnemonicAndIndexInputs() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            assertThrows(IllegalArgumentException.class, () -> kit.wallets().fromMnemonic(""));
            assertThrows(IllegalArgumentException.class,
                    () -> kit.wallets().fromMnemonic(YanoWallets.DEFAULT_DETERMINISTIC_MNEMONIC, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> kit.wallets().newWallet(-1));
        }
    }

    @Test
    void faucetFundsWalletDefaultAddress() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(HEALTHY);
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.from(node)) {
            TestWallet wallet = kit.wallets().deterministicWallet(0);

            kit.faucet().fund(wallet, 1_000_000L);

            assertEquals(List.of(wallet.address()), node.devnet.fundedAddresses);
        }
    }

    @Test
    void walletAssertionsUsePublicUtxoQueries() {
        TestWallet wallet;
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            wallet = kit.wallets().deterministicWallet(0);
        }

        List<Utxo> utxos = List.of(
                utxo("a", 0, wallet.address(), 1_000_000L),
                utxo("b", 0, wallet.address(), 2_000_000L),
                utxo("c", 0, "addr_test1other", 5_000_000L));

        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY, utxos)) {
            assertEquals(BigInteger.valueOf(3_000_000L), kit.assertions().wallet(wallet).balance());
            kit.assertions().wallet(wallet).hasExactly(3_000_000L).hasAtLeast(2_000_000L);
            assertThrows(AssertionError.class, () -> kit.assertions().wallet(wallet).hasAtLeast(3_000_001L));
        }
    }

    @Test
    void walletAssertionsPageThroughUtxos() {
        TestWallet wallet;
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY)) {
            wallet = kit.wallets().deterministicWallet(0);
        }

        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < 125; i++) {
            utxos.add(utxo("tx-" + i, 0, wallet.address(), 1L));
        }

        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY, utxos)) {
            assertEquals(BigInteger.valueOf(125L), kit.assertions().wallet(wallet).balance());
        }
    }

    @Test
    void walletAssertionsFailClearlyWhenUtxoStateDisabled() {
        try (YanoDevnetTestKit kit = TestkitFakes.kit(HEALTHY, List.of(), false)) {
            TestWallet wallet = kit.wallets().deterministicWallet(0);

            assertThrows(AssertionError.class, () -> kit.assertions().wallet(wallet).balance());
        }
    }

    @Test
    void realDevnetFundsWalletAdvancesTimeAndRestoresSnapshot() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build();
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(config)) {
            kit.start();
            kit.assertions().nodeIsRunning();

            TestWallet wallet = kit.wallets().deterministicWallet(0);

            kit.snapshots().create("before-funding");
            FundResult funding = kit.faucet().fund(wallet, 3_000_000L);

            assertEquals(3_000_000L, funding.lovelace());
            assertTrue(kit.queries().utxoState().getUtxo(funding.txHash(), funding.index()).isPresent());
            kit.assertions().wallet(wallet).hasExactly(3_000_000L);

            TimeAdvanceResult advanced = kit.time().advanceSlots(1);
            assertTrue(advanced.blocksProduced() > 0);

            kit.snapshots().restore("before-funding");

            assertTrue(kit.queries().utxoState().getUtxo(funding.txHash(), funding.index()).isEmpty());
            kit.assertions().wallet(wallet).hasExactly(0L);
        }
    }

    private static Utxo utxo(String txHash, int index, String address, long lovelace) {
        return new Utxo(
                new Outpoint(txHash, index),
                address,
                BigInteger.valueOf(lovelace),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                0,
                0,
                null);
    }
}
