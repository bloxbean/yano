package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WalletBalanceServiceTest {
    @Test
    void scansWalletAddressesUntilGapLimitAndSumsLovelace() {
        Wallet wallet = Wallet.createFromMnemonic(
                Networks.preprod(),
                "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code");
        String address0 = wallet.getBaseAddressString(0);
        String address2 = wallet.getBaseAddressString(2);
        UtxoSupplier supplier = new FakeSupplier(address0, address2);

        WalletBalance balance = new WalletBalanceService().scan(wallet, supplier, 3, 10);

        assertThat(balance.lovelace()).isEqualTo(BigInteger.valueOf(3_000_000));
        assertThat(balance.utxoCount()).isEqualTo(2);
        assertThat(balance.addressCount()).isGreaterThanOrEqualTo(6);
        assertThat(balance.utxos()).extracting(WalletUtxoView::address)
                .contains(address0, address2);
    }

    @Test
    void aggregatesNativeAssetsAcrossWalletUtxos() {
        Wallet wallet = Wallet.createFromMnemonic(
                Networks.preprod(),
                "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code");
        String address0 = wallet.getBaseAddressString(0);
        String unit = "a".repeat(56) + "74657374";
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                if (page != 0 || !address0.equals(address)) {
                    return List.of();
                }
                return List.of(
                        Utxo.builder()
                                .txHash("c".repeat(64))
                                .outputIndex(0)
                                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), Amount.asset(unit, BigInteger.valueOf(7))))
                                .build(),
                        Utxo.builder()
                                .txHash("d".repeat(64))
                                .outputIndex(1)
                                .amount(List.of(Amount.lovelace(BigInteger.valueOf(3_000_000)), Amount.asset(unit, BigInteger.valueOf(5))))
                                .build());
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }
        };

        WalletBalance balance = new WalletBalanceService().scan(wallet, supplier, 2, 5);

        assertThat(balance.lovelace()).isEqualTo(BigInteger.valueOf(5_000_000));
        assertThat(balance.assets()).containsExactly(new WalletAssetBalance(unit, BigInteger.valueOf(12)));
    }

    private static class FakeSupplier implements UtxoSupplier {
        private final String address0;
        private final String address2;

        private FakeSupplier(String address0, String address2) {
            this.address0 = address0;
            this.address2 = address2;
        }

        @Override
        public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
            if (page != 0) {
                return List.of();
            }
            if (address0.equals(address)) {
                return List.of(utxo("a".repeat(64), 0, BigInteger.valueOf(1_000_000)));
            }
            if (address2.equals(address)) {
                return List.of(utxo("b".repeat(64), 1, BigInteger.valueOf(2_000_000)));
            }
            return List.of();
        }

        @Override
        public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
            return Optional.empty();
        }

        private Utxo utxo(String txHash, int index, BigInteger lovelace) {
            return Utxo.builder()
                    .txHash(txHash)
                    .outputIndex(index)
                    .amount(List.of(Amount.lovelace(lovelace)))
                    .build();
        }
    }
}
