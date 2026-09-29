package org.yanoproject.ledger.conformance;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** The harness's system properties (set by {@code ledger-conformance/build.gradle}) and pins. */
public final class ConformanceSettings {

    /** The Amaru tag the scenarios and the Amaru engine are pinned to ({@code amaru-validator-wasm/AMARU_VERSION}). */
    public static final String AMARU_TAG = "v10.11.20260925";

    private ConformanceSettings() {
    }

    /** @return true when missing coverage must fail ({@code -Pconformance.strict=true}, the Phase 5 gate) */
    public static boolean strict() {
        return Boolean.getBoolean("conformance.strict");
    }

    /** @return true when the build includes the Amaru engine ({@code -PwithAmaru=true}) */
    public static boolean withAmaru() {
        return Boolean.getBoolean("conformance.withAmaru");
    }

    /**
     * @return the version in {@code amaru-validator-wasm/Cargo.toml}, which the Amaru module under test must report
     *         in its {@code crate=} line (set by the build with {@code -PwithAmaru=true})
     */
    public static Optional<String> amaruCrateVersion() {
        return Optional.ofNullable(System.getProperty("amaru.wasm.crateVersion")).filter(s -> !s.isBlank());
    }

    /** @return the module the Amaru engine was compiled from ({@code amaru-validator/build/amaru-wasm}) */
    public static Optional<Path> amaruWasmFile() {
        return Optional.ofNullable(System.getProperty("amaru.wasm.file")).filter(s -> !s.isBlank()).map(Path::of)
                .filter(Files::isRegularFile);
    }

    /** @return the sha256 of {@link #amaruWasmFile()}, lowercase hex */
    public static Optional<String> amaruWasmSha256() {
        return amaruWasmFile().map(file -> {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** @return true for {@code conformanceReport}: the generated documents are written into the source tree */
    public static boolean writeDocs() {
        return Boolean.getBoolean("conformance.writeDocs");
    }

    /** @return the build directory for generated reports */
    public static Path reportDir() {
        String dir = System.getProperty("conformance.report.dir");
        return Path.of(dir != null ? dir : "build/conformance");
    }

    /** @return other modules' test-class directories to scan for {@code @Covers} */
    public static List<Path> scanDirs() {
        String dirs = System.getProperty("conformance.scan.dirs", "");
        return Arrays.stream(dirs.split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    /** @return the source-tree file a document goes to under {@code conformanceReport} */
    public static Optional<Path> docFile(String property) {
        return Optional.ofNullable(System.getProperty(property)).filter(s -> !s.isBlank()).map(Path::of);
    }

    /** Writes a generated document to the build directory, and to the source tree under {@code conformanceReport}. */
    public static Path write(String buildFileName, String docProperty, String content) {
        try {
            Path target = reportDir().resolve(buildFileName);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            if (writeDocs()) {
                Optional<Path> doc = docFile(docProperty);
                if (doc.isPresent()) {
                    Files.createDirectories(doc.get().getParent());
                    Files.writeString(doc.get(), content);
                    return doc.get();
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
