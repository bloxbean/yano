package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class EffectiveProtocolParamsSupplierTest {

    @Test
    void usesLedgerSnapshotAndCachesOnlyCurrentEpoch() {
        AtomicInteger calls = new AtomicInteger();
        LedgerStateProvider provider = provider(epoch -> {
            calls.incrementAndGet();
            return Optional.of(ProtocolParamsMapperTest.snapshot(epoch, java.math.BigInteger.valueOf(5000L + epoch)));
        });

        EffectiveProtocolParamsSupplier supplier = new EffectiveProtocolParamsSupplier(
                provider,
                new EpochSlotCalc(100, 10, 0));

        ProtocolParams epoch10First = supplier.getProtocolParams(1000);
        ProtocolParams epoch10Second = supplier.getProtocolParams(1099);
        ProtocolParams epoch11 = supplier.getProtocolParams(1100);

        assertSame(epoch10First, epoch10Second);
        assertEquals("5010", epoch10First.getMaxValSize());
        assertEquals("5011", epoch11.getMaxValSize());
        assertEquals(2, calls.get());
    }

    @Test
    void missingLedgerSnapshotIsNotCachedSoLaterSnapshotCanTakeOver() {
        AtomicInteger calls = new AtomicInteger();
        LedgerStateProvider provider = provider(epoch -> {
            int call = calls.incrementAndGet();
            if (call == 1) return Optional.empty();
            return Optional.of(ProtocolParamsMapperTest.snapshot(epoch, java.math.BigInteger.valueOf(5000L + epoch)));
        });

        EffectiveProtocolParamsSupplier supplier = new EffectiveProtocolParamsSupplier(
                provider,
                new EpochSlotCalc(100, 10, 0));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> supplier.getProtocolParams(1000));
        assertEquals("Effective protocol parameters are unavailable for epoch 10", error.getMessage());
        assertEquals("5010", supplier.getProtocolParams(1001).getMaxValSize());
        assertEquals("5010", supplier.getProtocolParams(1002).getMaxValSize());
        assertEquals(2, calls.get());
    }

    @Test
    void negativeSlotIsRejectedInsteadOfMappingToEpochZero() {
        EffectiveProtocolParamsSupplier supplier = new EffectiveProtocolParamsSupplier(
                provider(epoch -> Optional.empty()),
                new EpochSlotCalc(100, 10, 0));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> supplier.getProtocolParams(-1));

        assertEquals("Effective protocol parameters require a non-negative slot; got -1", error.getMessage());
    }

    private static LedgerStateProvider provider(Function<Integer, Optional<LedgerStateProvider.ProtocolParamsSnapshot>> snapshots) {
        return (LedgerStateProvider) Proxy.newProxyInstance(
                LedgerStateProvider.class.getClassLoader(),
                new Class<?>[]{LedgerStateProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocolParameters" -> snapshots.apply((Integer) args[0]);
                    case "toString" -> "TestLedgerStateProvider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
