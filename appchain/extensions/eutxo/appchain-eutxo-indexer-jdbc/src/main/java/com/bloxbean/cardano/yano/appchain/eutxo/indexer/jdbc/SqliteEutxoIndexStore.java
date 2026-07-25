package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.math.BigInteger;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Single-writer SQLite event journal and relational read projection.
 *
 * <p>The journal is the disposable database's rebuild source. Bounded public
 * reads execute against rollback-safe relational tables rather than replaying
 * the complete history into heap.</p>
 */
public final class SqliteEutxoIndexStore implements EutxoIndexStore {
    public static final String DEFAULT_FILE = "eutxo-lifecycle.db";
    public static final String MARKER_FILE = ".yano-eutxo-index";
    private static final String MIGRATION_LOCATION =
            "classpath:db/migration/eutxo/sqlite";

    private final EutxoIndexStoreContext context;
    private final String url;
    private final Connection connection;
    private boolean closed;

    private SqliteEutxoIndexStore(
            EutxoIndexStoreContext context,
            String url,
            Connection connection
    ) {
        this.context = context;
        this.url = url;
        this.connection = connection;
    }

    public static SqliteEutxoIndexStore open(EutxoIndexStoreContext context) {
        Objects.requireNonNull(context, "context");
        String url = resolveUrl(context);
        Connection connection = null;
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
            connection = DriverManager.getConnection(url);
            configure(connection);
            verifyIntegrity(connection);
            bindIdentity(connection, context.identity());
            recoverProjection(connection);
            writeMarker(context);
            return new SqliteEutxoIndexStore(
                    context, url, connection);
        } catch (SQLException | IOException | ClassNotFoundException failure) {
            closeQuietly(connection);
            throw new IllegalStateException(
                    "cannot open EUTxO SQLite index", failure);
        } catch (RuntimeException failure) {
            closeQuietly(connection);
            throw failure;
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
        Objects.requireNonNull(source, "source");
        IndexCheckpoint current = readCheckpoint(
                connection, context.identity());
        boolean duplicate = source.appHeight()
                <= current.source().appHeight();
        if (duplicate) {
            requireExactSource(source);
        } else {
            long expected = Math.addExact(
                    current.source().appHeight(), 1);
            if (source.appHeight() != expected) {
                throw new IllegalStateException(
                        "source block gap: expected " + expected
                                + " but received "
                                + source.appHeight());
            }
        }
        try {
            connection.setAutoCommit(false);
            return new Write(source, duplicate);
        } catch (SQLException failure) {
            throw sql("cannot begin index block", failure);
        }
    }

