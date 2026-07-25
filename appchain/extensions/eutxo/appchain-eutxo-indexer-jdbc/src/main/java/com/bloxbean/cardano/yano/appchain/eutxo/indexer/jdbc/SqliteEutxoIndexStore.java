package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexReader;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexWrite;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCheckpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCoverage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory.InMemoryEutxoIndexStore;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single-writer SQLite projection journal. Public reads use the same
 * storage-neutral model as every future backend.
 */
public final class SqliteEutxoIndexStore implements EutxoIndexStore {
    public static final String DEFAULT_FILE = "eutxo-lifecycle.db";
    public static final String MARKER_FILE = ".yano-eutxo-index";
    private static final String MIGRATION_LOCATION =
            "classpath:db/migration/eutxo/sqlite";

    private final EutxoIndexStoreContext context;
    private final String url;
    private final Connection connection;
    private InMemoryEutxoIndexStore projection;
    private boolean closed;

    private SqliteEutxoIndexStore(
            EutxoIndexStoreContext context,
            String url,
            Connection connection,
            InMemoryEutxoIndexStore projection
    ) {
        this.context = context;
        this.url = url;
        this.connection = connection;
        this.projection = projection;
    }

    public static SqliteEutxoIndexStore open(EutxoIndexStoreContext context) {
        Objects.requireNonNull(context, "context");
        String url = resolveUrl(context);
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(context.dataDirectory());
            rejectUnexpectedDatabase(url);
            Flyway flyway = flyway(url);
            flyway.validate();
            MigrateResult migrated = flyway.migrate();
            if (!migrated.success) {
                throw new IllegalStateException("Flyway migration did not succeed");
            }
            flyway.validate();
            Connection connection = DriverManager.getConnection(url);
            configure(connection);
            bindIdentity(connection, context.identity());
            writeMarker(context);
            InMemoryEutxoIndexStore projection = replay(
                    connection, context.identity());
            return new SqliteEutxoIndexStore(
                    context, url, connection, projection);
        } catch (SQLException | IOException | ClassNotFoundException failure) {
            throw new IllegalStateException(
                    "cannot open EUTxO SQLite index", failure);
        }
    }

    public static Flyway flyway(String url) {
        if (!isSqlite(url)) {
            throw new IllegalArgumentException(
                    "only jdbc:sqlite: is supported in index schema v1");
        }
        return Flyway.configure()
                .dataSource(url, "", "")
                .locations(MIGRATION_LOCATION)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .ignoreMigrationPatterns("*:pending")
                .load();
    }

    @Override
    public synchronized IndexIdentity identity() {
        return context.identity();
    }

    @Override
    public synchronized EutxoIndexWrite begin(SourcePoint source) {
        requireOpen();
        EutxoIndexWrite projectionWrite = projection.begin(source);
        boolean duplicate = source.appHeight()
                <= projection.checkpoint().source().appHeight();
        try {
            connection.setAutoCommit(false);
            return new Write(source, projectionWrite, duplicate);
        } catch (SQLException failure) {
            projectionWrite.abort();
            throw sql("cannot begin index block", failure);
        }
    }

    @Override
    public synchronized IndexCheckpoint checkpoint() {
        requireOpen();
        return projection.checkpoint();
    }

    @Override
    public synchronized void rollbackTo(SourcePoint source) {
        requireOpen();
        requireExactSource(source);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM source_block WHERE app_height > ?")) {
                statement.setLong(1, source.appHeight());
                statement.executeUpdate();
            }
            connection.commit();
            connection.setAutoCommit(true);
            projection.close();
            projection = replay(connection, identity());
        } catch (SQLException failure) {
            rollbackQuietly();
            throw sql("cannot rollback index", failure);
        }
    }

    @Override
    public synchronized EutxoIndexReader reader() {
        requireOpen();
        return projection.reader();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        projection.close();
        try {
            connection.close();
        } catch (SQLException failure) {
            throw sql("cannot close index", failure);
        }
    }

    public String jdbcUrl() {
        return url;
    }

    private final class Write implements EutxoIndexWrite {
        private final SourcePoint source;
        private final EutxoIndexWrite projectionWrite;
        private final boolean duplicate;
        private final List<EutxoIndexEvent> events = new ArrayList<>();
        private boolean finished;

        private Write(
                SourcePoint source,
                EutxoIndexWrite projectionWrite,
                boolean duplicate
        ) {
            this.source = source;
            this.projectionWrite = projectionWrite;
            this.duplicate = duplicate;
        }

        @Override
        public void apply(EutxoIndexEvent event) {
            requireActive();
            projectionWrite.apply(event);
            if (!duplicate) {
                events.add(event);
            }
        }

        @Override
        public void commit(IndexCheckpoint checkpoint) {
            requireActive();
            try {
                if (!duplicate) {
                    insertBlock(checkpoint);
                    for (int ordinal = 0; ordinal < events.size(); ordinal++) {
                        insertEvent(source.appHeight(), ordinal, events.get(ordinal));
                    }
                }
                connection.commit();
                connection.setAutoCommit(true);
                projectionWrite.commit(checkpoint);
                finished = true;
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly();
                projectionWrite.abort();
                finished = true;
                throw failure instanceof SQLException sqlFailure
                        ? sql("cannot commit index block", sqlFailure)
                        : (RuntimeException) failure;
            }
        }

        @Override
        public void abort() {
            if (finished) {
                return;
            }
            rollbackQuietly();
            projectionWrite.abort();
            finished = true;
        }

        private void insertBlock(IndexCheckpoint checkpoint)
                throws SQLException {
            if (!identity().digest().equals(checkpoint.identityDigest())
                    || !source.equals(checkpoint.source())) {
                throw new IllegalArgumentException(
                        "checkpoint identity or source differs from write");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_block("
                            + "app_height, app_block_hash, l1_slot, l1_block_hash,"
                            + " transaction_sequence, deposit_sequence,"
                            + " withdrawal_sequence, coverage)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, source.appHeight());
                statement.setString(2, source.appBlockHash());
                statement.setLong(3, source.l1Slot());
                statement.setString(4, source.l1BlockHash());
                statement.setLong(5, checkpoint.transactionSequence());
                statement.setLong(6, checkpoint.depositSequence());
                statement.setLong(7, checkpoint.withdrawalSequence());
                statement.setString(8, checkpoint.coverage().name());
                statement.executeUpdate();
            }
        }

        private void insertEvent(
                long appHeight,
                int ordinal,
                EutxoIndexEvent event
        ) throws SQLException {
            EncodedEvent encoded = encode(event);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO projection_event_journal("
                            + "app_height, event_ordinal, event_type,"
                            + " event_sequence, record_id, canonical_payload)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, appHeight);
                statement.setInt(2, ordinal);
                statement.setString(3, encoded.type());
                statement.setLong(4, event.sequence());
                statement.setString(5, encoded.recordId());
                statement.setBytes(6, encoded.payload());
                statement.executeUpdate();
            }
        }

        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("index write is already finished");
            }
        }
    }

    private static InMemoryEutxoIndexStore replay(
            Connection connection,
            IndexIdentity identity
    ) throws SQLException {
        InMemoryEutxoIndexStore store = new InMemoryEutxoIndexStore(identity);
        try (PreparedStatement blocks = connection.prepareStatement(
                "SELECT app_height, app_block_hash, l1_slot, l1_block_hash,"
                        + " transaction_sequence, deposit_sequence,"
                        + " withdrawal_sequence, coverage"
                        + " FROM source_block ORDER BY app_height");
             ResultSet rows = blocks.executeQuery()) {
            while (rows.next()) {
                SourcePoint source = new SourcePoint(
                        rows.getLong(1), rows.getString(2),
                        rows.getLong(3), rows.getString(4));
                IndexCheckpoint checkpoint = new IndexCheckpoint(
                        identity.digest(), source,
                        rows.getLong(5), rows.getLong(6), rows.getLong(7),
                        IndexCoverage.valueOf(rows.getString(8)));
                try (EutxoIndexWrite write = store.begin(source)) {
                    for (EutxoIndexEvent event : events(connection, source.appHeight())) {
                        write.apply(event);
                    }
                    write.commit(checkpoint);
                }
            }
        }
        return store;
    }

    private static List<EutxoIndexEvent> events(
            Connection connection,
            long appHeight
    ) throws SQLException {
        List<EutxoIndexEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_type, event_sequence, canonical_payload"
                        + " FROM projection_event_journal"
                        + " WHERE app_height = ? ORDER BY event_ordinal")) {
            statement.setLong(1, appHeight);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(decode(
                            rows.getString(1),
                            rows.getLong(2),
                            rows.getBytes(3)));
                }
            }
        }
        return events;
    }

    private static EncodedEvent encode(EutxoIndexEvent event) {
        if (event instanceof EutxoIndexEvent.Transaction transaction) {
            return new EncodedEvent(
                    "TRANSACTION", transaction.summary().transactionId(),
                    transaction.summary().encode());
        }
        if (event instanceof EutxoIndexEvent.Deposit deposit) {
            return new EncodedEvent(
                    "DEPOSIT",
                    deposit.record().claim().acceptedOutpoint().toString(),
                    deposit.record().encode());
        }
        EutxoIndexEvent.Withdrawal withdrawal =
                (EutxoIndexEvent.Withdrawal) event;
        return new EncodedEvent(
                "WITHDRAWAL", withdrawal.record().claim().claimId(),
                withdrawal.record().encode());
    }

    private static EutxoIndexEvent decode(
            String type,
            long sequence,
            byte[] payload
    ) {
        return switch (type) {
            case "TRANSACTION" -> new EutxoIndexEvent.Transaction(
                    sequence, EutxoTransactionSummary.decode(payload));
            case "DEPOSIT" -> new EutxoIndexEvent.Deposit(
                    sequence, EutxoDepositRecord.decode(payload));
            case "WITHDRAWAL" -> new EutxoIndexEvent.Withdrawal(
                    sequence, EutxoWithdrawalRecord.decode(payload));
            default -> throw new IllegalStateException(
                    "unsupported projection event type " + type);
        };
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void bindIdentity(
            Connection connection,
            IndexIdentity identity
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT identity_digest FROM index_metadata"
                        + " WHERE singleton_id = 1");
             ResultSet rows = query.executeQuery()) {
            if (rows.next()) {
                if (!identity.digest().equals(rows.getString(1))) {
                    throw new IllegalStateException(
                            "index identity does not match configured chain");
                }
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO index_metadata("
                        + "singleton_id, identity_digest, network, chain_id,"
                        + " state_machine_id, ledger_profile_digest,"
                        + " bridge_abi, validity_profile_digest)"
                        + " VALUES (1, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, identity.digest());
            insert.setString(2, identity.network());
            insert.setString(3, identity.chainId());
            insert.setString(4, identity.stateMachineId());
            insert.setString(5, identity.ledgerProfileDigest());
            insert.setInt(6, identity.bridgeAbi());
            insert.setString(7, identity.validityProfileDigest());
            insert.executeUpdate();
        }
    }

    private static void rejectUnexpectedDatabase(String url)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT name FROM sqlite_master"
                             + " WHERE type = 'table'"
                             + " AND name NOT LIKE 'sqlite_%'");
             ResultSet rows = statement.executeQuery()) {
            List<String> tables = new ArrayList<>();
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
            if (!tables.isEmpty()
                    && !tables.contains("flyway_schema_history")
                    && !tables.contains("index_metadata")) {
                throw new IllegalStateException(
                        "refusing to baseline an unexpected non-empty database");
            }
        }
    }

    private static String resolveUrl(EutxoIndexStoreContext context) {
        String configured = context.settings()
                .getOrDefault("jdbc.url", "").trim();
        String resolved = configured.isEmpty()
                ? "jdbc:sqlite:" + context.dataDirectory()
                .resolve(DEFAULT_FILE)
                .toString()
                : configured;
        if (!isSqlite(resolved) || resolved.contains("?")) {
            throw new IllegalArgumentException(
                    "SQLite index URL must be jdbc:sqlite:<path> without parameters");
        }
        return resolved;
    }

    private static boolean isSqlite(String url) {
        return url != null && url.startsWith("jdbc:sqlite:")
                && url.length() > "jdbc:sqlite:".length();
    }

    private static void writeMarker(EutxoIndexStoreContext context)
            throws IOException {
        Files.writeString(
                context.dataDirectory().resolve(MARKER_FILE),
                "yano-eutxo-index-v1\n" + context.identity().digest() + "\n",
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private void requireExactSource(SourcePoint source) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT app_block_hash, l1_slot, l1_block_hash"
                        + " FROM source_block WHERE app_height = ?")) {
            statement.setLong(1, source.appHeight());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                        || !source.appBlockHash().equals(rows.getString(1))
                        || source.l1Slot() != rows.getLong(2)
                        || !source.l1BlockHash().equals(rows.getString(3))) {
                    throw new IllegalStateException(
                            "rollback source is not retained exactly");
                }
            }
        } catch (SQLException failure) {
            throw sql("cannot verify rollback source", failure);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The original database failure remains the useful diagnostic.
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("index store is closed");
        }
    }

    private static IllegalStateException sql(
            String message,
            SQLException failure
    ) {
        return new IllegalStateException(message, failure);
    }

    private record EncodedEvent(
            String type,
            String recordId,
            byte[] payload
    ) {
    }
}
