package org.yanoproject.ledger.rules.conway.ruleset;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The frozen rule-set manifests (ADR-056 Phase 5c): for every supported protocol version, the composed rule set —
 * every scope's units in execution order with id, kind, label, the version that introduced it, implementation class,
 * content fingerprint ({@link SourceFingerprints}), parameters and Haskell reference, and the policies — must equal the
 * committed {@code conway-pv<N>.manifest}.
 *
 * <p>The manifests pin the composition <em>and the units' content</em>: an edit to a unit shows in the manifest of
 * every protocol version that contains it, so regenerating is the explicit "I mean all these versions" act (allowed
 * only for Haskell-cited fixes; anything else is a superseding unit in the newest delta). Behaviour is pinned by the
 * per-protocol-version gates only where a mutant or scenario exercises it. Regenerate after an intentional change with
 * {@code ./gradlew :ledger-rules:test --tests '*ConwayRuleSetManifestTest' -PupdateRuleManifests=true} and review the
 * diff.</p>
 */
class ConwayRuleSetManifestTest {

    private static final String RESOURCE_DIR = "/org/yanoproject/ledger/rules/conway/ruleset/";
    private static final SourceFingerprints FINGERPRINTS = new SourceFingerprints(sourceDir());
    private static final String REGENERATE = "./gradlew :ledger-rules:test --tests '*ConwayRuleSetManifestTest' "
            + "-PupdateRuleManifests=true";

    @TestFactory
    Stream<DynamicTest> everyProtocolVersionMatchesItsFrozenManifest() {
        return ConwayRuleSets.all().stream().map(set -> DynamicTest.dynamicTest("protocol version "
                + set.protocolVersion(), () -> check(set)));
    }

    @Test
    void thereIsAManifestForEverySupportedProtocolVersionAndNoOther() throws IOException {
        if (update()) {
            return;
        }
        Path dir = manifestDir();
        List<String> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".manifest")).sorted().toList();
        }
        List<String> expected = ConwayRuleSets.all().stream().map(s -> fileName(s.protocolVersion())).sorted()
                .toList();
        assertThat(files).as("manifests in %s", dir).isEqualTo(expected);
    }

    private static void check(ConwayRuleSet set) throws IOException {
        String actual = set.manifest(FINGERPRINTS::of);
        if (update()) {
            Path file = manifestDir().resolve(fileName(set.protocolVersion()));
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual);
            System.out.println("wrote " + file);
            return;
        }
        String frozen = read(set.protocolVersion());
        if (frozen == null) {
            fail("no frozen manifest for PV" + set.protocolVersion() + " (" + fileName(set.protocolVersion())
                    + "): generate it with " + REGENERATE + " and review it");
        }
        if (!frozen.equals(actual)) {
            fail("rule set for PV" + set.protocolVersion() + " changed:\n" + diff(frozen, actual) + "\n— if "
                    + "intentional, regenerate with " + REGENERATE + " and review the diff (only the protocol versions "
                    + "you meant to change may differ)");
        }
    }

    /** A line diff ({@code -} frozen only, {@code +} computed only), by longest common subsequence. */
    static String diff(String frozen, String actual) {
        List<String> a = frozen.lines().toList();
        List<String> b = actual.lines().toList();
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        String scope = "";
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                if (a.get(i).startsWith("[")) {
                    scope = a.get(i);
                }
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.add("  " + scope + " + " + b.get(j++));
            } else {
                out.add("  " + scope + " - " + a.get(i++));
            }
        }
        return String.join("\n", out);
    }

    private static String read(int version) throws IOException {
        try (InputStream in = ConwayRuleSetManifestTest.class.getResourceAsStream(RESOURCE_DIR + fileName(version))) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fileName(int version) {
        return "conway-pv" + version + ".manifest";
    }

    private static boolean update() {
        return Boolean.parseBoolean(System.getProperty("updateRuleManifests", "false"));
    }

    private static Path sourceDir() {
        String dir = System.getProperty("ruleSources.dir");
        if (dir == null || dir.isBlank()) {
            throw new UncheckedIOException(new IOException("ruleSources.dir is not set (ledger-rules/build.gradle)"));
        }
        return Path.of(dir);
    }

    private static Path manifestDir() {
        String dir = System.getProperty("ruleManifests.dir");
        if (dir == null || dir.isBlank()) {
            throw new UncheckedIOException(new IOException("ruleManifests.dir is not set (ledger-rules/build.gradle)"));
        }
        return Path.of(dir);
    }
}
