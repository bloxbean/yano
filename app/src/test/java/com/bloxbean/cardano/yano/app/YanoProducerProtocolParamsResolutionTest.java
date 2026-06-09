package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.blockproducer.EffectiveProtocolParamsSupplier;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoProducerProtocolParamsResolutionTest {

    private final LedgerStateProvider ledgerStateProvider = new TestLedgerStateProvider();
    private final EpochSlotCalc epochSlotCalc = new EpochSlotCalc(100, 10, 0);

    @Test
    void trackingWithLedgerStateUsesEffectiveSupplierFirst() {
        var resolution = YanoProducer.selectTransactionProtocolParams(
                true, ledgerStateProvider, epochSlotCalc,
                () -> params(12, 0),
                () -> params(13, 0));

        assertNotNull(resolution);
        assertEquals("effective-ledger", resolution.source());
        assertTrue(resolution.requireLedgerStateProvider());
        assertInstanceOf(EffectiveProtocolParamsSupplier.class, resolution.supplier());
        assertEquals(11, resolution.supplier().getProtocolParams(0).getProtocolMajorVer());
    }

    @Test
    void trackingWithoutLedgerStateFallsBackToStaticBeforeGenesis() {
        AtomicInteger staticCalls = new AtomicInteger();
        AtomicInteger genesisCalls = new AtomicInteger();

        var resolution = YanoProducer.selectTransactionProtocolParams(
                true, null, epochSlotCalc,
                () -> {
                    staticCalls.incrementAndGet();
                    return params(12, 0);
                },
                () -> {
                    genesisCalls.incrementAndGet();
                    return params(13, 0);
                });

        assertNotNull(resolution);
        assertEquals("static-protocol-param-json", resolution.source());
        assertFalse(resolution.requireLedgerStateProvider());
        assertEquals(12, resolution.supplier().getProtocolParams(0).getProtocolMajorVer());
        assertEquals(1, staticCalls.get());
        assertEquals(0, genesisCalls.get());
    }

    @Test
    void trackingWithoutLedgerStateFallsBackToGenesisWhenStaticUnavailable() {
        var resolution = YanoProducer.selectTransactionProtocolParams(
                true, null, epochSlotCalc,
                () -> null,
                () -> params(13, 0));

        assertNotNull(resolution);
        assertEquals("genesis-bootstrap", resolution.source());
        assertFalse(resolution.requireLedgerStateProvider());
        assertEquals(13, resolution.supplier().getProtocolParams(0).getProtocolMajorVer());
    }

    @Test
    void trackingWithoutAnyFallbackReturnsNull() {
        var resolution = YanoProducer.selectTransactionProtocolParams(
                true, null, epochSlotCalc,
                () -> null,
                () -> null);

        assertNull(resolution);
    }

    @Test
    void trackingOffUsesStaticBeforeGenesis() {
        AtomicInteger genesisCalls = new AtomicInteger();

        var resolution = YanoProducer.selectTransactionProtocolParams(
                false, ledgerStateProvider, epochSlotCalc,
                () -> params(12, 0),
                () -> {
                    genesisCalls.incrementAndGet();
                    return params(13, 0);
                });

        assertNotNull(resolution);
        assertEquals("static-protocol-param-json", resolution.source());
        assertFalse(resolution.requireLedgerStateProvider());
        assertEquals(12, resolution.supplier().getProtocolParams(0).getProtocolMajorVer());
        assertEquals(0, genesisCalls.get());
    }

    @Test
    void trackingOffUsesGenesisWhenStaticUnavailable() {
        var resolution = YanoProducer.selectTransactionProtocolParams(
                false, ledgerStateProvider, epochSlotCalc,
                () -> null,
                () -> params(13, 0));

        assertNotNull(resolution);
        assertEquals("genesis-bootstrap", resolution.source());
        assertFalse(resolution.requireLedgerStateProvider());
        assertEquals(13, resolution.supplier().getProtocolParams(0).getProtocolMajorVer());
    }

    private static ProtocolParams params(int major, int minor) {
        ProtocolParams params = new ProtocolParams();
        params.setProtocolMajorVer(major);
        params.setProtocolMinorVer(minor);
        return params;
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
