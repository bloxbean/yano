package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Deliberately awkward, double-opt-in test seam for the at-least-once crash
 * window after a connector has acknowledged an operation but before the
 * node-local execution result is persisted.
 *
 * <p>This is not a supported runtime feature. It is inert unless both the
 * global {@value #TEST_MODE_PROPERTY} switch and the exact versioned
 * {@value #FAULT_MODE_PROPERTY} switch are supplied as JVM system properties.
 * The signal directory must already be a canonical owner-only {@code 0700}
 * directory. No Yano/connector configuration key, ordinary environment
 * variable, plugin payload, or network request can activate it. A process
 * owner can deliberately inject these JVM properties (including through a
 * launcher such as {@code JAVA_OPTS}); that authority already controls the
 * JVM and is the mechanism used by the isolated E2E harness.</p>
 */
final class EffectRuntimeTestFaultSeam {
    static final String TEST_MODE_PROPERTY = "yano.test.enabled";
    static final String FAULT_MODE_PROPERTY =
            "yano.test.effect-runtime.post-confirmed-pause";
    static final String EFFECT_TYPE_PROPERTY =
            "yano.test.effect-runtime.post-confirmed-pause.type";
    static final String DIRECTORY_PROPERTY =
            "yano.test.effect-runtime.post-confirmed-pause.directory";
    static final String TIMEOUT_SECONDS_PROPERTY =
            "yano.test.effect-runtime.post-confirmed-pause.timeout-seconds";
    static final String MODE_V1 = "v1";
    static final String SIGNAL_FILE = "acknowledged-before-result-v1";
    static final String SIGNAL_TEMP_FILE = ".acknowledged-before-result-v1.tmp";
    static final String RELEASE_FILE = "release-v1";

    private static final Pattern EFFECT_TYPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;
    private static final long MAX_TIMEOUT_SECONDS = 900;
    private static final Set<String> FAULT_PROPERTIES = Set.of(
            FAULT_MODE_PROPERTY, EFFECT_TYPE_PROPERTY, DIRECTORY_PROPERTY,
            TIMEOUT_SECONDS_PROPERTY);
    private static final EffectRuntimeTestFaultSeam DISABLED =
            new EffectRuntimeTestFaultSeam(null, null, Duration.ZERO, null);

    private final String effectType;
    private final Path directory;
    private final Duration timeout;
    private final Logger log;
    private final AtomicBoolean fired = new AtomicBoolean();

    private EffectRuntimeTestFaultSeam(String effectType, Path directory,
                                       Duration timeout, Logger log) {
        this.effectType = effectType;
        this.directory = directory;
        this.timeout = timeout;
        this.log = log;
    }

    static EffectRuntimeTestFaultSeam fromSystemProperties(Logger log) {
        String mode = System.getProperty(FAULT_MODE_PROPERTY);
        boolean anyFaultProperty = FAULT_PROPERTIES.stream()
                .anyMatch(name -> System.getProperty(name) != null);
        if (!anyFaultProperty) {
            return DISABLED;
        }
        if (!Boolean.parseBoolean(System.getProperty(TEST_MODE_PROPERTY, "false"))) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault seam requires -D" + TEST_MODE_PROPERTY + "=true");
        }
        if (!MODE_V1.equals(mode)) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault seam mode must be exactly " + MODE_V1);
        }

        String type = requiredProperty(EFFECT_TYPE_PROPERTY);
        if (!EFFECT_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault type is malformed");
        }
        Path directory = privateDirectory(requiredProperty(DIRECTORY_PROPERTY));
        long timeoutSeconds = timeoutSeconds(System.getProperty(
                TIMEOUT_SECONDS_PROPERTY, Long.toString(DEFAULT_TIMEOUT_SECONDS)));
        log.warn("TEST-ONLY effect-runtime post-confirmed pause armed for type '{}' in {}",
                type, directory);
        return new EffectRuntimeTestFaultSeam(
                type, directory, Duration.ofSeconds(timeoutSeconds), log);
    }

    void afterConfirmedBeforePersistence(String chainId, String runtimeOwner,
                                         EffectRecord record, EffectExecution outcome) {
        if (directory == null || !(outcome instanceof EffectExecution.Confirmed confirmed)
                || !effectType.equals(record.type()) || !fired.compareAndSet(false, true)) {
            return;
        }

        Path signal = directory.resolve(SIGNAL_FILE);
        Path release = directory.resolve(RELEASE_FILE);
        byte[] signalBody = signalBody(chainId, runtimeOwner, record,
                confirmed.externalRef());
        createPrivateSignal(signal, signalBody);
        log.warn("TEST-ONLY effect-runtime paused after external acknowledgement for {}/{}; "
                        + "kill the process or create {} to release it",
                record.height(), record.ordinal(), release);

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (privateReleasePresent(release)) {
                log.warn("TEST-ONLY effect-runtime post-confirmed pause released for {}/{}",
                        record.height(), record.ordinal());
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "effect-runtime test fault pause interrupted", interrupted);
            }
        }
        throw new IllegalStateException("effect-runtime test fault pause timed out");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault property is required: " + name);
        }
        return value;
    }

    private static long timeoutSeconds(String value) {
        if (!value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault timeout must be a positive integer");
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException(
                        "effect-runtime test fault timeout exceeds " + MAX_TIMEOUT_SECONDS);
            }
            return seconds;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault timeout is malformed", failure);
        }
    }

    private static Path privateDirectory(String value) {
        try {
            Path requested = Path.of(value);
            if (!requested.isAbsolute() || !requested.equals(requested.normalize())
                    || Files.isSymbolicLink(requested)
                    || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "effect-runtime test fault directory must be an existing canonical directory");
            }
            Path real = requested.toRealPath();
            if (!real.equals(requested)) {
                throw new IllegalArgumentException(
                        "effect-runtime test fault directory must not traverse symbolic links");
            }
            if (!Files.getPosixFilePermissions(real)
                    .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IllegalArgumentException(
                        "effect-runtime test fault directory must have mode 0700");
            }
            if (!Files.notExists(real.resolve(SIGNAL_FILE), LinkOption.NOFOLLOW_LINKS)
                    || !Files.notExists(
                    real.resolve(SIGNAL_TEMP_FILE), LinkOption.NOFOLLOW_LINKS)
                    || !Files.notExists(real.resolve(RELEASE_FILE), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "effect-runtime test fault directory contains a stale marker");
            }
            return real;
        } catch (IOException | UnsupportedOperationException failure) {
            throw new IllegalArgumentException(
                    "effect-runtime test fault directory cannot be validated", failure);
        }
    }

    private static void createPrivateSignal(Path signal, byte[] content) {
        Path temporary = signal.resolveSibling(SIGNAL_TEMP_FILE);
        try (FileChannel channel = FileChannel.open(temporary,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "effect-runtime test fault signal could not be published", failure);
        }
        try {
            Files.move(temporary, signal, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException failure) {
            throw new IllegalStateException(
                    "effect-runtime test fault signal could not be published", failure);
        }
    }

    private static boolean privateReleasePresent(Path release) {
        if (!Files.exists(release, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(release)
                    || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(release) != 0
                    || !Files.getPosixFilePermissions(release)
                    .equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new IllegalStateException(
                        "effect-runtime test fault release file is unsafe");
            }
            return true;
        } catch (IOException | UnsupportedOperationException failure) {
            throw new IllegalStateException(
                    "effect-runtime test fault release file is unsafe", failure);
        }
    }

    private static byte[] signalBody(String chainId, String runtimeOwner,
                                     EffectRecord record, byte[] externalRef) {
        String body = "schemaVersion=1\n"
                + "chainIdSha256=" + sha256Hex(chainId.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "runtimeOwnerSha256="
                + sha256Hex(runtimeOwner.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "effectIdHash=" + record.effectId().hashHex() + "\n"
                + "type=" + record.type() + "\n"
                + "height=" + record.height() + "\n"
                + "ordinal=" + record.ordinal() + "\n"
                + "externalRefSha256=" + sha256Hex(externalRef) + "\n";
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
