package com.bloxbean.cardano.yano.archive.api.internal;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.api.schema.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/**
 * Shared, strictly schema-driven JDBC query implementation. SQL identifiers
 * come only from {@link ArchiveSchemas}; caller values are always bound.
 */
public final class JdbcArchiveRepositorySet implements ArchiveRepositorySet {
    public static final String TABLE_FILTER = "__table";
    public static final String OFFSET_FILTER = "__offset";

    private final Function<ArchiveReadSession, Connection> connections;
    private final String tablePrefix;
    private final String coverageTable;

    /**
     * Table holding ADR-039 projection receipts, when this archive was written by the projection.
     *
     * <p>The projection never writes {@code archive_coverage} — that table describes replay-worker
     * jobs. Its equivalent truth is the receipt log, where every required section for a block
     * range committed in one transaction, so a committed range covers every projected dataset
     * uniformly. Without this, coverage over a projection archive reads as empty and every query
     * fails "history coverage is incomplete" over data that is fully present.
     */
    private final String receiptsTable;

    public JdbcArchiveRepositorySet(Function<ArchiveReadSession, Connection> connections,
                                    String tablePrefix, String coverageTable) {
        this(connections, tablePrefix, coverageTable, null);
    }

    public JdbcArchiveRepositorySet(Function<ArchiveReadSession, Connection> connections,
                                    String tablePrefix, String coverageTable, String receiptsTable) {
        this.receiptsTable = receiptsTable;
        this.connections = Objects.requireNonNull(connections, "connections");
        this.tablePrefix = Objects.requireNonNull(tablePrefix, "tablePrefix");
        this.coverageTable = Objects.requireNonNull(coverageTable, "coverageTable");
    }

    @Override
    public <T> ArchiveRepository<T> repository(ArchiveDatasetId dataset, Class<T> rowType) {
        Objects.requireNonNull(dataset, "dataset");
        if (rowType != ArchiveRecord.class) {
            throw new IllegalArgumentException("only ArchiveRecord is supported by the generic repository");
        }
        @SuppressWarnings("unchecked")
        ArchiveRepository<T> repository = (ArchiveRepository<T>) new RecordRepository(dataset);
        return repository;
    }

    private final class RecordRepository implements ArchiveRepository<ArchiveRecord> {
        private final ArchiveDatasetId dataset;
        private final ArchiveDatasetSchema schema;

        private RecordRepository(ArchiveDatasetId dataset) {
            this.dataset = dataset;
            this.schema = Objects.requireNonNull(ArchiveSchemas.schema(dataset), "dataset schema");
        }

        @Override
        public ArchiveDatasetId dataset() {
            return dataset;
        }