    @Override
    public synchronized IndexCheckpoint checkpoint() {
        requireOpen();
        return readCheckpoint(connection, context.identity());
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
            updateProjectedHeight(source.appHeight());
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException failure) {
            rollbackQuietly();
            throw sql("cannot rollback index", failure);
        }
    }

    @Override
    public synchronized EutxoIndexReader reader() {
        requireOpen();
        return new SqliteEutxoIndexReader(
                this, connection, this::checkpoint);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
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
        private final boolean duplicate;
        private final List<EutxoIndexEvent> events = new ArrayList<>();
        private boolean finished;

        private Write(
                SourcePoint source,
                boolean duplicate
        ) {
            this.source = source;
            this.duplicate = duplicate;
        }

        @Override
        public void apply(EutxoIndexEvent event) {
            requireActive();
            if (!duplicate) {
                events.add(Objects.requireNonNull(event, "event"));
            }
        }

        @Override
        public void commit(IndexCheckpoint checkpoint) {
            requireActive();
            synchronized (SqliteEutxoIndexStore.this) {
                try {
                    if (duplicate) {
                        if (!checkpoint.equals(
                                SqliteEutxoIndexStore.this.checkpoint())) {
                            throw new IllegalStateException(
                                    "duplicate replay checkpoint differs");
                        }
                    } else {
                        insertBlock(checkpoint);
                        for (int ordinal = 0;
                             ordinal < events.size();
                             ordinal++) {
                            insertEvent(
                                    source.appHeight(), ordinal,
                                    events.get(ordinal));
                        }
                        updateProjectedHeight(source.appHeight());
                    }
                    connection.commit();
                    connection.setAutoCommit(true);
                    finished = true;
                } catch (SQLException | RuntimeException failure) {
                    rollbackQuietly();
                    finished = true;
                    throw failure instanceof SQLException sqlFailure
                            ? sql("cannot commit index block", sqlFailure)
                            : (RuntimeException) failure;
                }
            }
        }

        @Override
        public void abort() {
            synchronized (SqliteEutxoIndexStore.this) {
                if (finished) {
                    return;
                }
                rollbackQuietly();
                finished = true;
            }
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
            projectEvent(
                    connection, appHeight, ordinal,
                    event, encoded.payload());
        }

        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("index write is already finished");
            }
        }
    }

    private static void recoverProjection(Connection connection)
            throws SQLException {
        long sourceHeight = maximumHeight(
                connection, "source_block");
        long projectedHeight;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT projected_height FROM projection_state"
                        + " WHERE singleton_id = 1");
             ResultSet rows = statement.executeQuery()) {
            projectedHeight = rows.next() ? rows.getLong(1) : -1;
        }
        if (sourceHeight == projectedHeight) {
            return;
        }
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM indexed_withdrawal_version");
            statement.executeUpdate(
                    "DELETE FROM indexed_address_activity");
            statement.executeUpdate(
                    "DELETE FROM indexed_transaction_input");
            statement.executeUpdate(
                    "DELETE FROM indexed_transaction_output");
            statement.executeUpdate("DELETE FROM indexed_deposit");
            statement.executeUpdate("DELETE FROM indexed_transaction");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT app_height, event_ordinal, event_type,"
                        + " event_sequence, canonical_payload"
                        + " FROM projection_event_journal"
                        + " ORDER BY app_height, event_ordinal");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                EutxoIndexEvent event = decode(
                        rows.getString(3),
                        rows.getLong(4),
                        rows.getBytes(5));
                projectEvent(
                        connection, rows.getLong(1),
                        rows.getInt(2), event, rows.getBytes(5));
            }
        }
        updateProjectedHeight(connection, sourceHeight);
        connection.commit();
        connection.setAutoCommit(true);
    }

    private static long maximumHeight(
            Connection connection,
            String table
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(app_height), 0) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static void projectEvent(
            Connection connection,
            long appHeight,
            int ordinal,
            EutxoIndexEvent event,
            byte[] payload
    ) throws SQLException {
        if (event instanceof EutxoIndexEvent.Transaction transaction) {
            projectTransaction(
                    connection, appHeight, transaction, payload);
        } else if (event instanceof EutxoIndexEvent.Deposit deposit) {
            projectDeposit(
                    connection, appHeight, deposit, payload);
        } else if (event instanceof EutxoIndexEvent.Withdrawal withdrawal) {
            projectWithdrawal(
                    connection, appHeight, ordinal,
                    withdrawal, payload);
        }
    }

    private static void projectTransaction(
            Connection connection,
            long appHeight,
            EutxoIndexEvent.Transaction event,
            byte[] payload
    ) throws SQLException {
        EutxoTransactionSummary value = event.summary();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO indexed_transaction("
                        + "event_sequence, transaction_id, message_id,"
                        + " status, canonical_payload, app_height)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, event.sequence());
            statement.setString(2, value.transactionId());
            statement.setString(3, value.messageId());
            statement.setString(4, value.status().name());
            statement.setBytes(5, payload);
            statement.setLong(6, appHeight);
            statement.executeUpdate();
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (var input : value.inputs()) {
            addresses.add(input.address());
            if (value.status()
                    == EutxoTransactionSummary.Status.ACCEPTED) {
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "INSERT INTO"
                                             + " indexed_transaction_input("
                                             + "transaction_id,"
                                             + " input_outpoint,"
                                             + " parent_transaction_id,"
                                             + " address, lovelace,"
                                             + " app_height)"
                                             + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, value.transactionId());
                    statement.setString(
                            2, input.outpoint().toString());
                    statement.setString(
                            3, input.outpoint().transactionId());
                    statement.setString(4, input.address());
                    statement.setString(
                            5, input.lovelace().toString());
                    statement.setLong(6, appHeight);
                    statement.executeUpdate();
                }
            }
        }
        for (var output : value.outputs()) {
            addresses.add(output.address());
            if (value.status()
                    == EutxoTransactionSummary.Status.ACCEPTED) {
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "INSERT INTO"
                                             + " indexed_transaction_output("
                                             + "outpoint, transaction_id,"
                                             + " address, lovelace,"
                                             + " app_height)"
                                             + " VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(
                            1, output.outpoint().toString());
                    statement.setString(2, value.transactionId());
                    statement.setString(3, output.address());
                    statement.setString(
                            4, output.lovelace().toString());
                    statement.setLong(5, appHeight);
                    statement.executeUpdate();
                }
            }
        }
        for (String address : addresses) {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "INSERT INTO indexed_address_activity("
                                         + "address, event_sequence,"
                                         + " transaction_id, app_height)"
                                         + " VALUES (?, ?, ?, ?)")) {
                statement.setString(1, address);
                statement.setLong(2, event.sequence());
                statement.setString(3, value.transactionId());
                statement.setLong(4, appHeight);
                statement.executeUpdate();
            }
        }
    }

    private static void projectDeposit(
            Connection connection,
            long appHeight,
            EutxoIndexEvent.Deposit event,
            byte[] payload
    ) throws SQLException {
        EutxoDepositRecord value = event.record();
        BigInteger lovelace = outputLovelace(
                value.claim().mirroredOutputCbor());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO indexed_deposit("
                        + "event_sequence, accepted_outpoint,"
                        + " mirrored_outpoint, mirrored_transaction_id,"
                        + " address, lovelace, canonical_payload,"
                        + " app_height)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, event.sequence());
            statement.setString(
                    2, value.claim().acceptedOutpoint().toString());
            statement.setString(
                    3, value.mirroredOutpoint().toString());
            statement.setString(
                    4, value.mirroredOutpoint().transactionId());
            statement.setString(5, value.claim().l2Address());
            statement.setString(6, lovelace.toString());
            statement.setBytes(7, payload);
            statement.setLong(8, appHeight);
            statement.executeUpdate();
        }
    }

    private static void projectWithdrawal(
            Connection connection,
            long appHeight,
            int ordinal,
            EutxoIndexEvent.Withdrawal event,
            byte[] payload
    ) throws SQLException {
        EutxoWithdrawalRecord value = event.record();
        requireWithdrawalIdentity(connection, event.sequence(), value);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO indexed_withdrawal_version("
                        + "app_height, event_ordinal, event_sequence,"
                        + " claim_id, status, withdrawal_outpoint,"
                        + " canonical_payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, appHeight);
            statement.setInt(2, ordinal);
            statement.setLong(3, event.sequence());
            statement.setString(4, value.claim().claimId());
            statement.setString(5, value.status().name());
            statement.setString(
                    6, value.claim().withdrawalOutpoint().toString());
            statement.setBytes(7, payload);
            statement.executeUpdate();
        }
    }

    private static void requireWithdrawalIdentity(
            Connection connection,
            long sequence,
            EutxoWithdrawalRecord incoming
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_sequence, canonical_payload"
                        + " FROM indexed_withdrawal_version"
                        + " WHERE event_sequence = ? OR claim_id = ?"
                        + " ORDER BY app_height DESC LIMIT 1")) {
            statement.setLong(1, sequence);
            statement.setString(2, incoming.claim().claimId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                EutxoWithdrawalRecord existing =
                        EutxoWithdrawalRecord.decode(rows.getBytes(2));
                if (rows.getLong(1) != sequence
                        || !existing.claim().equals(incoming.claim())) {
                    throw new IllegalStateException(
                            "withdrawal identity maps to another claim");
                }
            }
        }
    }

    private static BigInteger outputLovelace(byte[] outputCbor) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    CborSerializationUtil.deserialize(outputCbor));
            return output.getValue().getCoin();
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "committed deposit output cannot be decoded",
                    failure);
        }
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

    private static IndexCheckpoint readCheckpoint(
            Connection connection,
            IndexIdentity identity
    ) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT app_height, app_block_hash, l1_slot,"
                        + " l1_block_hash, transaction_sequence,"
                        + " deposit_sequence, withdrawal_sequence,"
                        + " coverage FROM source_block"
                        + " ORDER BY app_height DESC LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return IndexCheckpoint.origin(identity);
            }
            SourcePoint source = new SourcePoint(
                    rows.getLong(1), rows.getString(2),
                    rows.getLong(3), rows.getString(4));
            return new IndexCheckpoint(
                    identity.digest(), source,
                    rows.getLong(5), rows.getLong(6),
                    rows.getLong(7),
                    IndexCoverage.valueOf(rows.getString(8)));
        } catch (SQLException failure) {
            throw sql("cannot read index checkpoint", failure);
        }
    }

    private void updateProjectedHeight(long appHeight)
            throws SQLException {
        updateProjectedHeight(connection, appHeight);
    }

    private static void updateProjectedHeight(
            Connection connection,
            long appHeight
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE projection_state SET projected_height = ?"
                        + " WHERE singleton_id = 1")) {
            statement.setLong(1, appHeight);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "projection state row is unavailable");
            }
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void verifyIntegrity(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1))) {
                throw new IllegalStateException(
                        "EUTxO index database failed SQLite integrity check");
            }
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
                            "source identity differs; rollback source"
                                    + " is not retained exactly");
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

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the original startup failure.
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
