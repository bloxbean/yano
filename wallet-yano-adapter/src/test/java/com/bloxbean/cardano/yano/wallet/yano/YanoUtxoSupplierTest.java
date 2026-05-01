package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.common.CardanoConstants;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YanoUtxoSupplierTest {
    @Test
    void mapsYanoUtxosToCclUtxosAndNormalizesPage() {
        FakeUtxoState utxoState = new FakeUtxoState();
        utxoState.addressUtxos = List.of(utxo("a".repeat(64), 1, BigInteger.valueOf(1_500_000)));
        YanoUtxoSupplier supplier = new YanoUtxoSupplier(utxoState);

        List<com.bloxbean.cardano.client.api.model.Utxo> result =
                supplier.getPage("addr_test1...", 25, 0, OrderEnum.asc);

        assertThat(utxoState.lastAddress).isEqualTo("addr_test1...");
        assertThat(utxoState.lastPage).isEqualTo(1);
        assertThat(utxoState.lastPageSize).isEqualTo(25);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTxHash()).isEqualTo("a".repeat(64));
        assertThat(result.getFirst().getOutputIndex()).isEqualTo(1);
        assertThat(result.getFirst().getAmount())
                .anySatisfy(amount -> {
                    assertThat(amount.getUnit()).isEqualTo(CardanoConstants.LOVELACE);
                    assertThat(amount.getQuantity()).isEqualTo(BigInteger.valueOf(1_500_000));
                })
                .anySatisfy(amount -> {
                    assertThat(amount.getUnit()).isEqualTo("policyasset");
                    assertThat(amount.getQuantity()).isEqualTo(BigInteger.TEN);
                });
        assertThat(result.getFirst().getInlineDatum()).isEqualTo("0102");
        assertThat(result.getFirst().getReferenceScriptHash()).isEqualTo("refhash");
    }

    @Test
    void canSearchByPaymentCredential() {
        FakeUtxoState utxoState = new FakeUtxoState();
        YanoUtxoSupplier supplier = new YanoUtxoSupplier(utxoState);
        supplier.setSearchByAddressVkh(true);

        supplier.getPage("addr_vkh1test", null, 1, OrderEnum.asc);

        assertThat(utxoState.lastPaymentCredential).isEqualTo("addr_vkh1test");
        assertThat(utxoState.lastPage).isEqualTo(2);
        assertThat(utxoState.lastPageSize).isEqualTo(YanoUtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH);
    }

    @Test
    void getTxOutputMapsOutpointLookup() {
        FakeUtxoState utxoState = new FakeUtxoState();
        Utxo yanoUtxo = utxo("b".repeat(64), 2, BigInteger.valueOf(2_000_000));
        utxoState.outpointUtxo = Optional.of(yanoUtxo);
        YanoUtxoSupplier supplier = new YanoUtxoSupplier(utxoState);

        Optional<com.bloxbean.cardano.client.api.model.Utxo> result = supplier.getTxOutput("b".repeat(64), 2);

        assertThat(utxoState.lastOutpoint).isEqualTo(new Outpoint("b".repeat(64), 2));
        assertThat(result).isPresent();
        assertThat(result.get().getTxHash()).isEqualTo("b".repeat(64));
        assertThat(result.get().getOutputIndex()).isEqualTo(2);
    }

    @Test
    void descOrderReversesReturnedPage() {
        FakeUtxoState utxoState = new FakeUtxoState();
        utxoState.addressUtxos = List.of(
                utxo("a".repeat(64), 0, BigInteger.ONE),
                utxo("b".repeat(64), 1, BigInteger.TWO));
        YanoUtxoSupplier supplier = new YanoUtxoSupplier(utxoState);

        List<com.bloxbean.cardano.client.api.model.Utxo> result =
                supplier.getPage("addr_test1...", 10, 0, OrderEnum.desc);

        assertThat(result).extracting(com.bloxbean.cardano.client.api.model.Utxo::getTxHash)
                .containsExactly("b".repeat(64), "a".repeat(64));
    }

    private Utxo utxo(String txHash, int index, BigInteger lovelace) {
        return new Utxo(
                new Outpoint(txHash, index),
                "addr_test1...",
                lovelace,
                List.of(new AssetAmount("policy", "asset", BigInteger.TEN)),
                "datumhash",
                new byte[]{1, 2},
                null,
                "refhash",
                false,
                10,
                20,
                "blockhash");
    }

    private static class FakeUtxoState implements UtxoState {
        private List<Utxo> addressUtxos = new ArrayList<>();
        private List<Utxo> paymentCredentialUtxos = new ArrayList<>();
        private Optional<Utxo> outpointUtxo = Optional.empty();
        private String lastAddress;
        private String lastPaymentCredential;
        private int lastPage;
        private int lastPageSize;
        private Outpoint lastOutpoint;

        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            lastAddress = bech32OrHexAddress;
            lastPage = page;
            lastPageSize = pageSize;
            return addressUtxos;
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            lastPaymentCredential = credentialHexOrAddress;
            lastPage = page;
            lastPageSize = pageSize;
            return paymentCredentialUtxos;
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            lastOutpoint = outpoint;
            return outpointUtxo;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
