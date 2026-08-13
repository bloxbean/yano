package com.bloxbean.cardano.yano.archive.ducklake;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Extracts checksum-verified build resources and LOADs them by absolute path. */
public final class PackagedDuckDbExtensionLoader implements DuckDbExtensionLoader {
    public static final String DUCKDB_VERSION = "1.5.5";
    private static final List<String> EXTENSIONS = List.of("ducklake", "sqlite_scanner");

    private final Path extractionDirectory;

    public PackagedDuckDbExtensionLoader(Path extractionDirectory) {
        this.extractionDirectory = Objects.requireNonNull(extractionDirectory, "extractionDirectory")
                .toAbsolutePath().normalize();
    }

    @Override
    public void load(Connection connection) throws SQLException {
        String engineVersion = querySingle(connection, "SELECT version()");
        String enginePlatform = querySingle(connection, "PRAGMA platform");
        if (!engineVersion.equals("v" + DUCKDB_VERSION) || !enginePlatform.equals(platform())) {
            throw new SQLException("packaged DuckDB extension mismatch: engine=" + engineVersion
                    + '/' + enginePlatform + ", bundle=v" + DUCKDB_VERSION + '/' + platform());
        }
        try {
            Files.createDirectories(extractionDirectory);
            for (String extension : EXTENSIONS) {
                Path binary = extract(extension);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOAD '" + sqlString(binary.toString()) + "'");
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new SQLException("failed to load packaged DuckDB extensions", e);
        }
    }

    private String querySingle(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("DuckDB diagnostic query returned no row: " + sql);
            return result.getString(1);
        }
    }

    private Path extract(String extension) throws IOException, NoSuchAlgorithmException {
        String platform = platform();
        String root = "/duckdb-extensions/" + DUCKDB_VERSION + "/" + platform + "/";
        byte[] expected = HexFormat.of().parseHex(readText(root + extension + ".sha256").trim());
        Path target = extractionDirectory.resolve(DUCKDB_VERSION).resolve(platform)
                .resolve(extension + ".duckdb_extension");
        if (Files.isRegularFile(target) && MessageDigest.isEqual(expected, digest(target))) return target;

        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), extension + '-', ".tmp");
        try (InputStream input = requiredResource(root + extension + ".duckdb_extension")) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!MessageDigest.isEqual(expected, digest(temporary))) {
            Files.deleteIfExists(temporary);
            throw new IOException("checksum mismatch for packaged extension " + extension);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private String readText(String resource) throws IOException {
        try (InputStream input = requiredResource(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private InputStream requiredResource(String resource) throws IOException {
        InputStream input = PackagedDuckDbExtensionLoader.class.getResourceAsStream(resource);
        if (input == null) throw new IOException("missing packaged extension resource " + resource);
        return input;
    }

    private byte[] digest(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("mac")) return arm ? "osx_arm64" : "osx_amd64";
        if (os.contains("linux")) return arm ? "linux_arm64" : "linux_amd64";
        if (os.contains("win")) return arm ? "windows_arm64" : "windows_amd64";
        throw new IllegalStateException("unsupported DuckDB extension platform: " + os + '/' + arch);
    }

    private static String sqlString(String value) {
        return value.replace("'", "''");
    }
}
