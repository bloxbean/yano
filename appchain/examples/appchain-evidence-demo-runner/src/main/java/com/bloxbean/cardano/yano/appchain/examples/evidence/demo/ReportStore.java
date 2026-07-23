package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Atomically persists bounded scenario reports and verified UI presentation copies. */
public final class ReportStore {
    public static final String LATEST_FILE = "latest.json";
    public static final String LATEST_LOAD_FILE = "load-latest.json";
    public static final int MAX_REPORT_BYTES = 1_048_576;
    public static final int MAX_CONTENT_PREVIEW_BYTES = 262_144;
    public static final int DEFAULT_CATALOG_PAGE_SIZE = 20;
    public static final int MAX_CATALOG_PAGE_SIZE = 100;
    private static final int MAX_EVIDENCE_REPORT_FILES = 10_000;
    private static final String EVIDENCE_ID = "[a-z0-9][a-z0-9-]{0,63}";
    private static final Set<PosixFilePermission> REPORT_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    private static final Set<PosixFilePermission> CONTENT_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final Path directory;
    private volatile EvidenceIndex cachedEvidenceIndex;

    public ReportStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path write(ScenarioReport report) {
        return writeReport(report, "report-" + safeId(report.scenarioId()) + ".json",
                LATEST_FILE);
    }

    public Path writeLoad(LoadReport report) {
        return writeReport(report, safeId(report.loadId()) + ".json",
                LATEST_LOAD_FILE);
    }

    private Path writeReport(Object report, String versionedName, String latestName) {
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
            Path versioned = directory.resolve(versionedName);
            atomicWrite(versioned, encoded, REPORT_PERMISSIONS);
            atomicWrite(directory.resolve(latestName), encoded, REPORT_PERMISSIONS);
            return versioned;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
    }

    public byte[] readLatest() {
        return readLatest(LATEST_FILE);
    }

    public byte[] readLatestLoad() {
        return readLatest(LATEST_LOAD_FILE);
    }

    private byte[] readLatest(String name) {
        Path latest = directory.resolve(name);
        try {
            return BoundedFiles.read(latest, MAX_REPORT_BYTES, true, false);
        } catch (IOException failure) {
            return null;
        }
    }

    /** Returns up to twenty newest bounded reports as one sanitized JSON array. */
    public byte[] readRecent() {
        ArrayNode result = JSON.createArrayNode();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return encode(result);
        }
        try (var files = Files.list(directory)) {
            List<Path> candidates = files
                    .filter(path -> path.getFileName().toString()
                            .matches("report-[a-z0-9][a-z0-9-]{0,63}\\.json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparingLong(ReportStore::modifiedAt)
                            .reversed().thenComparing(Path::toString))
                    .limit(20)
                    .toList();
            int total = 2;
            List<JsonNode> accepted = new ArrayList<>();
            for (Path candidate : candidates) {
                byte[] bytes = BoundedFiles.read(
                        candidate, MAX_REPORT_BYTES, true, false);
                JsonNode report = JSON.readTree(bytes);
                if (report == null || !report.isObject()
                        || report.path("schemaVersion").asInt(-1)
                        != ScenarioReport.SCHEMA_VERSION) {
                    continue;
                }
                total = Math.addExact(total, bytes.length + 1);
                if (total > MAX_REPORT_BYTES) {
                    break;
                }
                accepted.add(report);
            }
            accepted.forEach(result::add);
            return encode(result);
        } catch (IOException | ArithmeticException failure) {
            return encode(JSON.createArrayNode());
        }
    }

