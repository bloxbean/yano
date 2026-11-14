package org.yanoproject.api.host;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HostApiVersionTest {
    @Test
    void supportsOlderLevelsWithinOneMajorWithoutMatchingProductVersions() {
        assertThatCode(() -> new HostApiVersion(1, 2).requireCompatible(1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> HostApiVersion.CURRENT.requireCompatible(1, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new HostApiVersion(1, 1).requireCompatible(1, 2))
                .hasMessageContaining("requires Yano Host API 1.2");
        assertThatThrownBy(() -> new HostApiVersion(2, 1).requireCompatible(1, 1))
                .hasMessageContaining("host provides 2.1");
    }
}