        @Override
        public ArchiveQueryResult<ArchiveRecord> query(ArchiveReadSession session, ArchiveQuery query) {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(query, "query");
            if (query.range().sourceKind() != dataset.sourceKind()) {
                throw new IllegalArgumentException("query range does not match dataset source");
            }
            Connection connection = connections.apply(session);
            ArchiveTableSchema table = selectedTable(query.filters());
            List<ArchiveColumn> columns = table.columns();
            Set<String> columnNames = new HashSet<>();
            columns.forEach(column -> columnNames.add(column.name()));
            String coordinate = coordinateColumn(dataset, columnNames);
            List<String> ordering = ordering(table, coordinate);
            String digest = filterDigest(dataset, table.physicalName(), query.filters(), query.range());
            validateCursor(dataset, schema.projectionVersion(), query, digest, session.generation(), ordering.size());

            List<Object> parameters = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(String.join(",", columns.stream().map(ArchiveColumn::name).toList()))
                    .append(" FROM ").append(tablePrefix).append(table.physicalName()).append(" WHERE 1=1");
            if (coordinate != null) {
                sql.append(" AND ").append(coordinate).append(" BETWEEN ? AND ?");
                parameters.add(query.range().startInclusive());
                parameters.add(query.range().endInclusive());
            }
            appendFilters(sql, parameters, query.filters(), columnNames);
            query.cursor().ifPresent(cursor -> appendCursor(sql, parameters, cursor, ordering, columns));
            sql.append(" ORDER BY ").append(String.join(",", ordering)).append(' ')
                    .append(query.order().name()).append(" LIMIT ?");
            parameters.add(query.limit() + 1);
            long offset = offset(query.filters());
            if (offset > 0) {
                if (query.cursor().isPresent()) throw new IllegalArgumentException("offset and cursor are mutually exclusive");
                sql.append(" OFFSET ?");
                parameters.add(offset);
            }

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bind(statement, parameters);
                List<ArchiveRecord> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        var values = new LinkedHashMap<String, Object>();
                        for (int index = 0; index < columns.size(); index++) {
                            Object value = result.getObject(index + 1);
                            if (value instanceof Blob blob) value = blob.getBytes(1, Math.toIntExact(blob.length()));
                            values.put(columns.get(index).name(), value);
                        }
                        rows.add(new ArchiveRecord(table.physicalName(), values));
                    }
                }
                boolean hasMore = rows.size() > query.limit();
                if (hasMore) rows.removeLast();
                ArchiveCoverage coverage = coverage(connection, session.generation());
                boolean complete = covers(coverage.completeRanges(), query.range());
                Optional<ArchivePageCursor> next = Optional.empty();
                if (complete && hasMore && !rows.isEmpty()) {
                    ArchiveRecord last = rows.getLast();
                    next = Optional.of(new ArchivePageCursor(dataset, schema.projectionVersion(), digest,
                            query.order(), query.range().endInclusive(), coverage.revision(),
                            ordering.stream().map(name -> encodeCursorValue(last.value(name))).toList()));
                }
                return new ArchiveQueryResult<>(rows, coverage, complete, next);
            } catch (SQLException e) {
                throw new ArchiveStoreException("archive query failed for " + dataset.logicalName(), e);
            }
        }

        private ArchiveCoverage coverage(Connection connection, long generation) throws SQLException {
            String sql = "SELECT projection_version,source_kind,range_start,range_end FROM "
                    + coverageTable + " WHERE dataset=? ORDER BY range_start";
            List<ArchiveRange> ranges = new ArrayList<>();
            int projectionVersion = schema.projectionVersion();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dataset.name());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        projectionVersion = result.getInt(1);
                        SourceKind kind = SourceKind.valueOf(result.getString(2));
                        ranges.add(kind == SourceKind.BLOCK
                                ? new BlockRange(result.getLong(3), result.getLong(4))
                                : new EpochRange(result.getLong(3), result.getLong(4)));
                    }
                }
            }
            // Fall back to the receipt log only when the worker wrote nothing. Preferring
            // receipts unconditionally would mask a genuinely incomplete legacy archive; falling
            // back only on an empty result keeps the legacy answer authoritative wherever it
            // exists, which the Phase 7 oracle run depends on.
            // Receipts prove BLOCK ranges. An epoch dataset is asked about in epoch terms, and a
            // receipt cannot say which epochs a block range contains, so epoch coverage is left
            // empty rather than fabricated - those reads fail closed instead of answering from
            // coverage nobody proved.
            if (ranges.isEmpty() && receiptsTable != null && dataset.sourceKind() == SourceKind.BLOCK) {
                ranges.addAll(receiptCoverage(connection));
            }
            return new ArchiveCoverage(dataset, projectionVersion, generation, merge(ranges));
        }

        /** Contiguous block ranges the projection has durably committed. */
        private List<ArchiveRange> receiptCoverage(Connection connection) throws SQLException {
            List<ArchiveRange> ranges = new ArrayList<>();
            // Absent on any archive the legacy worker wrote, which is the normal case.
            String unqualified = receiptsTable.substring(receiptsTable.lastIndexOf('.') + 1);
            try (PreparedStatement exists = connection.prepareStatement(
                    "SELECT 1 FROM information_schema.tables WHERE table_name = ? LIMIT 1")) {
                exists.setString(1, unqualified);
                try (ResultSet found = exists.executeQuery()) {
                    if (!found.next()) return ranges;
                }
            }
            String sql = "SELECT first_block,last_block FROM " + receiptsTable + " ORDER BY first_block";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long first = result.getLong(1);
                    long last = result.getLong(2);
                    ranges.add(new BlockRange(first, last));
                }
            }
            return ranges;
        }

        private ArchiveTableSchema selectedTable(Map<String, Object> filters) {
            String requested = Objects.toString(filters.getOrDefault(TABLE_FILTER,
                    schema.tables().getFirst().physicalName()));
            return schema.tables().stream().filter(table -> table.physicalName().equals(requested)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("table does not belong to dataset: " + requested));
        }
    }

    private static String coordinateColumn(ArchiveDatasetId dataset, Set<String> columns) {
        if (dataset.sourceKind() == SourceKind.BLOCK) {
            if (columns.contains("block_number")) return "block_number";
            if (columns.contains("first_seen_block_number")) return "first_seen_block_number";
        } else {
            if (columns.contains("epoch")) return "epoch";
            if (columns.contains("earned_epoch")) return "earned_epoch";
        }
        // Dimension tables may use a first-seen coordinate instead of a block
        // coordinate. Any future coordinate-free table remains guarded by its
        // parent dataset coverage.
        return null;
    }

    private static List<String> ordering(ArchiveTableSchema table, String coordinate) {
        Set<String> names = new HashSet<>();
        table.columns().forEach(column -> names.add(column.name()));
        List<String> order = new ArrayList<>();
        if (coordinate != null && names.contains(coordinate)) order.add(coordinate);
        for (String key : table.primaryKey()) if (!order.contains(key)) order.add(key);
        return List.copyOf(order);
    }

    private static void appendFilters(StringBuilder sql, List<Object> parameters,
                                      Map<String, Object> filters, Set<String> columnNames) {
        filters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getKey().startsWith("__")) return;
            if (!columnNames.contains(entry.getKey())) {
                throw new IllegalArgumentException("unsupported archive filter: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                sql.append(" AND ").append(entry.getKey()).append(" IS NULL");
            } else if (entry.getValue() instanceof Collection<?> values) {
                if (values.isEmpty()) {
                    sql.append(" AND 1=0");
                } else {
                    sql.append(" AND ").append(entry.getKey()).append(" IN (")
                            .append(String.join(",", Collections.nCopies(values.size(), "?"))).append(')');
                    parameters.addAll(values);
                }
            } else {
                sql.append(" AND ").append(entry.getKey()).append("=?");
                parameters.add(entry.getValue());
            }
        });
    }

    private static void appendCursor(StringBuilder sql, List<Object> parameters, ArchivePageCursor cursor,
                                     List<String> ordering, List<ArchiveColumn> columns) {
        Map<String, ArchiveColumn> byName = new HashMap<>();
        columns.forEach(column -> byName.put(column.name(), column));
        sql.append(" AND (").append(String.join(",", ordering)).append(") ")
                .append(cursor.order() == ArchivePageCursor.Order.ASC ? '>' : '<')
                .append(" (").append(String.join(",", Collections.nCopies(ordering.size(), "?"))).append(')');
        for (int index = 0; index < ordering.size(); index++) {
            parameters.add(decodeCursorValue(cursor.lastOrderingValues().get(index), byName.get(ordering.get(index))));
        }
    }

    private static void validateCursor(ArchiveDatasetId dataset, int projectionVersion, ArchiveQuery query,
                                       String digest, long generation, int orderSize) {
        query.cursor().ifPresent(cursor -> {
            if (cursor.dataset() != dataset || cursor.projectionVersion() != projectionVersion
                    || cursor.order() != query.order()
                    || !cursor.filterDigest().equals(digest) || cursor.coverageRevision() != generation
                    || cursor.archiveBoundary() != query.range().endInclusive()
                    || cursor.lastOrderingValues().size() != orderSize) {
                throw new IllegalArgumentException("archive cursor does not match the pinned query");
            }
        });
    }

    private static long offset(Map<String, Object> filters) {
        Object value = filters.get(OFFSET_FILTER);
        if (value == null) return 0;
        long offset = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
        if (offset < 0 || offset > 10_000_000L) throw new IllegalArgumentException("invalid archive query offset");
        return offset;
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof byte[] bytes) statement.setBytes(index + 1, bytes);
            else statement.setObject(index + 1, value);
        }
    }

    private static boolean covers(List<ArchiveRange> ranges, ArchiveRange requested) {
        long next = requested.startInclusive();
        for (ArchiveRange range : ranges) {
            if (range.endInclusive() < next) continue;
            if (range.startInclusive() > next) return false;
            if (range.endInclusive() >= requested.endInclusive()) return true;
            next = range.endInclusive() + 1;
        }
        return false;
    }

    private static List<ArchiveRange> merge(List<ArchiveRange> ranges) {
        if (ranges.isEmpty()) return List.of();
        List<ArchiveRange> merged = new ArrayList<>();
        ArchiveRange current = ranges.getFirst();
        for (int index = 1; index < ranges.size(); index++) {
            ArchiveRange next = ranges.get(index);
            if (next.startInclusive() <= current.endInclusive() + 1) {
                current = current.sourceKind() == SourceKind.BLOCK
                        ? new BlockRange(current.startInclusive(), Math.max(current.endInclusive(), next.endInclusive()))
                        : new EpochRange(current.startInclusive(), Math.max(current.endInclusive(), next.endInclusive()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private static String filterDigest(ArchiveDatasetId dataset, String table,
                                       Map<String, Object> filters, ArchiveRange range) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((dataset.name() + '|' + table + '|' + range.canonicalForm()).getBytes(StandardCharsets.UTF_8));
            filters.entrySet().stream().filter(entry -> !entry.getKey().equals(OFFSET_FILTER))
                    .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                        Object value = entry.getValue();
                        if (value instanceof byte[] bytes) digest.update(bytes);
                        else if (value instanceof Collection<?> values) values.forEach(item ->
                                digest.update(item instanceof byte[] bytes ? bytes
                                        : Objects.toString(item).getBytes(StandardCharsets.UTF_8)));
                        else digest.update(Objects.toString(value).getBytes(StandardCharsets.UTF_8));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encodeCursorValue(Object value) {
        if (value instanceof byte[] bytes) return "x:" + HexFormat.of().formatHex(bytes);
        if (value instanceof Number number) return "n:" + number;
        if (value instanceof Boolean bool) return "b:" + bool;
        return "s:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
    }

    private static Object decodeCursorValue(String encoded, ArchiveColumn column) {
        if (encoded.startsWith("x:")) return HexFormat.of().parseHex(encoded.substring(2));
        if (encoded.startsWith("n:")) {
            String number = encoded.substring(2);
            return switch (column.type()) {
                case INT32 -> Integer.parseInt(number);
                case INT64 -> Long.parseLong(number);
                case DECIMAL_38 -> new java.math.BigDecimal(number);
                default -> throw new IllegalArgumentException("numeric cursor for non-numeric column");
            };
        }
        if (encoded.startsWith("b:")) return Boolean.parseBoolean(encoded.substring(2));
        if (encoded.startsWith("s:")) return new String(Base64.getUrlDecoder().decode(encoded.substring(2)),
                StandardCharsets.UTF_8);
        throw new IllegalArgumentException("invalid archive cursor value");
    }
}
