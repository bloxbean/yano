package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Atomically persists bounded, sanitized scenario reports. */
public final class ReportStore {
    public static final String LATEST_FILE = "latest.json";
    public static final int MAX_REPORT_BYTES = 1_048_576;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path directory;

    public ReportStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path write(ScenarioReport report) {
        try {
            byte[] encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            if (encoded.length == 0 || encoded.length > MAX_REPORT_BYTES) {
                throw new DemoException(DemoError.REPORT_WRITE_FAILED);
            }
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new DemoException(DemoError.REPORT_WRITE_FAILED);
            }
            Path versioned = directory.resolve("report-" + safeId(report.scenarioId()) + ".json");
            atomicWrite(versioned, encoded);
            atomicWrite(directory.resolve(LATEST_FILE), encoded);
            return versioned;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
    }

    public byte[] readLatest() {
        Path latest = directory.resolve(LATEST_FILE);
        try {
            return BoundedFiles.read(latest, MAX_REPORT_BYTES, true, false);
        } catch (IOException failure) {
            return null;
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
        Path temp = Files.createTempFile(target.getParent(), ".report-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.setPosixFilePermissions(temp, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX hosts use their native default ACL.
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
        return value;
    }
}
