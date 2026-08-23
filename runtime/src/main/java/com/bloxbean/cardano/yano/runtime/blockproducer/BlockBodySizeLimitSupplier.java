package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.EpochParamProvider;

import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies the effective maximum block-body size for the epoch containing a block slot.
 */
@FunctionalInterface
public interface BlockBodySizeLimitSupplier {

    long getMaxBlockBodySize(long slot);

    /**
     * Resolve every resource limit from one epoch-parameter view. Legacy lambda
     * implementations constrain body bytes only and leave execution units unbounded.
     */
    default BlockProductionLimits getLimits(long slot) {
        return new BlockProductionLimits(getMaxBlockBodySize(slot), null, null);
    }

    static BlockBodySizeLimitSupplier unbounded() {
        return slot -> Long.MAX_VALUE;
    }

    static BlockBodySizeLimitSupplier fromEpochParams(Supplier<EpochParamProvider> providerSupplier) {
        Objects.requireNonNull(providerSupplier, "providerSupplier must not be null");
        return new BlockBodySizeLimitSupplier() {
            @Override
            public long getMaxBlockBodySize(long slot) {
                return getLimits(slot).maxBodyBytes();
            }

            @Override
            public BlockProductionLimits getLimits(long slot) {
                if (slot < 0) {
                    throw new IllegalStateException(
                            "Effective block limits require a non-negative slot; got " + slot);
                }
                EpochParamProvider provider = Objects.requireNonNull(providerSupplier.get(),
                        "effective epoch parameter provider must not be null");
                int epoch = provider.getEpochSlotCalc().slotToEpoch(slot);
                Integer maxBlockSize = provider.getMaxBlockSize(epoch);
                BigInteger maxBlockExMem = provider.getMaxBlockExMem(epoch);
                BigInteger maxBlockExSteps = provider.getMaxBlockExSteps(epoch);
                if (maxBlockSize == null || maxBlockSize <= 0
                        || maxBlockExMem == null || maxBlockExMem.signum() <= 0
                        || maxBlockExSteps == null || maxBlockExSteps.signum() <= 0) {
                    throw new IllegalStateException(
                            "Effective block limits are unavailable or invalid for epoch " + epoch);
                }
                return new BlockProductionLimits(
                        maxBlockSize.longValue(), maxBlockExMem, maxBlockExSteps);
            }
        };
    }
}
