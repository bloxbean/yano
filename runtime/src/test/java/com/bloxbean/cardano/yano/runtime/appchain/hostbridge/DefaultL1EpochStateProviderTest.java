package com.bloxbean.cardano.yano.runtime.appchain.hostbridge;

import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultL1EpochStateProviderTest {

    @Test
    void derivesTheFirstObservableEpochFromNetworkEraConfiguration() {
        assertThat(DefaultL1EpochStateProvider.firstObservableEpoch(
                new EpochSlotCalc(432_000, 21_600, 4_492_800))).isEqualTo(208);
        assertThat(DefaultL1EpochStateProvider.firstObservableEpoch(
                new EpochSlotCalc(432_000, 21_600, 86_400))).isEqualTo(4);
        assertThat(DefaultL1EpochStateProvider.firstObservableEpoch(
                new EpochSlotCalc(86_400, 21_600, 0))).isEqualTo(1);
    }

    @Test
    void completedBoundaryScanDoesNotEnumerateByronEpochs() {
        assertThat(DefaultL1EpochStateProvider.firstBoundaryToScan(
                -1, 4, 50, 4)).isEqualTo(4);
        assertThat(DefaultL1EpochStateProvider.firstBoundaryToScan(
                -1, 211, 50, 208)).isEqualTo(208);
        assertThat(DefaultL1EpochStateProvider.firstBoundaryToScan(
                -1, 311, 50, 4)).isEqualTo(262);
    }
}
