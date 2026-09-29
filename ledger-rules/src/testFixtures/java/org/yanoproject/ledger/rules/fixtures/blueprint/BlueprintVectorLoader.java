package org.yanoproject.ledger.rules.fixtures.blueprint;

import org.junit.jupiter.api.Assumptions;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the cardano-blueprint ledger conformance vectors (ADR-056 §8, Phase 7b): a directory with {@code eras/} (one
 * vector file per Imp test run, grouped by the spec it comes from) and {@code pparams/} (the protocol-parameter
 * records the states refer to by hash).
 *
 * <p><b>Pin.</b> The vectors of cardano-blueprint PR #71 ("update ledger rules conformance test vectors", head
 * {@value #BLUEPRINT_COMMIT} in {@code KtorZ/cardano-blueprint}), file {@value #TARBALL_PATH}, sha256
 * {@value #TARBALL_SHA256}. That is the binary {@code [config, NewEpochState, NewEpochState, events, title]} format
 * Amaru's {@code evaluate_ledger_states.rs} reads, and the exact set in Amaru's
 * {@code crates/amaru-ledger/tests/data/rules-conformance} at the pinned Amaru tag. cardano-blueprint {@code main}
 * ({@code 0f0c17e1}) still carries the older tarball (one JSON file per transaction with a {@code LedgerState}), which
 * is a different format and is not read here. <b>The pin depends on an unmerged fork commit</b>: GitHub serves
 * {@value #TARBALL_URL} only while the PR's ref exists; if it disappears, re-pin to the merged commit or to Amaru's copy
 * of the same files. The tarball is deliberately not vendored into Yano. Because the directory can come from the tarball or from an Amaru
 * checkout, {@link #verifyPin()} checks the extracted files against {@value #CORPUS_DIGEST}, a digest over every
 * file's path and content.</p>
 *
 * <p><b>Locating.</b> System property {@value #VECTORS_PROPERTY} or environment variable {@value #VECTORS_ENV}: the
 * extracted directory (holding {@code eras/} and {@code pparams/}). The build sets the property from
 * {@code -PblueprintVectors=<tarball or directory>} (a tarball is checked against the pinned sha256 and extracted), or
 * falls back to the Amaru checkout of {@code -PamaruScenariosDir}. Tests call {@link #requireVectorsDir()}, which
 * skips them (a JUnit assumption) when neither is configured.</p>
 */
public final class BlueprintVectorLoader {

    public static final String VECTORS_PROPERTY = "blueprint.vectors.dir";
    public static final String VECTORS_ENV = "BLUEPRINT_VECTORS_DIR";

    /** The repository and commit of cardano-blueprint PR #71's head. */
    public static final String BLUEPRINT_REPOSITORY = "KtorZ/cardano-blueprint";
    public static final String BLUEPRINT_COMMIT = "d57b8d765395e54645824b6718b02c6565bc0ffc";
    public static final String TARBALL_PATH = "src/ledger/conformance-test-vectors/vectors.tar.gz";
    public static final String TARBALL_URL = "https://github.com/cardano-scaling/cardano-blueprint/raw/"
            + BLUEPRINT_COMMIT + "/" + TARBALL_PATH;
    public static final String TARBALL_SHA256 = "5041539c9e2908cc897512f249ea16ec26bbb04cb1ec288dc59f47024a9b727d";

    /** sha256 over {@code relative path \n sha256(file) \n} of every file, in path order. */
    public static final String CORPUS_DIGEST = "39bf59ed66e31d7e61f7f9c41e924905d0795c0ae4324590d3a6bfe072a05ed2";
    public static final int EXPECTED_VECTORS = 320;
    public static final int EXPECTED_PPARAMS = 44;

    /** Where an Amaru checkout keeps the same vectors. */
    private static final String AMARU_VECTORS = "crates/amaru-ledger/tests/data/rules-conformance";

    private final Path root;
    private final Map<String, byte[]> pparams = new TreeMap<>();

    /** @param root the directory holding {@code eras/} and {@code pparams/} */
    public BlueprintVectorLoader(Path root) {
        this.root = root;
    }

    /** @return the configured vectors directory, if any and if it holds the vectors */
    public static Optional<Path> locateVectorsDir() {
        String configured = System.getProperty(VECTORS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(VECTORS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(configured);
        // The directory itself, an Amaru checkout's root, or Amaru's transaction-scenario directory (a sibling).
        for (Path candidate : List.of(dir, dir.resolve(AMARU_VECTORS), dir.resolveSibling("rules-conformance"))) {
            if (Files.isDirectory(candidate.resolve("eras")) && Files.isDirectory(candidate.resolve("pparams"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * @return the vectors directory
     * @throws TestAbortedException (a skipped test) when it is not configured
     */
    public static Path requireVectorsDir() {
        Optional<Path> dir = locateVectorsDir();
        Assumptions.assumeTrue(dir.isPresent(), () -> "cardano-blueprint vectors not configured: pass "
                + "-PblueprintVectors=<vectors.tar.gz or extracted directory> (" + TARBALL_URL + ", sha256 "
                + TARBALL_SHA256 + "), or set -D" + VECTORS_PROPERTY + " / " + VECTORS_ENV);
        return dir.orElseThrow();
    }

    /** @return a loader for the configured vectors; skips the calling test when there are none */
    public static BlueprintVectorLoader fromEnvironment() {
        return new BlueprintVectorLoader(requireVectorsDir());
    }

    public Path root() {
        return root;
    }

    /** @return every vector file under {@code eras/}, by relative path */
    public List<Path> vectorFiles() {
        try (Stream<Path> files = Files.walk(root.resolve("eras"))) {
            return files.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the digest of the directory's files (see {@link #CORPUS_DIGEST}) */
    public String corpusDigest() {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> all = files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = relative(p);
                        return rel.startsWith("eras/") || rel.startsWith("pparams/");
                    })
                    .sorted((a, b) -> relative(a).compareTo(relative(b)))
                    .toList();
            MessageDigest corpus = sha256();
            for (Path file : all) {
                String line = relative(file) + "\n" + HexFormat.of().formatHex(sha256().digest(Files.readAllBytes(file)))
                        + "\n";
                corpus.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(corpus.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @throws IllegalStateException when the directory is not the pinned vector set */
    public void verifyPin() {
        String digest = corpusDigest();
        if (!CORPUS_DIGEST.equals(digest)) {
            throw new IllegalStateException("the vectors in " + root + " are not the pinned set (digest " + digest
                    + ", pinned " + CORPUS_DIGEST + ")");
        }
    }

    /** @return the protocol-parameter record with this hash (lowercase hex) */
    public synchronized Optional<byte[]> pparams(String hashHex) {
        if (pparams.isEmpty()) {
            try (Stream<Path> files = Files.list(root.resolve("pparams"))) {
                for (Path file : files.toList()) {
                    pparams.put(file.getFileName().toString(), Files.readAllBytes(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.ofNullable(pparams.get(hashHex)).map(byte[]::clone);
    }

    /** @return the number of protocol-parameter records */
    public int pparamsCount() {
        pparams("");
        return pparams.size();
    }

    /** @return a decoder that resolves parameter records from this directory */
    public NewEpochStateDecoder decoder() {
        return new NewEpochStateDecoder(this::pparams);
    }

    /** @return every vector, by relative path */
    public List<BlueprintVector> loadAll() {
        return vectorFiles().stream().map(this::load).toList();
    }

    public BlueprintVector load(Path file) {
        String id = relative(root.resolve("eras").relativize(file).toString().replace('\\', '/'));
        try {
            return parse(id, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read vector " + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot load vector " + id + ": " + e.getMessage(), e);
        }
    }

    /** Parses one vector file. */
    public static BlueprintVector parse(String id, byte[] bytes) {
        CborReader r = new CborReader(bytes);
        r.readArray(5, "vector");
        BlueprintVector.Config config = config(r);
        byte[] initial = r.readRaw();
        byte[] fin = r.readRaw();
        List<BlueprintVector.Event> events = new ArrayList<>();
        long count = r.readArray();
        for (long i = 0; r.hasNext(count, i); i++) {
            long fields = r.readArray();
            int kind = r.readInt();
            events.add(switch (kind) {
                case 0 -> {
                    require(fields, 4, "transaction event");
                    byte[] tx = r.readBytes();
                    boolean success = r.readBool();
                    yield new BlueprintVector.Event.Tx(tx, success, r.readUint());
                }
                case 1 -> {
                    require(fields, 2, "tick event");
                    yield new BlueprintVector.Event.PassTick(r.readUint());
                }
                case 2 -> {
                    require(fields, 2, "epoch event");
                    yield new BlueprintVector.Event.PassEpoch(r.readUint());
                }
                default -> throw new IllegalArgumentException("unknown event kind " + kind);
            });
        }
        String title = r.readText();
        if (!r.atEnd()) {
            throw new IllegalArgumentException("trailing bytes after the vector");
        }
        int slash = id.indexOf('/');
        return new BlueprintVector(id, slash > 0 ? id.substring(0, slash) : id, config, initial, fin, events, title);
    }

    /**
     * {@code [slot, epoch, epochSize, slotsPerKESPeriod, stabilityWindow, randomnessStabilisationWindow,
     * securityParameter, maxKESEvo, quorum, maxLovelaceSupply, activeSlotCoeff, networkId, systemStart]}, where the
     * system start is {@code [year, dayOfYear, picosecondsOfDay]} (Haskell {@code UTCTime}).
     */
    private static BlueprintVector.Config config(CborReader r) {
        r.readArray(13, "config");
        long slot = r.readUint();
        long epoch = r.readUint();
        long epochSize = r.readUint();
        long kesPeriod = r.readUint();
        long stability = r.readUint();
        long randomness = r.readUint();
        long k = r.readUint();
        long maxKes = r.readUint();
        long quorum = r.readUint();
        BigInteger maxSupply = r.readBigInteger();
        BigInteger[] f = r.readRational();
        int networkId = r.readInt();
        r.readArray(3, "system start");
        int year = r.readInt();
        int dayOfYear = r.readInt();
        BigInteger picos = r.readBigInteger();
        long startMillis = LocalDate.ofYearDay(year, dayOfYear).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                + picos.divide(BigInteger.valueOf(1_000_000_000L)).longValueExact();
        return new BlueprintVector.Config(slot, epoch, epochSize, kesPeriod, stability, randomness, k, maxKes, quorum,
                maxSupply, f, networkId, startMillis);
    }

    private static void require(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalArgumentException(what + ": expected " + expected + " fields, got " + actual);
        }
    }

    private String relative(Path file) {
        return relative(root.relativize(file).toString().replace('\\', '/'));
    }

    private static String relative(String path) {
        return path.replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
