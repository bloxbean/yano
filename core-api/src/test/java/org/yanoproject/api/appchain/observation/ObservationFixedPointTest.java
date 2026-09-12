package org.yanoproject.api.appchain.observation;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationFixedPointTest {
    @Test
    void canonicalSignedBoundaryRoundTrips() {
        for (BigInteger units : new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(),
                BigInteger.ONE.shiftLeft(127).negate(), BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)}) {
            for (int scale = 0; scale <= ObservationFixedPoint.MAX_SCALE; scale++) {
                ObservationFixedPoint value = new ObservationFixedPoint(units, scale);
                assertThat(value.encode()).hasSize(18);
                assertThat(ObservationFixedPoint.decode(value.encode())).isEqualTo(value);
            }
        }
    }

    @Test
    void strictDecimalParsingNeverRoundsOrUsesFloatingPoint() {
        assertThat(ObservationFixedPoint.parse("0.123456", 6).units()).isEqualTo(BigInteger.valueOf(123456));
        assertThat(ObservationFixedPoint.parse("-12.50", 6).units()).isEqualTo(BigInteger.valueOf(-12500000));
        for (String invalid : new String[]{"", " 1", "1 ", "+1", "01", "1e2", "NaN", "Infinity",
                ".5", "1.", "-0", "-0.0", "1,2", "1.2345678", "9".repeat(64)}) {
            assertThatThrownBy(() -> ObservationFixedPoint.parse(invalid, 6))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ObservationFixedPoint(BigInteger.ONE.shiftLeft(127), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationFixedPoint(BigInteger.ONE.shiftLeft(127).negate()
                .subtract(BigInteger.ONE), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationFixedPoint.decode(new byte[19]))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] badScale = new ObservationFixedPoint(BigInteger.ZERO, 0).encode();
        badScale[1] = 19;
        assertThatThrownBy(() -> ObservationFixedPoint.decode(badScale)).isInstanceOf(IllegalArgumentException.class);
    }
}
