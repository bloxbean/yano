package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.exception.ApiRuntimeException;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoProtocolParamsSupplierTest {
    @Test
    void readsProtocolParamsFromLedgerSnapshot() {
        YanoProtocolParamsSupplier supplier = new YanoProtocolParamsSupplier(
                epoch -> Optional.of(snapshot(epoch)),
                () -> 285,
                null);

        ProtocolParams params = supplier.getProtocolParams();

        assertThat(params.getMinFeeA()).isEqualTo(44);
        assertThat(params.getMinFeeB()).isEqualTo(155381);
        assertThat(params.getMaxTxSize()).isEqualTo(16384);
        assertThat(params.getCoinsPerUtxoSize()).isEqualTo("4310");
        assertThat(params.getPriceMem()).isEqualTo(new BigDecimal("0.0577"));
        assertThat(params.getCostModels().get("PlutusV1").values()).containsExactly(10L, 20L);
    }

    @Test
    void fallsBackToNodeProtocolParameterJsonWhenSnapshotMissing() {
        String protocolParamsJson = """
                {
                  "txFeePerByte": 44,
                  "txFeeFixed": 155381,
                  "maxTxSize": 16384,
                  "utxoCostPerByte": 4310,
                  "protocolVersion": {"major": 10, "minor": 0}
                }
                """;
        YanoProtocolParamsSupplier supplier = new YanoProtocolParamsSupplier(
                epoch -> Optional.empty(),
                () -> 1,
                protocolParamsJson);

        ProtocolParams params = supplier.getProtocolParams();

        assertThat(params.getMinFeeA()).isEqualTo(44);
        assertThat(params.getMinFeeB()).isEqualTo(155381);
        assertThat(params.getMaxTxSize()).isEqualTo(16384);
        assertThat(params.getCoinsPerUtxoSize()).isEqualTo("4310");
        assertThat(params.getProtocolMajorVer()).isEqualTo(10);
    }

    @Test
    void throwsWhenProtocolParamsAreUnavailable() {
        YanoProtocolParamsSupplier supplier = new YanoProtocolParamsSupplier(
                epoch -> Optional.empty(),
                () -> 42,
                null);

        assertThatThrownBy(supplier::getProtocolParams)
                .isInstanceOf(ApiRuntimeException.class)
                .hasMessageContaining("unavailable for epoch 42");
    }

    private LedgerStateProvider.ProtocolParamsSnapshot snapshot(int epoch) {
        return new LedgerStateProvider.ProtocolParamsSnapshot(
                epoch,
                44,
                155381,
                90112,
                16384,
                1100,
                BigInteger.valueOf(2_000_000),
                BigInteger.valueOf(500_000_000),
                18,
                500,
                new BigDecimal("0.3"),
                new BigDecimal("0.003"),
                new BigDecimal("0.2"),
                null,
                null,
                10,
                2,
                null,
                BigInteger.valueOf(170_000_000),
                null,
                Map.of("PlutusV1", Map.of("000", 10L, "001", 20L)),
                Map.of("PlutusV1", List.of(10L, 20L)),
                new BigDecimal("0.0577"),
                new BigDecimal("0.0000721"),
                BigInteger.valueOf(16_500_000),
                new BigInteger("10000000000"),
                BigInteger.valueOf(72_000_000),
                new BigInteger("20000000000"),
                BigInteger.valueOf(5000),
                150,
                3,
                BigInteger.valueOf(4310),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                3,
                146,
                6,
                new BigInteger("100000000000"),
                BigInteger.valueOf(500_000_000),
                20,
                new BigDecimal("15"));
    }
}
