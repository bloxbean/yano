package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectiveProtocolVersionSupplierTest {

    @Test
    void cachesLastResolvedEpochAndRefreshesOnEpochChange() {
        AtomicInteger lookups = new AtomicInteger();
        LedgerStateProvider provider = new TestLedgerStateProvider() {
            @Override
            public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
                lookups.incrementAndGet();
                return Optional.of(snapshot(epoch, 10 + epoch, 0));
            }
        };

        var supplier = new EffectiveProtocolVersionSupplier(
                provider, new EpochSlotCalc(100, 10, 0), (IntConsumer) null);

        assertThat(supplier.getProtocolVersion(25)).isEqualTo(new ProtocolVersion(10, 0));
        assertThat(supplier.getProtocolVersion(99)).isEqualTo(new ProtocolVersion(10, 0));
        assertThat(lookups).hasValue(1);

        assertThat(supplier.getProtocolVersion(100)).isEqualTo(new ProtocolVersion(11, 0));
        assertThat(lookups).hasValue(2);
    }

    @Test
    void lazilyBootstrapsMissingEpochBeforeFailing() {
        AtomicBoolean bootstrapped = new AtomicBoolean();
        LedgerStateProvider provider = new TestLedgerStateProvider() {
            @Override
            public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
                if (!bootstrapped.get()) {
                    return Optional.empty();
                }
                return Optional.of(snapshot(epoch, 11, 0));
            }
        };

        var supplier = new EffectiveProtocolVersionSupplier(
                provider, new EpochSlotCalc(100, 10, 0), epoch -> bootstrapped.set(true));

        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(11, 0));
        assertThat(bootstrapped).isTrue();
    }

    @Test
    void failsClearlyWhenSnapshotIsUnavailable() {
        LedgerStateProvider provider = new TestLedgerStateProvider() {
            @Override
            public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
                return Optional.empty();
            }
        };

        var supplier = new EffectiveProtocolVersionSupplier(
                provider, new EpochSlotCalc(100, 10, 0), (IntConsumer) null);

        assertThatThrownBy(() -> supplier.getProtocolVersion(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Effective protocol parameters are unavailable for epoch 0");
    }

    @Test
    void failsClearlyWhenSnapshotHasNoProtocolVersion() {
        LedgerStateProvider provider = new TestLedgerStateProvider() {
            @Override
            public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
                return Optional.of(snapshot(epoch, null, null));
            }
        };

        var supplier = new EffectiveProtocolVersionSupplier(
                provider, new EpochSlotCalc(100, 10, 0), (IntConsumer) null);

        assertThatThrownBy(() -> supplier.getProtocolVersion(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Effective protocol version is unavailable or invalid for epoch 0");
    }

    private static ProtocolParamsSnapshot snapshot(
            int epoch, Integer major, Integer minor) {
        return new ProtocolParamsSnapshot(
                epoch,
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                major, minor,
                null, null, null,
                null, null,
                null, null,
                null, null, null, null, null,
                null, null,
                null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                null);
    }

    private abstract static class TestLedgerStateProvider implements LedgerStateProvider {
        @Override
        public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getDelegatedPool(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
            return false;
        }

        @Override
        public BigInteger getTotalDeposited() {
            return BigInteger.ZERO;
        }

        @Override
        public boolean isPoolRegistered(String poolHash) {
            return false;
        }

        @Override
        public Optional<BigInteger> getPoolDeposit(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getPoolRetirementEpoch(String poolHash) {
            return Optional.empty();
        }
    }
}
