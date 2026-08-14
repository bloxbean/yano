package com.bloxbean.cardano.yano.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemVersionTest {

    @Test
    void parsesStrictSemVerValues() {
        List.of(
                "0.0.0",
                "1.0.0",
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-0.3.7",
                "1.0.0-x.7.z.92",
                "1.0.0-x-y-z.--",
                "1.0.0+20130313144700",
                "1.0.0-beta+exp.sha.5114f85"
        ).forEach(value -> assertThat(SemVersion.parse(value).toString()).isEqualTo(value));
    }

    @Test
    void rejectsValuesOutsideSemVerGrammar() {
        List<String> invalid = List.of(
                "",
                "1",
                "1.0",
                "01.0.0",
                "1.01.0",
                "1.0.01",
                "1.0.0-01",
                "1.0.0-alpha..1",
                "1.0.0-",
                "1.0.0+",
                "v1.0.0",
                "1.0.0 alpha",
                "1.0.0-\u00e9"
        );
        invalid.forEach(value -> assertThatThrownBy(() -> SemVersion.parse(value))
                .as("invalid SemVer %s", value)
                .isInstanceOf(IllegalArgumentException.class));
        assertThatThrownBy(() -> SemVersion.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVersion.parse("1.0.0+" + "a".repeat(123)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void followsOfficialPrecedenceOrdering() {
        List<SemVersion> versions = List.of(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0"
        ).stream().map(SemVersion::parse).toList();

        for (int i = 0; i < versions.size() - 1; i++) {
            assertThat(versions.get(i)).isLessThan(versions.get(i + 1));
        }
        assertThat(SemVersion.parse("999999999999999999999.0.0"))
                .isGreaterThan(SemVersion.parse("99999999999999999999.999.999"));
    }

    @Test
    void ignoresBuildMetadataForPrecedence() {
        assertThat(SemVersion.parse("1.2.3+build.1").compareTo(SemVersion.parse("1.2.3+build.2")))
                .isZero();
        assertThat(SemVersion.parse("1.2.3-alpha+build.9")
                .compareTo(SemVersion.parse("1.2.3-alpha+build.10")))
                .isZero();
    }
}
