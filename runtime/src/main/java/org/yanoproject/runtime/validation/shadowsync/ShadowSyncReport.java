package org.yanoproject.runtime.validation.shadowsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.BlockResult;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.EngineResult;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.Kind;
import org.yanoproject.runtime.validation.shadowsync.SyncBlockValidator.TxResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shadow-sync results (ADR-056 Phase 7a): counters per engine and protocol version, one JSONL line per finding,
 * bounded replay bundles, and the periodic summary. Thread-safe.
 *
 * <h2>JSONL lines</h2>
 * <ul>
 *   <li>{@code "type":"tx"}, {@code "kind":"DISAGREED"|"ENGINE_FAILURE"}: slot, blockNo, blockHash, epoch, pv, engine,
 *       txIndex, txHash, expected ({@code VALID}|{@code PHASE2_INVALID}), actual, failures
 *       ({@code RULE.Constructor: detail}), overlayTainted, dump (the bundle path or null);</li>
 *   <li>{@code "type":"block"}, {@code "kind":"BLOCK_RULE"}: a {@code BBODY} check failed
 *       ({@code BodyRefScriptsSizeTooBig}, {@code TooManyExUnits}, {@code WrongBlockBodySizeBBODY},
 *       {@code InvalidBodyHashBBODY}), with supplied and limit;</li>
 *   <li>{@code "type":"block"}, {@code "kind":"ENGINE_FAILURE"}: the block could not be validated at all (no
 *       pre-block state, undecodable bytes, no environment, skipped under backpressure or still validating when
 *       shadow sync stopped), with reason and txCount;</li>
 *   <li>{@code "type":"block"}, {@code "kind":"ID_MISMATCH"}: the ids reassembled from the stored bytes differ from
 *       the applied block's;</li>
 *   <li>{@code "type":"summary"}: written when the node stops, the counters.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * Every applied Conway block with transactions is either submitted (captured and handed to validation) or failed
 * before submit; every submitted block ends validated, pre-Conway after capture (a Conway-era block below protocol
 * version 9) or failed after submit, which includes skipped at stop:
 * <pre>
 *   Conway blocks with transactions = submitted + failedBeforeSubmit
 *   submitted = validated + preConwayAfterCapture + failedAfterSubmit   (nothing in flight)
 * </pre>
 * Any failed block is a coverage gap: a clean run has both failure counts at 0.
 */
@Slf4j
public final class ShadowSyncReport implements AutoCloseable {

    /** Where a block sits in the chain. */
    public record BlockRef(long slot, long blockNumber, String blockHash) {
    }

    /** Per engine and protocol version. */
    public record Counts(long validated, long agreed, long disagreed, long engineFailures) {
    }

