package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.utxo.PointerAddressId;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxoView;
import com.bloxbean.cardano.yano.api.utxo.StakeBalanceView;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialBalance;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialExtractor;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
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
    void malformedShelleyPaymentAddressFailsClosed() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState("addr1notavalidchecksum", BigInteger.valueOf(42_000_000L));

        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregateBalances(utxoState));
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

    @Test
    void pointerOverlayDoesNotMaterializeNonPointerCredentials() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        String pointerAddress =
                "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k";
        String baseAddressHex = "00" + PAYMENT_HASH + "33".repeat(28);
        UtxoState utxoState = new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.empty();
            }

            @Override
            public void forEachUtxo(BiConsumer<String, BigInteger> consumer) {
                consumer.accept(baseAddressHex, BigInteger.valueOf(99_000_000L));
                consumer.accept(pointerAddress, BigInteger.valueOf(42_000_000L));
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        PointerAddressResolver resolver = new PointerAddressResolver(null, null) {
            @Override
            public StakeCredential resolve(long slot, int txIndex, int certIndex) {
                return new StakeCredential(0, STAKE_HASH);
            }
        };

        var balances = aggregator.aggregatePointerBalances(utxoState, resolver, -1);

        assertEquals(1, balances.size());
        assertEquals(BigInteger.valueOf(42_000_000L),
                balances.get(new UtxoBalanceAggregator.CredentialKey(0, STAKE_HASH)));
    }

    @Test
    void pointerOverlayFailsClosedWithoutResolver() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        UtxoState utxoState = new SingleUtxoState(
                "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k",
                BigInteger.valueOf(42_000_000L));

        assertThrows(IllegalStateException.class,
                () -> aggregator.aggregatePointerBalances(utxoState, null, -1));
    }

    @Test
    void pointerIndexAndHistoricalScanProduceGoldenEquivalentOverlay() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        String pointerAddress =
                "addr1gxrgsz5tkx0vsapdhyrk09w9zplhllr94zy70vycpll2egsvpsxqgnmy5k";
        UtxoState utxoState = new SingleUtxoState(
                pointerAddress, BigInteger.valueOf(42_000_000L));
        PointerAddressId pointer = StakeCredentialExtractor.extractPointer(pointerAddress);
        PointerAddressResolver resolver = new PointerAddressResolver(null, null) {
            @Override
            public StakeCredential resolve(long slot, int txIndex, int certIndex) {
                assertEquals(pointer.slot(), slot);
                assertEquals(pointer.transactionIndex(), txIndex);
                assertEquals(pointer.certificateIndex(), certIndex);
                return new StakeCredential(0, STAKE_HASH);
            }
        };

        var scanned = aggregator.aggregatePointerBalancesWithStats(
                utxoState, resolver, -1);
        var indexed = aggregator.aggregatePointerBalancesFromIndex(
                new SinglePointerStakeView(new PointerUtxo(
                        100, BigInteger.valueOf(42_000_000L), pointer)),
                resolver, 100);

        assertEquals(scanned.balances(), indexed.balances());
        assertEquals(scanned.resolved(), indexed.resolved());
        assertEquals(scanned.failed(), indexed.failed());
        assertEquals("pointer-scan", scanned.path());
        assertEquals("pointer-index", indexed.path());
    }

    @Test
    void unresolvablePointerPayloadHasExactScanAndIndexFailureParity() {
        UtxoBalanceAggregator aggregator = new UtxoBalanceAggregator();
        PointerAddressResolver resolver = new PointerAddressResolver(null, null) {
            @Override
            public StakeCredential resolve(long slot, int txIndex, int certIndex) {
                throw new AssertionError("unresolvable rows must not reach the resolver");
            }
        };
        var scanned = aggregator.aggregatePointerBalancesWithStats(
                new SingleUtxoState(
                        unresolvablePointerAddress(), BigInteger.valueOf(42_000_000L)),
                resolver, -1);
        var indexed = aggregator.aggregatePointerBalancesFromIndex(
                new SinglePointerStakeView(new PointerUtxo(
                        100, BigInteger.valueOf(42_000_000L), null)),
                resolver, 100);

        assertEquals(scanned.balances(), indexed.balances());
        assertEquals(0, indexed.resolved());
        assertEquals(1, scanned.failed());
        assertEquals(scanned.failed(), indexed.failed());
    }

    private static String unresolvablePointerAddress() {
        byte[] bytes = new byte[1 + 28 + 10];
        bytes[0] = 0x41;
        Arrays.fill(bytes, 1, 29, (byte) 0x11);
        Arrays.fill(bytes, 29, bytes.length, (byte) 0xFF);
        return new Address(bytes).toBech32();
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

    private static final class SinglePointerStakeView implements StakeBalanceView {
        private final PointerUtxo pointerUtxo;

        private SinglePointerStakeView(PointerUtxo pointerUtxo) {
            this.pointerUtxo = pointerUtxo;
        }

        @Override
        public CanonicalBlockReference coordinate() {
            return new CanonicalBlockReference(1, 100, new byte[32]);
        }

        @Override
        public boolean advance() {
            return false;
        }

        @Override
        public StakeCredentialBalance current() {
            throw new IllegalStateException("No stake rows");
        }

        @Override
        public Optional<PointerUtxoView> openPointerUtxoView(long maxCreationSlot) {
            return Optional.of(new PointerUtxoView() {
                private boolean advanced;

                @Override
                public boolean advance() {
                    if (advanced || pointerUtxo.creationSlot() > maxCreationSlot) return false;
                    advanced = true;
                    return true;
                }

                @Override
                public PointerUtxo current() {
                    if (!advanced) throw new IllegalStateException("not advanced");
                    return pointerUtxo;
                }

                @Override
                public void close() {
                }
            });
        }

        @Override
        public void close() {
        }
    }
}
