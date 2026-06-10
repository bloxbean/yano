package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtxoBalanceAggregatorTest {
    private static final String PAYMENT_HASH = "11".repeat(28);
    private static final String STAKE_HASH = "22".repeat(28);

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
    void extractCredentialParsesShelleyHexBaseAddress() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        String addressHex = "00" + PAYMENT_HASH + STAKE_HASH;

        var credential = aggregator.extractCredential(addressHex, null);

        assertEquals(new UtxoBalanceAggregator.CredentialKey(0, STAKE_HASH), credential);
    }

    @Test
    void extractCredentialSkipsRewardAccountHexAddress() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        String rewardAccountHex = "e0" + STAKE_HASH;

        var credential = aggregator.extractCredential(rewardAccountHex, null);

        assertNull(credential);
    }

    @Test
    void extractCredentialReturnsNullForMalformedNonShelleyHex() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();

        assertNull(aggregator.extractCredential("0011223", null));
        assertNull(aggregator.extractCredential("001122zz", null));
        assertNull(aggregator.extractCredential("Ae2tdPwUPEZHWn3PDn9cVng11YnjXTb6bmfQ4Pw9nCvVstM7uEYEqUzuQAb", null));
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
