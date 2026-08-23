package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockBodySizeLimitSupplierTest {

    @Test
    void resolvesMaxBlockSizeForEpochContainingTheProducedSlot() {
        AtomicLong requestedEpoch = new AtomicLong(-1);
        EpochParamProvider params = params(requestedEpoch, 91_000);
        BlockBodySizeLimitSupplier supplier = BlockBodySizeLimitSupplier.fromEpochParams(() -> params);

        BlockProductionLimits limits = supplier.getLimits(250);
        assertThat(limits.maxBodyBytes()).isEqualTo(91_000);
        assertThat(limits.maxExecutionMemory()).isEqualTo(BigInteger.valueOf(72_000_000));
        assertThat(limits.maxExecutionSteps()).isEqualTo(BigInteger.valueOf(20_000_000_000L));
        assertThat(requestedEpoch).hasValue(2);
    }

    @Test
    void failsClosedWhenEffectiveMaxBlockSizeIsUnavailable() {
        EpochParamProvider params = params(new AtomicLong(), null);
        BlockBodySizeLimitSupplier supplier = BlockBodySizeLimitSupplier.fromEpochParams(() -> params);

        assertThatThrownBy(() -> supplier.getMaxBlockBodySize(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable or invalid for epoch 0");
    }

    private static EpochParamProvider params(AtomicLong requestedEpoch, Integer maxBlockSize) {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public Integer getMaxBlockSize(long epoch) {
                requestedEpoch.set(epoch);
                return maxBlockSize;
            }

            @Override
            public BigInteger getMaxBlockExMem(long epoch) {
                return BigInteger.valueOf(72_000_000);
            }

            @Override
            public BigInteger getMaxBlockExSteps(long epoch) {
                return BigInteger.valueOf(20_000_000_000L);
            }

            @Override
            public long getEpochLength() {
                return 100;
            }
        };
    }
}
