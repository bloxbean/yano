package org.yanoproject.ledger.conformance.diversity.uplc;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UplcVectorTest {

    @Test
    void labelsAVectorAsTheExpectationsDo() {
        Path relative = Path.of("protocol-version-11/v3/builtin/semantics/dropList/dropList-09/dropList-09.uplc");
        UplcVector vector = UplcVector.of(relative, Path.of("/corpus").resolve(relative));

        assertThat(vector.id()).isEqualTo("builtin_semantics_droplist_droplist_09_v3");
        assertThat(vector.title()).isEqualTo("droplist 09");
        assertThat(vector.group()).isEqualTo("builtin/semantics/dropList");
        assertThat(vector.plutusVersion()).isEqualTo("V3");
        assertThat(vector.protocolVersion()).isEqualTo("11.0");
        assertThat(vector.expected().getFileName()).hasToString("dropList-09.uplc.expected");
        assertThat(vector.flat().getFileName()).hasToString("dropList-09.flat");
    }
}
