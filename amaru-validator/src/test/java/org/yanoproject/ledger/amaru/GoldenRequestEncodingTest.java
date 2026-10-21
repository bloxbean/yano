package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden interface tests: the Java encoder ({@link AmaruRequestEncoder} with
 * {@link org.yanoproject.ledger.amaru.wire.ProtocolParamsEncoder}), given a scenario's inputs through the
 * shared scenario loader, must write byte for byte the request the Rust reference encoder wrote for the
 * same scenario. This pins the whole v1 request layout, including Amaru's 31-element parameter layout
 * and the test-only ledger constants, so an interface drift on either side fails here.
 *
 * <p>The committed subset ({@code src/test/resources/golden}) always runs when the scenarios are
 * configured. With the Rust gate's full dump ({@code amaru.request.dumps.dir}, by default
 * {@code amaru-validator-wasm/target/scenario-requests}) every scenario is compared in both modes.</p>
 */
class GoldenRequestEncodingTest {

    @Test
    void committedGoldenRequestsMatchTheReferenceEncoder() throws Exception {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        Path golden = resourceDir("golden");
        List<Path> files;
        try (Stream<Path> list = Files.list(golden)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".req.cbor")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(10);
        for (Path file : files) {
            compare(loader, file);
        }
    }

    @Test
    void everyDumpedReferenceRequestMatches() throws IOException {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        String configured = System.getProperty("amaru.request.dumps.dir", "");
        Path dumps = configured.isBlank() ? null : Path.of(configured);
        assumeTrue(dumps != null && Files.isDirectory(dumps),
                "no reference request dump (run the Rust gate with AMARU_DUMP_REQUESTS_DIR)");
        List<Path> files;
        try (Stream<Path> list = Files.list(dumps)) {
            files = list.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".full.req.cbor") || name.endsWith(".phase_one.req.cbor");
            }).sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "the dump directory holds no requests");
        List<String> mismatches = new ArrayList<>();
        for (Path file : files) {
            try {
                compare(loader, file);
            } catch (AssertionError e) {
                mismatches.add(file.getFileName() + ": " + e.getMessage().lines().findFirst().orElse(""));
            }
        }
        System.out.println("Golden request encoding: " + (files.size() - mismatches.size()) + " of " + files.size()
                + " reference requests reproduced byte for byte");
        assertThat(mismatches).isEmpty();
        // Every scenario has a full-mode request; the decoding-failure scenarios stop before phase-one mode.
        assertThat(files.stream().filter(p -> p.getFileName().toString().endsWith(".full.req.cbor")))
                .hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);
    }

    private static void compare(AmaruScenarioLoader loader, Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String scenario = fileName.substring(0, fileName.indexOf('.'));
        AmaruRequest.Mode mode = fileName.endsWith(".phase_one.req.cbor") ? AmaruRequest.Mode.PHASE_ONE
                : AmaruRequest.Mode.FULL;
        AmaruScenario loaded = loader.load(scenario + ".json");
        byte[] expected = Files.readAllBytes(file);
        byte[] actual = AmaruRequestEncoder.encode(ScenarioSupport.referenceRequest(loaded, mode));
        assertThat(HexUtil.encodeHexString(actual)).as(fileName).isEqualTo(HexUtil.encodeHexString(expected));
    }

    private static Path resourceDir(String name) {
        URL url = Objects.requireNonNull(GoldenRequestEncodingTest.class.getClassLoader().getResource(name), name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
