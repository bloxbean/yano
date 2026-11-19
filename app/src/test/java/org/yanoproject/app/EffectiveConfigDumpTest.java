package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.yanoproject.app.configdump.EffectiveConfigDump;

import java.nio.file.Path;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064 D8: records the configuration Yano's own application inherits from its artifacts. The
 * downstream-consumer check compares its own dump with this one ({@code :app:effectiveConfigDump}).
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class EffectiveConfigDumpTest {

    static final String DUMP_PROPERTY = "yano.effective-config.dump";

    @Test
    void dumpsTheInheritedConfiguration() throws Exception {
        SortedMap<String, String> dump = EffectiveConfigDump.artifactDefaults();

        assertThat(dump).containsEntry("yano.api-prefix", "/api/v1")
                .containsKey("yano.storage.path")
                .containsKey("quarkus.http.port")
                .doesNotContainKeys(EffectiveConfigDump.APPLICATION_ONLY_KEYS.toArray(String[]::new));
        String target = System.getProperty(DUMP_PROPERTY);
        if (target != null) {
            EffectiveConfigDump.write(Path.of(target), dump);
        }
    }
}
