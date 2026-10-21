package org.yanoproject.ledger.conformance.blueprint;

import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The vectors Amaru's own harness fails at the pinned Amaru tag: the keys of
 * {@code crates/amaru-ledger/tests/data/rules-conformance.failures.toml}, pinned in
 * {@code amaru-known-failures.txt}. Amaru passes every other vector, which is the set the Java engine must pass too
 * (ADR-056 Phase 7 gate: "blueprint Conway vectors at or above Amaru's recorded pass set").
 */
public final class AmaruKnownFailures {

    private static final String RESOURCE = "/org/yanoproject/ledger/conformance/blueprint/amaru-known-failures.txt";
    private static final String FAILURES_FILE = "crates/amaru-ledger/tests/data/rules-conformance.failures.toml";
    private static final Pattern TOML_KEY = Pattern.compile("^\"([^\"]+)\"\\s*=");

    private AmaruKnownFailures() {
    }

    /** @return the pinned vector ids, in file order */
    public static Set<String> pinned() {
        try (InputStream in = AmaruKnownFailures.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            Set<String> ids = new LinkedHashSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    ids.add(trimmed);
                }
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the keys of the failures file in the configured Amaru checkout ({@code -PamaruScenariosDir}), when there
     *         is one
     */
    public static Optional<Set<String>> fromAmaruCheckout() {
        Optional<Path> scenarios = AmaruScenarioLoader.locateScenariosDir();
        if (scenarios.isEmpty()) {
            return Optional.empty();
        }
        // The scenarios directory is crates/amaru-ledger/tests/data/transaction; the failures file sits next to it.
        Path file = scenarios.get().resolveSibling("rules-conformance.failures.toml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Set<String> ids = new LinkedHashSet<>();
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                Matcher m = TOML_KEY.matcher(line);
                if (m.find()) {
                    ids.add(m.group(1));
                }
            }
            return Optional.of(ids);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + FAILURES_FILE, e);
        }
    }
}
