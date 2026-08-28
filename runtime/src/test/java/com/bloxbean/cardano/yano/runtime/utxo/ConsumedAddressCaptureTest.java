package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumedAddressCaptureTest {

    @Test
    void disabledCaptureIsAllocationFreeAcrossBlocks() {
        ConsumedAddressCapture first = ConsumedAddressCapture.create(false);
        ConsumedAddressCapture second = ConsumedAddressCapture.create(false);

        assertThat(first).isSameAs(second);
        assertThat(first.view()).isSameAs(ConsumedOutputAddresses.NONE);
    }

    @Test
    void enabledCaptureIsIsolatedPerBlock() {
        assertThat(ConsumedAddressCapture.create(true))
                .isNotSameAs(ConsumedAddressCapture.create(true));
    }

    @Test
    void createdOutputCanBeResolvedWhenSpentLaterInTheSameBlock() {
        ConsumedAddressCapture capture = ConsumedAddressCapture.create(true);
        capture.recordCreated("aa", 3, "addr_test1_intra_block");

        assertThat(capture.view().addressOf("aa", 3)).isEqualTo("addr_test1_intra_block");
    }
}
