package org.yanoproject.ledger.conformance;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The harness's system properties (set by {@code ledger-conformance/build.gradle}) and pins. */
public final class ConformanceSettings {

    private ConformanceSettings() {
    }

    /**
     * @return the Amaru tag the scenarios are read at, {@code AMARU_TAG} in
     *         {@code ledger-conformance/node-diversity/corpora.env} (ADR-062 D5; set by the build)
     */
    public static String amaruTag() {
        String tag = System.getProperty("conformance.amaruTag");
        if (tag == null || tag.isBlank()) {
            throw new IllegalStateException(
                    "conformance.amaruTag is not set: run through ledger-conformance/build.gradle");
        }
        return tag;
    }

    /**
     * @return true when missing coverage must fail: the default since the ADR-056 Phase 5 gate (the build passes
     *         {@code conformance.strict}; {@code -Pconformance.strict=false} reports instead)
     */
    public static boolean strict() {
        return Boolean.parseBoolean(System.getProperty("conformance.strict", "true"));
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
