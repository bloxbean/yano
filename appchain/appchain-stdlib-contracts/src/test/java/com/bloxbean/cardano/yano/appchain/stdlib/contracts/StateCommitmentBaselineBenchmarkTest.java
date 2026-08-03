package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateCommitmentBaselineBenchmarkTest {
    @Test
    void smokeWorkloadProducesVerifiedDistinctProfileRootsAndMeasurements() {
        StateCommitmentBaselineBenchmark.BenchmarkReport report =
                StateCommitmentBaselineBenchmark.run(1_000, 100);

        assertThat(report.mpf().rootHex()).hasSize(64);
        assertThat(report.jmt().rootHex()).hasSize(64).isNotEqualTo(report.mpf().rootHex());
        assertThat(report.mpf().operationsPerSecond()).isPositive();
        assertThat(report.jmt().operationsPerSecond()).isPositive();
        assertThat(report.toJson()).contains("jmt-poseidon-bls12381-v1", "deferred");
    }
}
