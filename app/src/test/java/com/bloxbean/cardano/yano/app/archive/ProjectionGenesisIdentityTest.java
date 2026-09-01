package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionGenesisIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void localProducerIdentityIgnoresOnlySystemStart() throws Exception {
        Path genesis = writeGenesis("2026-01-01T00:00:00Z", 100);
        YanoConfig config = config(genesis, true, true);
        String initial = ProjectionGenesisIdentity.resolve(config);

        writeGenesis("2026-09-01T04:34:47Z", 100);
        assertThat(ProjectionGenesisIdentity.resolve(config)).isEqualTo(initial);

        writeGenesis("2026-09-01T04:34:47Z", 101);
        assertThat(ProjectionGenesisIdentity.resolve(config)).isNotEqualTo(initial);
    }

    @Test
    void publicNetworkIdentityIncludesSystemStart() throws Exception {
        Path genesis = writeGenesis("2026-01-01T00:00:00Z", 100);
        YanoConfig config = config(genesis, false, false);
        String initial = ProjectionGenesisIdentity.resolve(config);

        writeGenesis("2026-09-01T04:34:47Z", 100);
        assertThat(ProjectionGenesisIdentity.resolve(config)).isNotEqualTo(initial);
    }

    @Test
    void configuredHashTakesPrecedence() {
        YanoConfig config = YanoConfig.builder()
                .shelleyGenesisHash("ABCDEF")
                .shelleyGenesisFile(tempDir.resolve("missing.json").toString())
                .build();

        assertThat(ProjectionGenesisIdentity.resolve(config)).isEqualTo("abcdef");
    }

    private Path writeGenesis(String systemStart, int securityParam) throws Exception {
        Path genesis = tempDir.resolve("shelley-genesis.json");
        Files.writeString(genesis, """
                {
                  "networkMagic": 42,
                  "systemStart": "%s",
                  "securityParam": %d
                }
                """.formatted(systemStart, securityParam));
        return genesis;
    }

    private static YanoConfig config(Path genesis, boolean devMode, boolean blockProducer) {
        return YanoConfig.builder()
                .shelleyGenesisFile(genesis.toString())
                .devMode(devMode)
                .enableBlockProducer(blockProducer)
                .build();
    }
}