    /**
     * The counters.
     *
     * @param byEngine             per engine, per protocol major version
     * @param blocksSubmitted      blocks handed to validation (captured); each ends validated, pre-Conway after
     *                             capture or failed after submit
     * @param blocksValidated      blocks whose transactions were validated
 * @param boundaryBlocks       of those, blocks that were the first of their epoch (validated after the boundary)
     * @param blocksSkippedPreConway blocks before Conway (not validated), including those found pre-Conway after
     *                             capture
     * @param txsSkippedPreConway  their transactions
     * @param blocksEmpty          Conway blocks without transactions
     * @param blockFailures        Conway blocks that could not be validated: failed before plus after submit
     * @param txsInFailedBlocks    their transactions
     * @param blocksPreConwayAfterCapture submitted Conway-era blocks whose pre-block protocol version is below 9
     * @param blocksFailedBeforeSubmit blocks not handed to validation: outside a write section, no free slot within
     *                             the maximum wait, interrupted while waiting (the apply thread stopping), no pre-block
     *                             state
     * @param blocksFailedAfterSubmit submitted blocks not validated: no bytes, undecodable, no environment, an engine
     *                             crash, or skipped at stop
     * @param blocksSkippedAtStop  of those, blocks still validating when shadow sync stopped and gave up waiting
     * @param refScriptChecks      blocks whose reference-script size was checked
     * @param refScriptViolations  of those, blocks over the limit
     * @param refScriptUnavailable blocks where the check could not run
     * @param exUnitsChecks        blocks whose {@code TooManyExUnits} sum was checked
     * @param exUnitsViolations    of those, blocks over {@code maxBlockExUnits}
     * @param exUnitsUnavailable   blocks where that check could not run
     * @param bodyChecks           blocks whose body size and hash were checked against the header
     * @param bodyViolations       of those, blocks with a wrong body size or hash
     * @param idMismatches         blocks whose reassembled ids differ from the applied block's
     * @param backpressureWaits    blocks the apply thread waited for a free slot for
     * @param backpressureWaitMillis total time waited
     * @param validationMillis     total validation time of validated blocks (all engines)
     * @param dumpsWritten         replay bundles written
     * @param reportLines          JSONL lines written
     */
    public record Stats(Map<String, Map<Integer, Counts>> byEngine, long blocksSubmitted, long blocksValidated,
                        long boundaryBlocks, long blocksSkippedPreConway,
                        long txsSkippedPreConway, long blocksEmpty, long blockFailures, long txsInFailedBlocks,
                        long blocksPreConwayAfterCapture, long blocksFailedBeforeSubmit, long blocksFailedAfterSubmit,
                        long blocksSkippedAtStop,
                        long refScriptChecks, long refScriptViolations, long refScriptUnavailable,
                        long exUnitsChecks, long exUnitsViolations, long exUnitsUnavailable, long bodyChecks,
                        long bodyViolations, long idMismatches,
                        long backpressureWaits, long backpressureWaitMillis, long validationMillis, long dumpsWritten,
                        long reportLines) {

        /** @return transactions validated by {@code engine} over every protocol version */
        public long validated(String engine) {
            return sum(engine, Counts::validated);
        }

        public long disagreed(String engine) {
            return sum(engine, Counts::disagreed);
        }

        public long engineFailures(String engine) {
            return sum(engine, Counts::engineFailures);
        }

        public long agreed(String engine) {
            return sum(engine, Counts::agreed);
        }

        /** @return disagreements of every engine */
        public long disagreedTotal() {
            return byEngine.keySet().stream().mapToLong(this::disagreed).sum();
        }

        /** @return engine failures of every engine */
        public long engineFailuresTotal() {
            return byEngine.keySet().stream().mapToLong(this::engineFailures).sum();
        }

        private long sum(String engine, java.util.function.ToLongFunction<Counts> f) {
            Map<Integer, Counts> byPv = byEngine.get(engine);
            return byPv == null ? 0 : byPv.values().stream().mapToLong(f).sum();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path reportFile;
    private final Path dumpDir;
    private final int maxDumps;
    private BufferedWriter writer;
    private final Object writeLock = new Object();

    private final Map<String, Map<Integer, Adders>> byEngine = new ConcurrentHashMap<>();
    private final LongAdder blocksSubmitted = new LongAdder();
    private final LongAdder blocksValidated = new LongAdder();
    private final LongAdder boundaryBlocks = new LongAdder();
    private final LongAdder blocksSkippedPreConway = new LongAdder();
    private final LongAdder txsSkippedPreConway = new LongAdder();
    private final LongAdder blocksEmpty = new LongAdder();
    private final LongAdder blockFailures = new LongAdder();
    private final LongAdder txsInFailedBlocks = new LongAdder();
    private final LongAdder blocksPreConwayAfterCapture = new LongAdder();
    private final LongAdder blocksFailedBeforeSubmit = new LongAdder();
    private final LongAdder blocksFailedAfterSubmit = new LongAdder();
    private final LongAdder blocksSkippedAtStop = new LongAdder();
    private final LongAdder refScriptChecks = new LongAdder();
    private final LongAdder refScriptViolations = new LongAdder();
    private final LongAdder refScriptUnavailable = new LongAdder();
    private final LongAdder exUnitsChecks = new LongAdder();
    private final LongAdder exUnitsViolations = new LongAdder();
    private final LongAdder exUnitsUnavailable = new LongAdder();
    private final LongAdder bodyChecks = new LongAdder();
    private final LongAdder bodyViolations = new LongAdder();
    private final LongAdder idMismatches = new LongAdder();
    private final LongAdder backpressureWaits = new LongAdder();
    private final LongAdder backpressureWaitMillis = new LongAdder();
    private final LongAdder validationNanos = new LongAdder();
    private final AtomicLong dumpsWritten = new AtomicLong();
    private final LongAdder reportLines = new LongAdder();
    private final AtomicLong loggedFindings = new AtomicLong();

    private static final class Adders {
        final LongAdder validated = new LongAdder();
        final LongAdder agreed = new LongAdder();
        final LongAdder disagreed = new LongAdder();
        final LongAdder engineFailures = new LongAdder();
    }

    /**
     * @param reportFile the JSONL file (appended to), or {@code null}
     * @param dumpDir    the replay-bundle directory, or {@code null}
     * @param maxDumps   at most this many bundles
     */
    public ShadowSyncReport(Path reportFile, Path dumpDir, int maxDumps) {
        this.reportFile = reportFile;
        this.dumpDir = dumpDir;
        this.maxDumps = maxDumps;
        if (reportFile != null) {
            try {
                Path parent = reportFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Shadow sync: cannot open the report file {}: {}; findings are only logged", reportFile,
                        e.toString());
                writer = null;
            }
        }
    }

    /** @return the report file, or {@code null} */
    public Path reportFile() {
        return reportFile;
    }

    /** @return a dumper for {@link SyncBlockValidator}, or {@code null} when no dump directory is configured */
    public SyncBlockValidator.Dumper dumper() {
        if (dumpDir == null || maxDumps == 0) {
            return null;
        }
        return new SyncBlockValidator.Dumper() {
            @Override
            public Path write(ShadowDumpBundle bundle) {
                return dump(bundle);
            }

            @Override
            public boolean accepts() {
                return dumpsWritten.get() < maxDumps;
            }
        };
    }

    private Path dump(ShadowDumpBundle bundle) {
        if (dumpsWritten.incrementAndGet() > maxDumps) {
            dumpsWritten.decrementAndGet();
            return null;
        }
        try {
            return bundle.write(dumpDir);
        } catch (RuntimeException e) {
            dumpsWritten.decrementAndGet();
            log.warn("Shadow sync: cannot write the replay bundle for tx {}: {}", bundle.txHash(), e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records one validated block.
     *
     * @param firstOfEpoch the block is the first of its epoch
     */
    public void record(BlockRef block, int epoch, boolean firstOfEpoch, BlockResult result, long elapsedNanos) {
        blocksValidated.increment();
        if (firstOfEpoch) {
            boundaryBlocks.increment();
        }
        validationNanos.add(elapsedNanos);
        for (EngineResult engine : result.engines()) {
            Adders adders = adders(engine.engine(), result.protocolMajor());
            for (TxResult tx : engine.txs()) {
                adders.validated.increment();
                switch (tx.kind()) {
                    case AGREED -> adders.agreed.increment();
                    case DISAGREED -> adders.disagreed.increment();
                    case ENGINE_FAILURE -> adders.engineFailures.increment();
                }
                if (tx.kind() != Kind.AGREED) {
                    finding(block, epoch, result.protocolMajor(), engine.engine(), tx);
                }
            }
        }
        recordExUnits(block, epoch, result);
        recordBody(block, epoch, result);
        SyncBlockValidator.RefScriptCheck refs = result.refScripts();
        if (refs == null) {
            return;
        }
        if (!refs.checked()) {
            refScriptUnavailable.increment();
        } else {
            refScriptChecks.increment();
            if (refs.violated()) {
                refScriptViolations.increment();
                ObjectNode line = blockLine(block, "BLOCK_RULE", epoch, result.protocolMajor());
                line.put("check", "BBODY.BodyRefScriptsSizeTooBig");
                line.put("supplied", refs.totalSize());
                line.put("limit", refs.limit());
                write(line);
                log.warn("Shadow sync: block {} (slot {}) exceeds maxRefScriptSizePerBlock: {} > {}",
                        block.blockHash(), block.slot(), refs.totalSize(), refs.limit());
            }
        }
    }

    private void recordExUnits(BlockRef block, int epoch, BlockResult result) {
        SyncBlockValidator.ExUnitsCheck units = result.exUnits();
        if (units == null) {
            return;
        }
        if (!units.checked()) {
            exUnitsUnavailable.increment();
            return;
        }
        exUnitsChecks.increment();
        if (units.violated()) {
            exUnitsViolations.increment();
            ObjectNode line = blockLine(block, "BLOCK_RULE", epoch, result.protocolMajor());
            line.put("check", "BBODY.TooManyExUnits");
            line.put("supplied", "mem=" + units.mem() + ",steps=" + units.steps());
            line.put("limit", "mem=" + units.maxMem() + ",steps=" + units.maxSteps());
            write(line);
            log.warn("Shadow sync: block {} (slot {}) exceeds maxBlockExUnits: mem {} / {}, steps {} / {}",
                    block.blockHash(), block.slot(), units.mem(), units.maxMem(), units.steps(), units.maxSteps());
        }
    }

    private void recordBody(BlockRef block, int epoch, BlockResult result) {
        SyncBlock.BodyDigest body = result.body();
        if (body == null) {
            return;
        }
        bodyChecks.increment();
        if (body.sizeMatches() && body.hashMatches()) {
            return;
        }
        bodyViolations.increment();
        ObjectNode line = blockLine(block, "BLOCK_RULE", epoch, result.protocolMajor());
        List<String> checks = new ArrayList<>();
        if (!body.sizeMatches()) {
            checks.add("BBODY.WrongBlockBodySizeBBODY");
        }
        if (!body.hashMatches()) {
            checks.add("BBODY.InvalidBodyHashBBODY");
        }
        line.put("check", String.join(",", checks));
        line.put("supplied", body.actualSize() + " " + body.actualHash());
        line.put("limit", body.headerSize() + " " + body.headerHash());
        write(line);
        log.warn("Shadow sync: block {} (slot {}): body size {} / hash {} differ from the header's {} / {}",
                block.blockHash(), block.slot(), body.actualSize(), body.actualHash(), body.headerSize(),
                body.headerHash());
    }

    /** Records a block handed to validation. */
    public void submitted() {
        blocksSubmitted.increment();
    }

    /** Records a submitted block that shadow sync stopped before validating (a failure after submit). */
    public void skippedAtStop(BlockRef block, int txCount, String reason) {
        blocksSkippedAtStop.increment();
        failedAfterSubmit(block, txCount, reason);
    }

    /** Records a submitted Conway-era block whose pre-block protocol version is before Conway (not validated). */
    public void preConwayAfterCapture(int txCount) {
        blocksPreConwayAfterCapture.increment();
        skippedPreConway(txCount);
    }

    /** Records a Conway block with transactions that was never handed to validation. */
    public void failedBeforeSubmit(BlockRef block, int txCount, String reason) {
        blocksFailedBeforeSubmit.increment();
        blockFailure(block, txCount, reason);
    }

    /** Records a submitted block that could not be validated. */
    public void failedAfterSubmit(BlockRef block, int txCount, String reason) {
        blocksFailedAfterSubmit.increment();
        blockFailure(block, txCount, reason);
    }

    /** Records a block before Conway (not validated). */
    public void skippedPreConway(int txCount) {
        blocksSkippedPreConway.increment();
        txsSkippedPreConway.add(txCount);
    }

    /** Records a Conway block without transactions. */
    public void emptyBlock() {
        blocksEmpty.increment();
    }

    private void blockFailure(BlockRef block, int txCount, String reason) {
        blockFailures.increment();
        txsInFailedBlocks.add(txCount);
        ObjectNode line = blockLine(block, "ENGINE_FAILURE", -1, -1);
        line.put("txCount", txCount);
        line.put("reason", reason);
        write(line);
        if (loggedFindings.incrementAndGet() <= 100) {
            log.warn("Shadow sync: block {} (slot {}, {} txs) not validated: {}", block.blockHash(), block.slot(),
                    txCount, reason);
        }
    }

    /** Records that the ids reassembled from the stored bytes differ from the applied block's. */
    public void idMismatch(BlockRef block, List<String> stored, List<String> applied) {
        idMismatches.increment();
        ObjectNode line = blockLine(block, "ID_MISMATCH", -1, -1);
        stored.forEach(line.putArray("stored")::add);
        applied.forEach(line.putArray("applied")::add);
        write(line);
        log.warn("Shadow sync: block {} (slot {}): transaction ids from the stored bytes differ from the applied "
                + "block's", block.blockHash(), block.slot());
    }

    /** Records how long the apply thread waited for a free slot. */
    public void backpressureWait(long millis) {
        backpressureWaits.increment();
        backpressureWaitMillis.add(millis);
    }

    private void finding(BlockRef block, int epoch, int pv, String engine, TxResult tx) {
        ObjectNode line = JSON.createObjectNode();
        line.put("type", "tx");
        line.put("kind", tx.kind().name());
        line.put("at", Instant.now().toString());
        line.put("slot", block.slot());
        line.put("blockNo", block.blockNumber());
        line.put("blockHash", block.blockHash());
        line.put("epoch", epoch);
        line.put("pv", pv);
        line.put("engine", engine);
        line.put("txIndex", tx.index());
        line.put("txHash", tx.txHash());
        line.put("expected", tx.expected().name());
        line.put("actual", tx.actual());
        ArrayNode failures = line.putArray("failures");
        for (LedgerFailure f : tx.failures()) {
            failures.add(f.qualifiedName() + (f.detail() != null && !f.detail().isBlank() ? ": " + f.detail() : ""));
        }
        line.put("overlayTainted", tx.overlayTainted());
        if (tx.dump() != null) {
            line.put("dump", tx.dump().toString());
        } else {
            line.putNull("dump");
        }
        write(line);
        if (loggedFindings.incrementAndGet() <= 100) {
            log.warn("Shadow sync {}: engine {} on tx {} (block {}, slot {}, index {}, pv {}): expected {}, got {}",
                    tx.kind(), engine, tx.txHash(), block.blockNumber(), block.slot(), tx.index(), pv,
                    tx.expected(), tx.actual());
        }
    }

    private static ObjectNode blockLine(BlockRef block, String kind, int epoch, int pv) {
        ObjectNode line = JSON.createObjectNode();
        line.put("type", "block");
        line.put("kind", kind);
        line.put("at", Instant.now().toString());
        line.put("slot", block.slot());
        line.put("blockNo", block.blockNumber());
        line.put("blockHash", block.blockHash());
        if (epoch >= 0) {
            line.put("epoch", epoch);
        }
        if (pv >= 0) {
            line.put("pv", pv);
        }
        return line;
    }

    private void write(ObjectNode line) {
        synchronized (writeLock) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(JSON.writeValueAsString(line));
                writer.newLine();
                writer.flush();
                reportLines.increment();
            } catch (IOException e) {
                log.warn("Shadow sync: writing the report failed: {}; further findings are only logged",
                        e.toString());
                closeWriter();
            }
        }
    }

    private Adders adders(String engine, int pv) {
        return byEngine.computeIfAbsent(engine, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(pv, ignored -> new Adders());
    }

    // ------------------------------------------------------------------ reading

    public Stats stats() {
        Map<String, Map<Integer, Counts>> engines = new TreeMap<>();
        byEngine.forEach((engine, byPv) -> {
            Map<Integer, Counts> counts = new TreeMap<>();
            byPv.forEach((pv, a) -> counts.put(pv, new Counts(a.validated.sum(), a.agreed.sum(), a.disagreed.sum(),
                    a.engineFailures.sum())));
            engines.put(engine, Map.copyOf(counts));
        });
        return new Stats(Map.copyOf(engines), blocksSubmitted.sum(), blocksValidated.sum(), boundaryBlocks.sum(),
                blocksSkippedPreConway.sum(), txsSkippedPreConway.sum(), blocksEmpty.sum(), blockFailures.sum(),
                txsInFailedBlocks.sum(), blocksPreConwayAfterCapture.sum(), blocksFailedBeforeSubmit.sum(),
                blocksFailedAfterSubmit.sum(), blocksSkippedAtStop.sum(),
                refScriptChecks.sum(), refScriptViolations.sum(), refScriptUnavailable.sum(), exUnitsChecks.sum(),
                exUnitsViolations.sum(), exUnitsUnavailable.sum(), bodyChecks.sum(), bodyViolations.sum(),
                idMismatches.sum(),
                backpressureWaits.sum(), backpressureWaitMillis.sum(), validationNanos.sum() / 1_000_000,
                dumpsWritten.get(), reportLines.sum());
    }

    /** @return one line for the INFO summary */
    public static String summary(Stats s) {
        StringBuilder out = new StringBuilder();
        out.append("blocks: submitted=").append(s.blocksSubmitted())
                .append(" validated=").append(s.blocksValidated())
                .append(" (first of epoch ").append(s.boundaryBlocks()).append(')')
                .append(" pre-Conway-after-capture=").append(s.blocksPreConwayAfterCapture())
                .append(" failed(before submit)=").append(s.blocksFailedBeforeSubmit())
                .append(" failed(after submit)=").append(s.blocksFailedAfterSubmit())
                .append(" skippedAtStop=").append(s.blocksSkippedAtStop())
                .append(" empty=").append(s.blocksEmpty())
                .append(" pre-Conway skipped=").append(s.blocksSkippedPreConway())
                .append(" (txs ").append(s.txsSkippedPreConway()).append(')');
        s.byEngine().forEach((engine, byPv) -> byPv.forEach((pv, c) -> out.append(" | ").append(engine)
                .append(" pv").append(pv).append(": validated=").append(c.validated())
                .append(" agreed=").append(c.agreed())
                .append(" disagreed=").append(c.disagreed())
                .append(" engine-failures=").append(c.engineFailures())));
        out.append(" | refScripts checked=").append(s.refScriptChecks())
                .append(" violations=").append(s.refScriptViolations())
                .append(" unavailable=").append(s.refScriptUnavailable())
                .append(" | exUnits checked=").append(s.exUnitsChecks())
                .append(" violations=").append(s.exUnitsViolations())
                .append(" unavailable=").append(s.exUnitsUnavailable())
                .append(" | body checked=").append(s.bodyChecks())
                .append(" violations=").append(s.bodyViolations())
                .append(" | idMismatches=").append(s.idMismatches())
                .append(" | backpressure waits=").append(s.backpressureWaits())
                .append(" (").append(s.backpressureWaitMillis()).append(" ms)")
                .append(" | validation ").append(s.validationMillis()).append(" ms");
        return out.toString();
    }

    /** Writes the final summary line and closes the report file. The file is closed even if the summary fails. */
    @Override
    public void close() {
        try {
            ObjectNode line = JSON.createObjectNode();
            line.put("type", "summary");
            line.put("at", Instant.now().toString());
            line.set("stats", statsNode(stats()));
            write(line);
        } catch (RuntimeException e) {
            log.warn("Shadow sync: writing the summary failed: {}", e.toString());
        } finally {
            synchronized (writeLock) {
                closeWriter();
            }
        }
    }

    /**
     * The counters as JSON, built field by field: no bean introspection, so it works in a native image without
     * reflection registration.
     */
    static ObjectNode statsNode(Stats s) {
        ObjectNode node = JSON.createObjectNode();
        ObjectNode engines = node.putObject("byEngine");
        s.byEngine().forEach((engine, byPv) -> {
            ObjectNode pvs = engines.putObject(engine);
            byPv.forEach((pv, c) -> {
                ObjectNode counts = pvs.putObject(Integer.toString(pv));
                counts.put("validated", c.validated());
                counts.put("agreed", c.agreed());
                counts.put("disagreed", c.disagreed());
                counts.put("engineFailures", c.engineFailures());
            });
        });
        node.put("blocksSubmitted", s.blocksSubmitted());
        node.put("blocksValidated", s.blocksValidated());
        node.put("boundaryBlocks", s.boundaryBlocks());
        node.put("blocksSkippedPreConway", s.blocksSkippedPreConway());
        node.put("txsSkippedPreConway", s.txsSkippedPreConway());
        node.put("blocksEmpty", s.blocksEmpty());
        node.put("blockFailures", s.blockFailures());
        node.put("txsInFailedBlocks", s.txsInFailedBlocks());
        node.put("blocksPreConwayAfterCapture", s.blocksPreConwayAfterCapture());
        node.put("blocksFailedBeforeSubmit", s.blocksFailedBeforeSubmit());
        node.put("blocksFailedAfterSubmit", s.blocksFailedAfterSubmit());
        node.put("blocksSkippedAtStop", s.blocksSkippedAtStop());
        node.put("refScriptChecks", s.refScriptChecks());
        node.put("refScriptViolations", s.refScriptViolations());
        node.put("refScriptUnavailable", s.refScriptUnavailable());
        node.put("exUnitsChecks", s.exUnitsChecks());
        node.put("exUnitsViolations", s.exUnitsViolations());
        node.put("exUnitsUnavailable", s.exUnitsUnavailable());
        node.put("bodyChecks", s.bodyChecks());
        node.put("bodyViolations", s.bodyViolations());
        node.put("idMismatches", s.idMismatches());
        node.put("backpressureWaits", s.backpressureWaits());
        node.put("backpressureWaitMillis", s.backpressureWaitMillis());
        node.put("validationMillis", s.validationMillis());
        node.put("dumpsWritten", s.dumpsWritten());
        node.put("reportLines", s.reportLines());
        return node;
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("Closing the shadow sync report failed: {}", e.toString());
            }
            writer = null;
        }
    }

    @Override
    public String toString() {
        return "ShadowSyncReport[" + Objects.toString(reportFile, "no file") + "]";
    }
}