    /**
     * Persists an exact, bounded presentation copy only after the scenario has
     * independently verified the immutable object version and matching IPFS
     * content. This copy is never a consensus input and is owner-readable only.
     */
    public void writeVerifiedContent(String evidenceId,
                                     long businessVersion,
                                     byte[] content,
                                     byte[] expectedSha256) {
        requireEvidenceIdentity(evidenceId, businessVersion);
        if (content == null || expectedSha256 == null || expectedSha256.length != 32
                || !Arrays.equals(Digests.sha256(content), expectedSha256)) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
        if (content.length > MAX_CONTENT_PREVIEW_BYTES) {
            return;
        }
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new DemoException(DemoError.REPORT_WRITE_FAILED);
            }
            atomicWrite(contentPath(evidenceId, businessVersion), content,
                    CONTENT_PERMISSIONS);
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
    }

    /** Returns a bounded newest-publication-first inventory of retained evidence versions. */
    public byte[] readEvidenceCatalog() {
        return readEvidenceCatalog(1, DEFAULT_CATALOG_PAGE_SIZE, null);
    }

    /** Returns one bounded, newest-publication-first page, optionally filtered by identity/version. */
    public byte[] readEvidenceCatalog(int requestedPage, int pageSize, String query) {
        if (requestedPage < 1 || pageSize < 1 || pageSize > MAX_CATALOG_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid evidence catalog page");
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() > 64 || !normalizedQuery.matches("[a-z0-9-]*")) {
            throw new IllegalArgumentException("invalid evidence catalog query");
        }
        Map<EvidenceKey, EvidenceAggregate> evidence = evidenceReports();
        List<Map.Entry<EvidenceKey, EvidenceAggregate>> ordered = evidence.entrySet().stream()
                .filter(entry -> matchesQuery(entry.getKey(), normalizedQuery))
                .sorted(Comparator
                        .<Map.Entry<EvidenceKey, EvidenceAggregate>, Instant>comparing(
                                entry -> entry.getValue().publicationTime()).reversed()
                        .thenComparing(entry -> entry.getKey().evidenceId())
                        .thenComparing(entry -> entry.getKey().businessVersion(),
                                Comparator.reverseOrder()))
                .toList();
        int total = ordered.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = Math.min(requestedPage, totalPages);
        int from = Math.min(total, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);
        ArrayNode items = JSON.createArrayNode();
        for (Map.Entry<EvidenceKey, EvidenceAggregate> entry : ordered.subList(from, to)) {
            EvidenceAggregate aggregate = entry.getValue();
            JsonNode report = aggregate.latestReport();
            JsonNode storage = report.path("storage");
            ObjectNode item = JSON.createObjectNode();
            item.put("evidenceId", entry.getKey().evidenceId());
            item.put("businessVersion", entry.getKey().businessVersion());
            item.put("businessStatus", report.path("chain").path("businessStatus").asText(""));
            item.put("operation", report.path("operation").asText(""));
            item.put("publishedAt", aggregate.publicationTime().toString());
            item.put("lastVerifiedAt", aggregate.latestTime().toString());
            item.put("sha256", storage.path("sha256").asText(""));
            item.put("size", storage.path("size").asLong(-1));
            item.put("cid", storage.path("cid").asText(""));
            item.put("contentAvailable", contentAvailable(
                    entry.getKey().evidenceId(), entry.getKey().businessVersion(), storage));
            item.put("reportCount", aggregate.reportCount());
            items.add(item);
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total);
        result.put("totalPages", totalPages);
        result.put("returned", items.size());
        result.put("hasPrevious", page > 1);
        result.put("hasNext", page < totalPages);
        result.put("truncated", false);
        result.set("items", items);
        return encodeCatalog(result, items, total);
    }

    private static boolean matchesQuery(EvidenceKey key, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return key.evidenceId().contains(query)
                || ("v" + key.businessVersion()).equals(query)
                || Long.toString(key.businessVersion()).equals(query);
    }

    /** Returns the newest successful report plus its exact verified JSON presentation copy. */
    public byte[] readEvidenceDetail(String evidenceId, long businessVersion) {
        requireEvidenceIdentity(evidenceId, businessVersion);
        EvidenceAggregate aggregate = evidenceReports().get(
                new EvidenceKey(evidenceId, businessVersion));
        if (aggregate == null) {
            return null;
        }
        JsonNode report = aggregate.latestReport();
        JsonNode storage = report.path("storage");
        ObjectNode result = JSON.createObjectNode();
        result.put("publishedAt", aggregate.publicationTime().toString());
        result.put("lastVerifiedAt", aggregate.latestTime().toString());
        result.set("report", report.deepCopy());

        ObjectNode content = JSON.createObjectNode();
        content.put("sha256", storage.path("sha256").asText(""));
        content.put("size", storage.path("size").asLong(-1));
        content.put("cid", storage.path("cid").asText(""));
        content.put("objectStoreVerified", storage.path("objectStateVerified").asBoolean(false));
        content.put("ipfsVerified", storage.path("ipfsPinVerified").asBoolean(false));
        addVerifiedContent(content, evidenceId, businessVersion, storage);
        result.set("content", content);
        return encodeBounded(result);
    }

    private void addVerifiedContent(ObjectNode result,
                                    String evidenceId,
                                    long businessVersion,
                                    JsonNode storage) {
        long expectedSize = storage.path("size").asLong(-1);
        String expectedSha256 = storage.path("sha256").asText("");
        if (expectedSize > MAX_CONTENT_PREVIEW_BYTES) {
            unavailable(result, "PREVIEW_TOO_LARGE");
            return;
        }
        byte[] content;
        try {
            content = BoundedFiles.read(contentPath(evidenceId, businessVersion),
                    MAX_CONTENT_PREVIEW_BYTES, true, false);
        } catch (IOException unavailable) {
            unavailable(result, "NOT_MATERIALIZED");
            return;
        }
        if (content.length != expectedSize
                || !Digests.hex(Digests.sha256(content)).equals(expectedSha256)) {
            unavailable(result, "INTEGRITY_MISMATCH");
            return;
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            StrictJson.parse(content);
        } catch (Exception notJson) {
            unavailable(result, "NON_JSON_CONTENT");
            return;
        }
        result.put("available", true);
        result.put("format", "json");
        result.put("mediaType", "application/json");
        result.put("text", text);
        result.put("integrityVerified", true);
    }

    private static void unavailable(ObjectNode result, String reason) {
        result.put("available", false);
        result.put("reason", reason);
        result.put("integrityVerified", false);
    }

    private Map<EvidenceKey, EvidenceAggregate> evidenceReports() {
        long stamp = evidenceIndexStamp();
        EvidenceIndex cached = cachedEvidenceIndex;
        if (cached != null && cached.stamp() == stamp) {
            return cached.evidence();
        }
        synchronized (this) {
            cached = cachedEvidenceIndex;
            if (cached != null && cached.stamp() == stamp) {
                return cached.evidence();
            }
            Map<EvidenceKey, EvidenceAggregate> scanned = scanEvidenceReports();
            EvidenceIndex refreshed = new EvidenceIndex(stamp, Map.copyOf(scanned));
            cachedEvidenceIndex = refreshed;
            return refreshed.evidence();
        }
    }

    private Map<EvidenceKey, EvidenceAggregate> scanEvidenceReports() {
        Map<EvidenceKey, EvidenceAggregate> result = new HashMap<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return result;
        }
        try (var files = Files.list(directory)) {
            List<Path> candidates = files
                    .filter(ReportStore::isVersionedScenarioReport)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparingLong(ReportStore::modifiedAt).reversed())
                    .limit(MAX_EVIDENCE_REPORT_FILES)
                    .toList();
            for (Path candidate : candidates) {
                JsonNode report;
                try {
                    report = JSON.readTree(BoundedFiles.read(
                            candidate, MAX_REPORT_BYTES, true, false));
                } catch (IOException | RuntimeException malformed) {
                    continue;
                }
                if (!validEvidenceReport(report)) {
                    continue;
                }
                String evidenceId = report.path("evidenceId").asText();
                long version = report.path("businessVersion").asLong();
                Instant finished;
                try {
                    finished = Instant.parse(report.path("finishedAt").asText());
                } catch (RuntimeException malformedTime) {
                    continue;
                }
                EvidenceKey key = new EvidenceKey(evidenceId, version);
                result.computeIfAbsent(key, ignored -> new EvidenceAggregate())
                        .observe(report, finished,
                                report.path("authenticatedStateChanged").asBoolean(false));
            }
            return result;
        } catch (IOException failure) {
            return Map.of();
        }
    }

    private long evidenceIndexStamp() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            return Long.MIN_VALUE;
        }
        try {
            long stamp = Files.getLastModifiedTime(
                    directory, LinkOption.NOFOLLOW_LINKS).hashCode();
            Path latest = directory.resolve(LATEST_FILE);
            stamp = 31 * stamp
                    + Files.getLastModifiedTime(latest, LinkOption.NOFOLLOW_LINKS).hashCode();
            stamp = 31 * stamp + Files.size(latest);
            return stamp;
        } catch (IOException unavailable) {
            return Long.MIN_VALUE + 1;
        }
    }

    private static boolean validEvidenceReport(JsonNode report) {
        return report != null && report.isObject()
                && report.path("schemaVersion").asInt(-1) == ScenarioReport.SCHEMA_VERSION
                && "PASS".equals(report.path("outcome").asText())
                && report.path("evidenceId").asText("").matches(EVIDENCE_ID)
                && report.path("businessVersion").canConvertToLong()
                && report.path("businessVersion").asLong() > 0
                && report.path("chain").isObject()
                && report.path("storage").isObject();
    }

    private static boolean isVersionedScenarioReport(Path path) {
        return path.getFileName().toString()
                .matches("report-[a-z0-9][a-z0-9-]{0,63}\\.json");
    }

    private boolean contentAvailable(String evidenceId, long businessVersion, JsonNode storage) {
        if (storage.path("size").asLong(-1) > MAX_CONTENT_PREVIEW_BYTES) {
            return false;
        }
        Path content = contentPath(evidenceId, businessVersion);
        return Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(content);
    }

    private Path contentPath(String evidenceId, long businessVersion) {
        return directory.resolve("content-" + evidenceId + "-v" + businessVersion + ".payload");
    }

    private static void requireEvidenceIdentity(String evidenceId, long businessVersion) {
        if (evidenceId == null || !evidenceId.matches(EVIDENCE_ID) || businessVersion < 1) {
            throw new IllegalArgumentException("invalid evidence identity");
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException unavailable) {
            return Long.MIN_VALUE;
        }
    }

    private static byte[] encode(ArrayNode reports) {
        try {
            byte[] encoded = JSON.writeValueAsBytes(reports);
            return encoded.length <= MAX_REPORT_BYTES ? encoded : new byte[]{'[', ']'};
        } catch (IOException impossible) {
            return new byte[]{'[', ']'};
        }
    }

    private static byte[] encodeBounded(JsonNode value) {
        try {
            byte[] encoded = JSON.writeValueAsBytes(value);
            if (encoded.length > MAX_REPORT_BYTES) {
                throw new DemoException(DemoError.REPORT_WRITE_FAILED);
            }
            return encoded;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException impossible) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
    }

    private static byte[] encodeCatalog(ObjectNode result, ArrayNode items, int total) {
        try {
            byte[] encoded = JSON.writeValueAsBytes(result);
            while (encoded.length > MAX_REPORT_BYTES && !items.isEmpty()) {
                items.remove(items.size() - 1);
                result.put("returned", items.size());
                result.put("truncated", items.size() < total);
                result.put("hasNext", true);
                encoded = JSON.writeValueAsBytes(result);
            }
            if (encoded.length > MAX_REPORT_BYTES) {
                throw new DemoException(DemoError.REPORT_WRITE_FAILED);
            }
            return encoded;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException impossible) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
    }

    private static void atomicWrite(Path target,
                                    byte[] bytes,
                                    Set<PosixFilePermission> permissions) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new DemoException(DemoError.REPORT_WRITE_FAILED);
        }
        Path temp = Files.createTempFile(target.getParent(), ".report-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.setPosixFilePermissions(temp, permissions);
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

    private record EvidenceKey(String evidenceId, long businessVersion) {
    }

    private record EvidenceIndex(long stamp, Map<EvidenceKey, EvidenceAggregate> evidence) {
    }

    private static final class EvidenceAggregate {
        private JsonNode latestReport;
        private Instant latestTime;
        private Instant publishedTime;
        private int reportCount;

        void observe(JsonNode report, Instant finished, boolean stateChanged) {
            reportCount++;
            if (latestTime == null || finished.isAfter(latestTime)) {
                latestTime = finished;
                latestReport = report.deepCopy();
            }
            if (stateChanged && (publishedTime == null || finished.isBefore(publishedTime))) {
                publishedTime = finished;
            }
        }

        JsonNode latestReport() {
            return latestReport;
        }

        Instant latestTime() {
            return latestTime;
        }

        Instant publicationTime() {
            return publishedTime == null ? latestTime : publishedTime;
        }

        int reportCount() {
            return reportCount;
        }
    }
}
