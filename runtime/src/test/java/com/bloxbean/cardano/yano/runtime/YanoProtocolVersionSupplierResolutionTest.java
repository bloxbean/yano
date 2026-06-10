package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yano.runtime.blockproducer.EffectiveProtocolVersionSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersion;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoProtocolVersionSupplierResolutionTest {

    private final EpochParamTracker tracker = new EpochParamTracker(new TestEpochParamProvider(), true);
    private final LedgerStateProvider ledgerStateProvider = new TestLedgerStateProvider();

    @Test
    void trackingWithLedgerStateUsesEffectiveSupplierFirst() {
        ProtocolVersionSupplier supplier = Yano.resolveBlockProtocolVersionSupplier(
                true, tracker, ledgerStateProvider,
                () -> ProtocolVersionSupplier.fixed(12, 0),
                () -> ProtocolVersionSupplier.fixed(13, 0));

        assertThat(supplier).isInstanceOf(EffectiveProtocolVersionSupplier.class);
        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(11, 0));
    }

    @Test
    void trackingWithoutLedgerStateFallsBackToStaticBeforeGenesis() {
        AtomicInteger staticCalls = new AtomicInteger();
        AtomicInteger genesisCalls = new AtomicInteger();

        ProtocolVersionSupplier supplier = Yano.resolveBlockProtocolVersionSupplier(
                true, tracker, null,
                () -> {
                    staticCalls.incrementAndGet();
                    return ProtocolVersionSupplier.fixed(12, 0);
                },
                () -> {
                    genesisCalls.incrementAndGet();
                    return ProtocolVersionSupplier.fixed(13, 0);
                });

        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(12, 0));
        assertThat(staticCalls).hasValue(1);
        assertThat(genesisCalls).hasValue(0);
    }

    @Test
    void trackingWithoutLedgerStateFallsBackToGenesisWhenStaticUnavailable() {
        ProtocolVersionSupplier supplier = Yano.resolveBlockProtocolVersionSupplier(
                true, tracker, null,
                () -> null,
                () -> ProtocolVersionSupplier.fixed(13, 0));

        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(13, 0));
    }

    @Test
    void trackingWithoutAnyFallbackThrowsClearly() {
        assertThatThrownBy(() -> Yano.resolveBlockProtocolVersionSupplier(
                true, tracker, null,
                () -> null,
                () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No protocol version source available");
    }

    @Test
    void trackingOffUsesStaticBeforeGenesis() {
        AtomicInteger genesisCalls = new AtomicInteger();

        ProtocolVersionSupplier supplier = Yano.resolveBlockProtocolVersionSupplier(
                false, tracker, ledgerStateProvider,
                () -> ProtocolVersionSupplier.fixed(12, 0),
                () -> {
                    genesisCalls.incrementAndGet();
                    return ProtocolVersionSupplier.fixed(13, 0);
                });

        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(12, 0));
        assertThat(genesisCalls).hasValue(0);
    }

    @Test
    void trackingOffUsesGenesisWhenStaticUnavailable() {
        ProtocolVersionSupplier supplier = Yano.resolveBlockProtocolVersionSupplier(
                false, tracker, ledgerStateProvider,
                () -> null,
                () -> ProtocolVersionSupplier.fixed(13, 0));

        assertThat(supplier.getProtocolVersion(0)).isEqualTo(new ProtocolVersion(13, 0));
    }

    private static class TestEpochParamProvider implements EpochParamProvider {
        @Override
        public BigInteger getKeyDeposit(long epoch) {
            return BigInteger.ZERO;
        }

        @Override
        public BigInteger getPoolDeposit(long epoch) {
            return BigInteger.ZERO;
        }

        @Override
        public long getEpochLength() {
            return 100;
        }

        @Override
        public long getByronSlotsPerEpoch() {
            return 10;
        }

        @Override
        public int getProtocolMajor(long epoch) {
            return 11;
        }

        @Override
        public int getProtocolMinor(long epoch) {
            return 0;
        }
    }

    private static class TestLedgerStateProvider implements LedgerStateProvider {
        @Override
        public Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
            return Optional.of(snapshot(epoch, 11, 0));
        }

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
}
