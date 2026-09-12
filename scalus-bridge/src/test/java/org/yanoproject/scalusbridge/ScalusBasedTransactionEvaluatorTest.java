package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledgerrules.SlotConfigSupplier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusBasedTransactionEvaluatorTest {

    private final SlotConfig slotConfig = new SlotConfig(1000, 0, 0);

    @Test
    void evaluatorConvertsBlstLinkageErrorToExplicitFailure() {
        var evaluator = new BlstFailingEvaluator();

        var ex = assertThrows(BlsBuiltinsUnavailableException.class,
                () -> evaluator.evaluate(new byte[]{1, 2, 3}, Set.of()));

        assertEquals(ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE, ex.getMessage());
        assertTrue(ex.getCause() instanceof NoClassDefFoundError);
    }

    @Test
    void runtimeEvaluatorRejectsNegativeCurrentSlot() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new AssertionError("protocol parameters should not be requested");
                },
                null, slotConfig, 0, () -> -1L);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("Failed to resolve current slot from runtime", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("current slot supplier returned -1"));
    }

    @Test
    void runtimeEvaluatorRejectsCurrentSlotSupplierFailure() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new AssertionError("protocol parameters should not be requested");
                },
                null, slotConfig, 0, () -> {
            throw new IllegalStateException("tip unavailable");
        });

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("Failed to resolve current slot from runtime", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("tip unavailable"));
    }

    @Test
    void runtimeEvaluatorPassesResolvedSlotToProtocolParamsSupplier() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new IllegalStateException("resolved slot " + slot);
                },
                null, slotConfig, 0, () -> 123L);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("resolved slot 123", ex.getMessage());
    }

    @Test
    void evaluatorWithoutRuntimeSupplierKeepsLegacySlotZero() {
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> {
                    throw new IllegalStateException("resolved slot " + slot);
                },
                null, slotConfig, 0, null);

        var ex = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("resolved slot 0", ex.getMessage());
    }

    @Test
    void runtimeEvaluatorUsesDynamicSlotConfigSupplierPerCall() {
        var zeroTime = new AtomicLong(1_780_000_000_000L);
        var evaluator = new ScalusBasedTransactionEvaluator(
                slot -> new ProtocolParams(),
                null,
                () -> {
                    throw new IllegalStateException("zeroTime " + zeroTime.get());
                },
                0,
                () -> 123L);

        var first = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));
        zeroTime.set(1_780_000_001_000L);
        var second = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(new byte[0], Set.of()));

        assertEquals("zeroTime 1780000000000", first.getMessage());
        assertEquals("zeroTime 1780000001000", second.getMessage());
    }

    @Test
    void scalusSlotConfigAdapterPreservesUnitsAndOrder() {
        var ccl = new SlotConfig(2_000, 42, 1_780_000_000_000L);

        var scalus = SlotConfigAdapters.toScalus(slotConfigSupplier(
                ccl, new EpochSlotCalc(1_200, 1_200, 0)));

        assertEquals(1_780_000_000_000L, scalus.zeroTime());
        assertEquals(42L, scalus.zeroSlot());
        assertEquals(2_000L, scalus.slotLength());
    }

    @Test
    void scalusSlotConfigAdapterPreservesDevnetEpochGeometry() {
        var supplier = new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return new SlotConfig(1_000, 0, 1_780_000_000_000L);
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return new EpochSlotCalc(1_200, 1_200, 0);
            }
        };

        var scalus = SlotConfigAdapters.toScalus(supplier);

        assertEquals(1_200L, scalus.epochLength());
        assertEquals(0L, scalus.zeroEpoch());
        assertEquals(55L, scalus.epochOf(66_166));
    }

    @Test
    void scalusSlotConfigAdapterPreservesMainnetEpochAndTimeOrigin() {
        var supplier = new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return new SlotConfig(1_000, 4_492_800, 1_596_059_091_000L);
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return new EpochSlotCalc(432_000, 21_600, 4_492_800);
            }
        };

        var scalus = SlotConfigAdapters.toScalus(supplier);

        assertEquals(208L, scalus.epochOf(4_492_800));
        assertEquals(550L, scalus.epochOf(152_236_800));
        assertEquals(1_596_059_091_000L, scalus.slotToTime(4_492_800));
    }

    @Test
    void scalusSlotConfigAdapterPreservesPreprodEpochGeometry() {
        var supplier = slotConfigSupplier(
                new SlotConfig(1_000, 86_400, 1_655_769_600_000L),
                new EpochSlotCalc(432_000, 21_600, 86_400));

        var scalus = SlotConfigAdapters.toScalus(supplier);

        assertEquals(432_000L, scalus.epochLength());
        assertEquals(4L, scalus.zeroEpoch());
        assertEquals(100L, scalus.epochOf(41_558_400));
    }

    @Test
    void scalusSlotConfigAdapterRejectsSupplierWithoutEpochGeometry() {
        SlotConfigSupplier supplier = () -> new SlotConfig(1_000, 0, 1_780_000_000_000L);

        var error = assertThrows(IllegalStateException.class, () -> SlotConfigAdapters.toScalus(supplier));

        assertEquals("Epoch slot configuration not available", error.getMessage());
    }

    private static SlotConfigSupplier slotConfigSupplier(SlotConfig slotConfig, EpochSlotCalc epochSlotCalc) {
        return new SlotConfigSupplier() {
            @Override
            public SlotConfig getSlotConfig() {
                return slotConfig;
            }

            @Override
            public EpochSlotCalc getEpochSlotCalc() {
                return epochSlotCalc;
            }
        };
    }

    private class BlstFailingEvaluator extends ScalusBasedTransactionEvaluator {
        BlstFailingEvaluator() {
            super(slot -> new ProtocolParams(), null,
                    slotConfigSupplier(slotConfig, new EpochSlotCalc(1_200, 1_200, 0)), 0, () -> 123L);
        }

        @Override
        protected Result<List<com.bloxbean.cardano.client.api.model.EvaluationResult>> evaluateWithScalus(
                scalus.cardano.ledger.SlotConfig scalusSlotConfig,
                ProtocolParams protocolParams,
                UtxoSupplier utxoSupplier,
                byte[] txCbor,
                Set<Utxo> inputUtxos) {
            throw new NoClassDefFoundError("Could not initialize class supranational.blst.blstJNI");
        }
    }
}
