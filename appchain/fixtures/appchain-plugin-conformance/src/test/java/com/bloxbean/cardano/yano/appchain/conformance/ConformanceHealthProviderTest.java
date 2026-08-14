package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ConformanceHealthProviderTest {

    @AfterEach
    void clearSelector() {
        System.clearProperty(ConformanceHealthProvider.MODE_PROPERTY);
        System.clearProperty(ConformanceHealthProvider.HANG_MARKER_PROPERTY);
    }

    @Test
    void defaultsToUpAndSelectsOnlyBoundedHealthStatuses() {
        assertThat(ConformanceHealthProvider.configuredMode())
                .isEqualTo(ConformanceHealthProvider.HealthMode.UP);

        for (var expected : List.of(
                PluginHealthStatus.UP,
                PluginHealthStatus.DEGRADED,
                PluginHealthStatus.DOWN)) {
            System.setProperty(ConformanceHealthProvider.MODE_PROPERTY, expected.name());
            var mode = ConformanceHealthProvider.configuredMode();
            var report = ConformanceHealthProvider.snapshotFor(mode).reports().getFirst();

            assertThat(report.checkId()).isEqualTo(ConformanceHealthProvider.CHECK_ID);
            assertThat(report.status()).isEqualTo(expected);
            assertThat(new ConformanceHealthProvider()).isInstanceOf(PluginHealthProvider.class);
        }
    }

    @Test
    void invalidSelectorIsRejectedWithoutEchoingItsValue() {
        String secret = "operator-secret-selector";
        System.setProperty(ConformanceHealthProvider.MODE_PROPERTY, secret);

        assertThatThrownBy(ConformanceHealthProvider::configuredMode)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported conformance health mode")
                .hasMessageNotContaining(secret);
    }

    @Test
    void hangModeEntersSnapshotAndIgnoresInterruptionOnlyInAFork(
            @TempDir Path temporary
    ) throws Exception {
        Path marker = temporary.resolve("hang-entered");
        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ConformanceHealthProvider.MODE_PROPERTY + "=HANG",
                "-D" + ConformanceHealthProvider.HANG_MARKER_PROPERTY + "=" + marker,
                "-cp", forkClasspath(),
                ConformanceHealthHangProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && process.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            assertThat(Files.exists(marker))
                    .as("forked HANG snapshot entered")
                    .isTrue();
            assertThat(Files.readString(marker)).isEqualTo("entered\n");
            Thread.sleep(250);
            assertThat(process.isAlive()).isTrue();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    private static String forkClasspath() throws URISyntaxException {
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> type : List.of(
                ConformanceHealthHangProbe.class,
                ConformanceHealthProvider.class,
                PluginHealthProvider.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toString());
        }
        return String.join(File.pathSeparator, entries);
    }
}
