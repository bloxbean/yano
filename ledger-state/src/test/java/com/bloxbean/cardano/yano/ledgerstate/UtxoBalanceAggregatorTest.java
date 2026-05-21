package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtxoBalanceAggregatorTest {

    @Test
    void aggregateBalancesSkipsLegacyByronAddressWithoutStakeCredential() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState(
                "Ae2tdPwUPEZHWn3PDn9cVng11YnjXTb6bmfQ4Pw9nCvVstM7uEYEqUzuQAb",
                BigInteger.valueOf(42_000_000L));

        var balances = aggregator.aggregateBalances(utxoState);

        assertTrue(balances.isEmpty());
    }

    @Test
    void aggregateBalancesSkipsOtherByronBase58AddressWithoutStakeCredential() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState(
                "2w1sdSJu3GVgD4Ldoi7YJZGMcbSEJ6TwoiEdBZKqphceKVoj2KgBZT351pXenJWFyDqUmjEuNXXg15MZajzU78itFEAqSrPz5eA",
                BigInteger.valueOf(42_000_000L));

        var balances = aggregator.aggregateBalances(utxoState);

        assertTrue(balances.isEmpty());
    }

    @Test
    void malformedShelleyPaymentAddressStillFails() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState("addr1notavalidchecksum", BigInteger.valueOf(42_000_000L));

        assertThrows(RuntimeException.class, () -> aggregator.aggregateBalances(utxoState));
    }

    @Test
    void unresolvedPointerAddressIsSkipped() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState(
                "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k",
                BigInteger.valueOf(42_000_000L));
        PointerAddressResolver unresolved = new PointerAddressResolver(null, null) {
            @Override
            public StakeCredential resolve(long slot, int txIndex, int certIndex) {
                return null;
            }
        };

        var balances = aggregator.aggregateBalances(utxoState, unresolved, -1);

        assertTrue(balances.isEmpty());
    }

    private record SingleUtxoState(String address, BigInteger lovelace) implements UtxoState {
        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public void forEachUtxo(BiConsumer<String, BigInteger> consumer) {
            consumer.accept(address, lovelace);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
