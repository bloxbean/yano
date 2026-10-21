package org.yanoproject.ledger.rules.view;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookupTest {

    @Test
    void presentAbsentAndUnavailableAreDistinct() {
        Lookup<String> present = Lookup.present("v");
        Lookup<String> absent = Lookup.absent();
        Lookup<String> unavailable = Lookup.unavailable("store down");

        assertThat(present.isPresent()).isTrue();
        assertThat(present.isAbsent()).isFalse();
        assertThat(absent.isAbsent()).isTrue();
        assertThat(absent.isPresent()).isFalse();
        assertThat(unavailable.isUnavailable()).isTrue();
        assertThat(unavailable.isAbsent()).isFalse();
    }

    @Test
    void ofNullableMapsNullToAbsent() {
        assertThat(Lookup.ofNullable(null)).isInstanceOf(Lookup.Absent.class);
        assertThat(Lookup.ofNullable(1)).isEqualTo(Lookup.present(1));
    }

    @Test
    void orElseThrowUnavailableKeepsAbsenceAsData() {
        assertThat(Lookup.present("v").orElseThrowUnavailable()).contains("v");
        assertThat(Lookup.<String>absent().orElseThrowUnavailable()).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> Lookup.unavailable("snapshot released").orElseThrowUnavailable())
                .isInstanceOf(LedgerStateUnavailableException.class)
                .hasMessageContaining("snapshot released");
    }

    @Test
    void requireRejectsAbsenceAndUnavailability() {
        assertThat(Lookup.present(5).require("x")).isEqualTo(5);
        assertThatThrownBy(() -> Lookup.absent().require("account"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account");
        assertThatThrownBy(() -> Lookup.unavailable("down").require("account"))
                .isInstanceOf(LedgerStateUnavailableException.class);
    }

    @Test
    void mapPreservesAbsentAndUnavailable() {
        assertThat(Lookup.present(2).map(i -> i * 10)).isEqualTo(Lookup.present(20));
        assertThat(Lookup.<Integer>absent().map(i -> i * 10).isAbsent()).isTrue();
        Lookup<Integer> mapped = Lookup.<Integer>unavailable("r").map(i -> i * 10);
        assertThat(mapped).isEqualTo(Lookup.unavailable("r"));
        assertThat(Lookup.present(2).map(i -> null).isAbsent()).isTrue();
    }

    @Test
    void presentRejectsNull() {
        assertThatThrownBy(() -> Lookup.present(null)).isInstanceOf(NullPointerException.class);
    }
}
