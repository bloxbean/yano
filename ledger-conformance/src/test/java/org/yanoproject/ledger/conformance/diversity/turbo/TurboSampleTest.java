package org.yanoproject.ledger.conformance.diversity.turbo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurboSampleTest {

    @Test
    void protocolVersionFollowsTheEpoch() {
        assertThat(TurboSample.protocolMajor(506)).isEqualTo(8);
        assertThat(TurboSample.protocolMajor(507)).isEqualTo(9);
        assertThat(TurboSample.protocolMajor(536)).isEqualTo(9);
        assertThat(TurboSample.protocolMajor(537)).isEqualTo(10);
        assertThat(TurboSample.protocolMajor(643)).isEqualTo(10);
        assertThat(TurboSample.protocolMajor(644)).isEqualTo(11);
    }

    @Test
    void languageFollowsTheSuffix() {
        TurboSample sample = TurboSample.of(600, Path.of("600/ABC-0-DEF-v2.flat"));

        assertThat(sample.bench()).isEqualTo("ABC-0-DEF-v2.flat");
        assertThat(sample.plutusVersion()).isEqualTo("V2");
        assertThat(sample.protocolVersion()).isEqualTo("10.0");
        assertThatThrownBy(() -> TurboSample.of(600, Path.of("600/ABC.flat")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void meanOfTheBestThirty() {
        long[] values = new long[50];
        for (int i = 0; i < values.length; i++) {
            values[i] = 50 - i;
        }
        // the 30 smallest are 1..30, mean 15.5, rounded up
        assertThat(TurboMeasurement.meanOfBest(values)).isEqualTo(16);
    }
}
