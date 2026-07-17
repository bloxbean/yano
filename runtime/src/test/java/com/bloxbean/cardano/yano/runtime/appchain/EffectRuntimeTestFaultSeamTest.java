package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EffectRuntimeTestFaultSeamTest {
    private static final List<String> PROPERTIES = List.of(
            EffectRuntimeTestFaultSeam.TEST_MODE_PROPERTY,
            EffectRuntimeTestFaultSeam.FAULT_MODE_PROPERTY,
            EffectRuntimeTestFaultSeam.EFFECT_TYPE_PROPERTY,
            EffectRuntimeTestFaultSeam.DIRECTORY_PROPERTY,
            EffectRuntimeTestFaultSeam.TIMEOUT_SECONDS_PROPERTY);

    private final Map<String, String> original = new LinkedHashMap<>();

    @BeforeEach
    void rememberAndClearProperties() {
        PROPERTIES.forEach(name -> {
            original.put(name, System.getProperty(name));
            System.clearProperty(name);
        });
    }

    @AfterEach
    void restoreProperties() {
        PROPERTIES.forEach(name -> {
            String value = original.get(name);
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void isInertWithoutTheDoubleOptIn(@TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        EffectRuntimeTestFaultSeam seam = EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test"));

        seam.afterConfirmedBeforePersistence("secret-chain", "secret-owner", effect(),
                EffectExecution.confirmed(new byte[]{1, 2, 3}));

        assertThat(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE))
                .doesNotExist();
    }

    @Test
    void globalTestModeAloneIsInert(@TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        System.setProperty(EffectRuntimeTestFaultSeam.TEST_MODE_PROPERTY, "true");
        EffectRuntimeTestFaultSeam seam = EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test"));

        seam.afterConfirmedBeforePersistence("chain", "owner", effect(),
                EffectExecution.confirmed(new byte[]{1, 2, 3}));

        assertThat(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE))
                .doesNotExist();
    }

    @Test
    void rejectsAConfiguredFaultWithoutGlobalTestMode(@TempDir Path temporary)
            throws Exception {
        configure(privateDirectory(temporary));
        System.clearProperty(EffectRuntimeTestFaultSeam.TEST_MODE_PROPERTY);

        assertThatThrownBy(() -> EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EffectRuntimeTestFaultSeam.TEST_MODE_PROPERTY);
    }

    @Test
    void rejectsNonPrivateSignalDirectory(@TempDir Path temporary) throws Exception {
        Path directory = temporary.toRealPath();
        Files.setPosixFilePermissions(directory,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        configure(directory);

        assertThatThrownBy(() -> EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode 0700");
    }

    @Test
    void rejectsAStaleReleaseMarkerWhenArming(@TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        Files.createFile(directory.resolve(EffectRuntimeTestFaultSeam.RELEASE_FILE),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        configure(directory);

        assertThatThrownBy(() -> EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale marker");
    }

    @Test
    void rejectsAStaleTemporarySignalWhenArming(@TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        Files.createFile(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_TEMP_FILE),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        configure(directory);

        assertThatThrownBy(() -> EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale marker");
    }

    @Test
    void signalsOnlyAfterMatchingConfirmationAndWaitsForPrivateRelease(
            @TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        configure(directory);
        EffectRuntimeTestFaultSeam seam = EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test"));

        seam.afterConfirmedBeforePersistence("secret-chain", "secret-owner", otherEffect(),
                EffectExecution.confirmed(new byte[]{9}));
        assertThat(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE))
                .doesNotExist();

        CompletableFuture<Void> paused = CompletableFuture.runAsync(() ->
                seam.afterConfirmedBeforePersistence(
                        "secret-chain", "secret-owner", effect(),
                        EffectExecution.confirmed(new byte[]{1, 2, 3})));
        Path signal = directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(signal, LinkOption.NOFOLLOW_LINKS)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(signal).isRegularFile();
        assertThat(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_TEMP_FILE))
                .doesNotExist();
        assertThat(Files.getPosixFilePermissions(signal))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(signal))
                .contains("schemaVersion=1\n")
                .contains("type=kafka.publish\n")
                .contains("height=7\n")
                .contains("ordinal=2\n")
                .contains("effectIdHash=")
                .contains("externalRefSha256=")
                .doesNotContain("secret-chain")
                .doesNotContain("secret-owner");
        assertThat(paused).isNotDone();

        Files.createFile(directory.resolve(EffectRuntimeTestFaultSeam.RELEASE_FILE),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        paused.get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsAnUnsafeReleaseFileImmediately(@TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        configure(directory);
        EffectRuntimeTestFaultSeam seam = EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test"));

        CompletableFuture<Void> paused = CompletableFuture.runAsync(() ->
                seam.afterConfirmedBeforePersistence(
                        "chain", "owner", effect(),
                        EffectExecution.confirmed(new byte[]{1, 2, 3})));
        Path signal = directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(signal, LinkOption.NOFOLLOW_LINKS)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Files.createFile(directory.resolve(EffectRuntimeTestFaultSeam.RELEASE_FILE),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-r--r--")));

        assertThatThrownBy(() -> paused.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("effect-runtime test fault release file is unsafe");
    }

    @Test
    void interruptionEndsThePausedWorkerWithoutPersistingAnOutcome(
            @TempDir Path temporary) throws Exception {
        Path directory = privateDirectory(temporary);
        configure(directory);
        EffectRuntimeTestFaultSeam seam = EffectRuntimeTestFaultSeam.fromSystemProperties(
                LoggerFactory.getLogger("effect-fault-test"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                seam.afterConfirmedBeforePersistence(
                        "chain", "owner", effect(),
                        EffectExecution.confirmed(new byte[]{1, 2, 3}));
            } catch (Throwable interrupted) {
                failure.set(interrupted);
            }
        }, "effect-fault-interruption-test");
        worker.start();

        Path signal = directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_FILE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(signal, LinkOption.NOFOLLOW_LINKS)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(signal).isRegularFile();
        assertThat(directory.resolve(EffectRuntimeTestFaultSeam.SIGNAL_TEMP_FILE))
                .doesNotExist();
        assertThat(Files.readString(signal))
                .startsWith("schemaVersion=1\n")
                .contains("effectIdHash=")
                .contains("externalRefSha256=");
        while (worker.getState() != Thread.State.TIMED_WAITING
                && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(worker.getState()).isEqualTo(Thread.State.TIMED_WAITING);

        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("effect-runtime test fault pause interrupted")
                .hasCauseInstanceOf(InterruptedException.class);
    }

    private static Path privateDirectory(Path path) throws Exception {
        Path real = path.toRealPath();
        Files.setPosixFilePermissions(real,
                PosixFilePermissions.fromString("rwx------"));
        return real;
    }

    private static void configure(Path directory) {
        System.setProperty(EffectRuntimeTestFaultSeam.TEST_MODE_PROPERTY, "true");
        System.setProperty(EffectRuntimeTestFaultSeam.FAULT_MODE_PROPERTY,
                EffectRuntimeTestFaultSeam.MODE_V1);
        System.setProperty(EffectRuntimeTestFaultSeam.EFFECT_TYPE_PROPERTY,
                "kafka.publish");
        System.setProperty(EffectRuntimeTestFaultSeam.DIRECTORY_PROPERTY,
                directory.toString());
        System.setProperty(EffectRuntimeTestFaultSeam.TIMEOUT_SECONDS_PROPERTY, "5");
    }

    private static EffectRecord effect() {
        return new EffectRecord(EffectRecord.RECORD_VERSION, "chain", 7, 2,
                "kafka.publish", new byte[]{1}, "scope", FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN, 10_000, null);
    }

    private static EffectRecord otherEffect() {
        return new EffectRecord(EffectRecord.RECORD_VERSION, "chain", 7, 1,
                "object.put", new byte[]{1}, "scope", FinalityGate.APP_FINAL,
                ResultPolicy.CHAIN, 10_000, null);
    }
}
