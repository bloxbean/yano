package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.api.schema.*;
import com.bloxbean.cardano.yano.archive.core.address.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.worker.*;
import org.flywaydb.core.Flyway;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entire relational ADR-036 hot layer. The database is private to one Yano process. */
public final class SqliteHotHistoryStore implements HotHistoryStore {
    private final Path file;
    private final String jdbcUrl;
    private final Connection writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteHotHistoryStore(Path file) {
        try {
            this.file = file.toAbsolutePath().normalize();
            Path parent = this.file.getParent();
            if (parent != null) Files.createDirectories(parent);
            jdbcUrl = "jdbc:sqlite:" + this.file;
            Flyway.configure(getClass().getClassLoader()).dataSource(jdbcUrl, null, null)
                    .locations("classpath:db/migration/history-hot-sqlite")
                    .baselineOnMigrate(false).load().migrate();
            writer = openConnection();
            installFactTables(writer);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot open SQLite hot-history store", e);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA temp_store=MEMORY");
        }
        return connection;
    }

    private void installFactTables(Connection connection) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (var entry : ArchiveSchemas.all().entrySet()) {
                if (entry.getKey().sourceKind() != SourceKind.BLOCK) continue;
                for (ArchiveTableSchema table : entry.getValue().tables()) {
                    StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                            .append(hotTable(table.physicalName()))
                            .append(" (hot_key BLOB PRIMARY KEY");
                    for (ArchiveColumn column : table.columns()) {
                        ddl.append(',').append(identifier(column.name())).append(' ')
                                .append(sqlType(column.type()));
                        if (!column.nullable()) ddl.append(" NOT NULL");
                    }
                    ddl.append(')');
                    try (Statement statement = connection.createStatement()) { statement.execute(ddl.toString()); }
                    if (!table.primaryKey().isEmpty()) {
                        String keys = table.primaryKey().stream().map(this::identifier)
                                .collect(java.util.stream.Collectors.joining(","));
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("CREATE INDEX IF NOT EXISTS "
                                    + identifier("idx_hot_" + table.physicalName() + "_logical") + " ON "
                                    + hotTable(table.physicalName()) + "(" + keys + ")");
                        }
                    }
                    String coordinate = table.columns().stream().map(ArchiveColumn::name)
                            .filter("block_number"::equals).findFirst().orElse(null);
                    if (coordinate != null) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("CREATE INDEX IF NOT EXISTS " + identifier("idx_hot_" + table.physicalName() + "_block")
                                    + " ON " + hotTable(table.physicalName()) + "(" + identifier(coordinate) + ")");
                        }
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO hot_fact_schema(dataset,table_name,projection_version) VALUES(?,?,?) "
                                    + "ON CONFLICT(dataset,table_name) DO UPDATE SET projection_version=excluded.projection_version")) {
                        insert.setString(1, entry.getKey().name());
                        insert.setString(2, table.physicalName());
                        insert.setInt(3, entry.getValue().projectionVersion());
                        insert.executeUpdate();
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally { connection.setAutoCommit(auto); }
    }

    @Override public synchronized void applyBlock(ArchiveDatasetId dataset, HotBlockCheckpoint block,
                                                   List<HotHistoryOperation> operations,
                                                   ArchiveProgress progress) {
        applyBlocks(dataset, List.of(new HotBlockUpdate(block, operations)), progress, null);
    }

    @Override public synchronized void applyBlocks(ArchiveDatasetId dataset, List<HotBlockUpdate> blocks,
                                                    ArchiveProgress progress, ArchiveReceipt receipt) {
        requireOpen();
        if (blocks.isEmpty() || progress.dataset() != dataset
                || progress.coordinate() != blocks.getLast().checkpoint().blockNumber()) {
            throw new IllegalArgumentException("progress does not match hot block range");
        }
        transaction(connection -> {
            for (HotBlockUpdate update : blocks) {
                for (HotHistoryOperation operation : update.operations()) {
                    applyOperation(connection, dataset, update.checkpoint(), operation);
                }
                putCheckpoint(connection, dataset, progress.track(), update.checkpoint());
            }
            putProgress(connection, progress);
            advanceRequirement(connection, progress);
            if (receipt != null) putReceipt(connection, receipt);
        }, "SQLite hot-history block apply failed");
    }

    private void applyOperation(Connection connection, ArchiveDatasetId dataset,
                                HotBlockCheckpoint block, HotHistoryOperation operation) throws SQLException {
        if (operation instanceof HotHistoryOperation.Fact fact) {
            insertFact(connection, dataset, fact.row());
        } else if (operation instanceof HotHistoryOperation.OutputCreated created) {
            insertResolverOutput(connection, created, block);
        } else if (operation instanceof HotHistoryOperation.OutputConsumed consumed) {
            insertResolverSpend(connection, consumed, block);
        } else if (operation instanceof HotHistoryOperation.PointerRegistered registered) {
            insertPointerRegistration(connection, dataset, registered, block);
        } else if (operation instanceof HotHistoryOperation.PointerDeregistered deregistered) {
            insertPointerDeregistration(connection, dataset, deregistered, block);
        }
    }

    private void insertFact(Connection connection, ArchiveDatasetId dataset, ArchiveRow row) throws SQLException {
        ArchiveTableSchema table = table(dataset, row.table());
        if (row.values().size() != table.columns().size()) throw new ArchiveStoreException("hot fact shape mismatch");
        byte[] key = HotArchiveRows.key(dataset, row);
        String columns = table.columns().stream().map(c -> identifier(c.name()))
                .collect(java.util.stream.Collectors.joining(","));
        String marks = String.join(",", Collections.nCopies(table.columns().size() + 1, "?"));
        String sql = "INSERT OR IGNORE INTO " + hotTable(table.physicalName())
                + "(hot_key," + columns + ") VALUES(" + marks + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, key);
            for (int i = 0; i < table.columns().size(); i++) bind(statement, i + 2,
                    table.columns().get(i), row.values().get(i));
            if (statement.executeUpdate() == 1) return;
        }
        ArchiveRecord existing = readFactByKey(connection, table, key).orElseThrow();
        for (int i = 0; i < table.columns().size(); i++) {
            if (!same(existing.value(table.columns().get(i).name()), row.values().get(i))) {
                throw new ArchiveStoreException("conflicting hot fact " + row.table());
            }
        }
    }

    private void insertResolverOutput(Connection connection, HotHistoryOperation.OutputCreated created,
                                      HotBlockCheckpoint block) throws SQLException {
        String sql = "INSERT OR IGNORE INTO resolver_outputs VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindResolverOutput(statement, created.outpoint(), created.output(),
                    block.blockNumber(), block.slot(), block.blockHash(), "BLOCK");
            if (statement.executeUpdate() == 1) return;
        }
        // An already-consumed output can still be encountered during an
        // identical deterministic replay. Compare the immutable creation
        // fact; consumption is validated independently below.
        ResolvedOutput existing = resolveOutput(connection, created.outpoint(), true)
                .orElseThrow(() -> new ArchiveStoreException("resolver outpoint is missing"));
        if (!sameOutput(existing, created.output())) throw new ArchiveStoreException("conflicting resolver outpoint");
    }

    private void insertResolverSpend(Connection connection, HotHistoryOperation.OutputConsumed consumed,
                                     HotBlockCheckpoint block) throws SQLException {
        if (resolveOutput(connection, consumed.outpoint(), true).isEmpty()) {
            throw new ArchiveStoreException("cannot consume missing resolver outpoint");
        }
        String sql = "INSERT OR IGNORE INTO resolver_spends VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, consumed.outpoint().txHash());
            statement.setInt(2, consumed.outpoint().outputIndex()); statement.setBytes(3, consumed.spendingTxHash());
            statement.setLong(4, block.blockNumber()); statement.setLong(5, block.slot());
            statement.setBytes(6, block.blockHash()); statement.setString(7, consumed.inputRole());
            if (statement.executeUpdate() == 1) return;
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT spending_tx_hash,input_role FROM resolver_spends "
                + "WHERE referenced_tx_hash=? AND referenced_output_index=?")) {
            query.setBytes(1, consumed.outpoint().txHash());
            query.setInt(2, consumed.outpoint().outputIndex());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !Arrays.equals(result.getBytes(1), consumed.spendingTxHash())
                        || !Objects.equals(result.getString(2), consumed.inputRole())) {
                    throw new ArchiveStoreException("conflicting resolver spend");
                }
            }
        }
    }

    private void insertPointerRegistration(Connection connection, ArchiveDatasetId dataset,
                                           HotHistoryOperation.PointerRegistered value,
                                           HotBlockCheckpoint block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO pointer_registrations VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, dataset.name());
            statement.setLong(2, value.slot()); statement.setInt(3, value.txIndex());
            statement.setInt(4, value.certIndex()); statement.setString(5, value.credentialType());
            statement.setBytes(6, value.credential()); statement.setLong(7, block.blockNumber());
            statement.setBytes(8, block.blockHash());
            if (statement.executeUpdate() == 1) return;
        }
        var pointer = new SequentialPointerResolver.PointerCoordinate(value.slot(), value.txIndex(), value.certIndex());
        var existing = resolvePointer(connection, dataset, pointer, false).orElse(null);
        if (existing == null || !existing.type().equals(value.credentialType())
                || !Arrays.equals(existing.hash(), value.credential())) {
            throw new ArchiveStoreException("conflicting pointer registration");
        }
    }

    private void insertPointerDeregistration(Connection connection, ArchiveDatasetId dataset,
                                             HotHistoryOperation.PointerDeregistered value,
                                             HotBlockCheckpoint block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO pointer_deregistrations VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, dataset.name());
            statement.setString(2, value.credentialType()); statement.setBytes(3, value.credential());
            statement.setLong(4, block.blockNumber()); statement.setBytes(5, block.blockHash());
            statement.setLong(6, value.slot()); statement.setInt(7, value.txIndex());
            statement.setInt(8, value.certIndex()); statement.executeUpdate();
        }
    }

    @Override public synchronized void seedResolver(
            Iterable<SequentialOutpointResolver.Entry> outputs, boolean complete, long baseBlock) {
        requireOpen();
        transaction(connection -> {
            for (var entry : outputs) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO resolver_outputs VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    bindResolverOutput(statement, entry.outpoint(), entry.output(),
                            null, null, null, "SEED");
                    int inserted = statement.executeUpdate();
                    if (inserted == 0) {
                        var existing = resolveOutput(connection, entry.outpoint(), true).orElseThrow();
                        if (!sameOutput(existing, entry.output())) throw new ArchiveStoreException("resolver seed conflict");
                    }
                }
            }
            if (complete) try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO resolver_seeds(singleton,base_block) VALUES(1,?) "
                            + "ON CONFLICT(singleton) DO UPDATE SET base_block=excluded.base_block")) {
                statement.setLong(1, baseBlock); statement.executeUpdate();
            }
        }, "SQLite resolver seed failed");
    }

    private void bindResolverOutput(PreparedStatement statement, Outpoint outpoint,
                                    ResolvedOutput output, Long block, Long slot, byte[] hash,
                                    String source) throws SQLException {
        statement.setBytes(1, outpoint.txHash());
        statement.setInt(2, outpoint.outputIndex()); statement.setBytes(3, output.addressKey());
        statement.setString(4, output.address()); statement.setBytes(5, output.paymentCredential());
        statement.setString(6, output.stakeCredentialType()); statement.setBytes(7, output.stakeCredential());
        nullableLong(statement, 8, block); nullableLong(statement, 9, slot); statement.setBytes(10, hash);
        statement.setString(11, source);
    }

    @Override public synchronized boolean resolverSeeded() {
        requireOpen();
        return resolverBaseBlock().isPresent();
    }

    @Override public synchronized OptionalLong resolverBaseBlock() {
        requireOpen();
        try (PreparedStatement statement = writer.prepareStatement(
                "SELECT base_block FROM resolver_seeds WHERE singleton=1")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? OptionalLong.of(result.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) { throw failure("resolver seed read failed", e); }
    }

    @Override public synchronized Optional<ResolvedOutput> resolveOutput(Outpoint outpoint) {
        requireOpen();
        try { return resolveOutput(writer, outpoint, false); }
        catch (SQLException e) { throw failure("resolver read failed", e); }
    }

    private Optional<ResolvedOutput> resolveOutput(Connection connection, Outpoint outpoint,
                                                   boolean includeSpent) throws SQLException {
        String sql = "SELECT o.address_key,o.address,o.payment_credential,o.stake_credential_type,o.stake_credential "
                + "FROM resolver_outputs o WHERE o.tx_hash=? AND o.output_index=?"
                + (includeSpent ? "" : " AND NOT EXISTS (SELECT 1 FROM resolver_spends s WHERE "
                + "s.referenced_tx_hash=o.tx_hash AND s.referenced_output_index=o.output_index)");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, outpoint.txHash());
            statement.setInt(2, outpoint.outputIndex());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ResolvedOutput(result.getBytes(1), result.getString(2),
                        result.getBytes(3), result.getString(4), result.getBytes(5))) : Optional.empty();
            }
        }
    }

    @Override public synchronized Optional<SequentialPointerResolver.ResolvedStakeCredential> resolvePointer(
            ArchiveDatasetId dataset, SequentialPointerResolver.PointerCoordinate pointer) {
        requireOpen();
        try { return resolvePointer(writer, dataset, pointer, true); }
        catch (SQLException e) { throw failure("pointer read failed", e); }
    }

    private Optional<SequentialPointerResolver.ResolvedStakeCredential> resolvePointer(Connection connection,
            ArchiveDatasetId dataset, SequentialPointerResolver.PointerCoordinate pointer,
            boolean honorDeregistration) throws SQLException {
        String sql = "SELECT r.credential_type,r.credential FROM pointer_registrations r WHERE r.dataset=? "
                + "AND r.pointer_slot=? AND r.pointer_tx_index=? AND r.pointer_cert_index=?"
                + (honorDeregistration ? " AND NOT EXISTS (SELECT 1 FROM pointer_deregistrations d WHERE d.dataset=r.dataset "
                + "AND d.credential_type=r.credential_type AND d.credential=r.credential)" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dataset.name());
            statement.setLong(2, pointer.slot()); statement.setInt(3, pointer.txIndex());
            statement.setInt(4, pointer.certIndex());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new SequentialPointerResolver.ResolvedStakeCredential(
                        result.getString(1), result.getBytes(2))) : Optional.empty();
            }
        }
    }

    @Override public synchronized List<SequentialPointerResolver.PointerCoordinate> pointersForCredential(
            ArchiveDatasetId dataset, SequentialPointerResolver.ResolvedStakeCredential credential) {
        requireOpen();
        String sql = "SELECT pointer_slot,pointer_tx_index,pointer_cert_index FROM pointer_registrations r "
                + "WHERE dataset=? AND credential_type=? AND credential=? AND NOT EXISTS "
                + "(SELECT 1 FROM pointer_deregistrations d WHERE d.dataset=r.dataset "
                + "AND d.credential_type=r.credential_type AND d.credential=r.credential)";
        try (PreparedStatement statement = writer.prepareStatement(sql)) {
            statement.setString(1, dataset.name());
            statement.setString(2, credential.type()); statement.setBytes(3, credential.hash());
            List<SequentialPointerResolver.PointerCoordinate> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new SequentialPointerResolver.PointerCoordinate(
                        rows.getLong(1), rows.getInt(2), rows.getInt(3)));
            }
            return List.copyOf(result);
        } catch (SQLException e) { throw failure("pointer credential read failed", e); }
    }

    @Override public synchronized void resetResolver(ArchiveDatasetId dataset) {
        requireOpen();
        transaction(connection -> {
            if (dataset == ArchiveDatasetId.ADDRESS_TRANSACTION) {
                execute(connection, "DELETE FROM resolver_seeds");
                execute(connection, "DELETE FROM resolver_outputs");
            }
            execute(connection, "DELETE FROM pointer_deregistrations WHERE dataset=?", dataset.name());
            execute(connection, "DELETE FROM pointer_registrations WHERE dataset=?", dataset.name());
        }, "SQLite resolver reset failed");
    }

    @Override public synchronized void rollbackTo(ArchiveDatasetId dataset, ArchiveTrack track, long commonBlock) {
        requireOpen();
        ArchiveProgress current = load(dataset, track).orElse(null);
        if (current == null || current.coordinate() <= commonBlock) return;
        transaction(connection -> rollback(connection, dataset, track, commonBlock), "SQLite rollback failed");
    }

    @Override public synchronized void resetTrackFrom(ArchiveDatasetId dataset, ArchiveTrack track, long firstBlock) {
        requireOpen();
        transaction(connection -> rollback(connection, dataset, track, firstBlock - 1),
                "SQLite activation reset failed");
    }

    private void rollback(Connection connection, ArchiveDatasetId dataset, ArchiveTrack track,
                          long commonBlock) throws SQLException {
        for (ArchiveTableSchema table : ArchiveSchemas.schema(dataset).tables()) {
            if (hasColumn(table, "block_number")) execute(connection, "DELETE FROM " + hotTable(table.physicalName())
                    + " WHERE block_number>?", commonBlock);
        }
        if (dataset == ArchiveDatasetId.ADDRESS_TRANSACTION) {
            execute(connection, "DELETE FROM resolver_spends WHERE spending_block_number>?", commonBlock);
            execute(connection, "DELETE FROM resolver_outputs WHERE source_kind='BLOCK' "
                    + "AND created_block_number>?", commonBlock);
        }
        if (dataset == ArchiveDatasetId.ADDRESS_TRANSACTION || dataset == ArchiveDatasetId.UTXO_HISTORY) {
            execute(connection, "DELETE FROM pointer_deregistrations WHERE dataset=? AND block_number>?",
                    dataset.name(), commonBlock);
            execute(connection, "DELETE FROM pointer_registrations WHERE dataset=? "
                    + "AND registered_block_number>?", dataset.name(), commonBlock);
        }
        execute(connection, "DELETE FROM block_checkpoints WHERE dataset=? AND track=? AND block_number>?",
                dataset.name(), track.name(), commonBlock);
        if (commonBlock < 0) execute(connection, "DELETE FROM projection_progress WHERE dataset=? AND track=?",
                dataset.name(), track.name());
        else {
            HotBlockCheckpoint point = checkpoint(connection, dataset, track, commonBlock).orElseThrow(() ->
                    new ArchiveStoreException("missing checkpoint at rollback target"));
            ArchiveProgress prior = load(connection, dataset, track).orElseThrow();
            putProgress(connection, new ArchiveProgress(dataset, track, commonBlock, point.slot(),
                    point.blockHash(), prior.backendGeneration()));
        }
        if (dataset.sourceKind() == SourceKind.BLOCK) {
            putRequirement(connection, dataset, Math.addExact(commonBlock, 1));
        }
    }

    @Override public synchronized void pruneUndoThrough(ArchiveDatasetId dataset, ArchiveTrack track,
                                                        long blockInclusive) {
        requireOpen();
        transaction(connection -> execute(connection, "DELETE FROM block_checkpoints WHERE dataset=? AND track=? "
                + "AND block_number<=?", dataset.name(), track.name(), blockInclusive),
                "SQLite checkpoint prune failed");
    }

    @Override public synchronized void pruneResolverThrough(ArchiveDatasetId dataset, long blockInclusive) {
        requireOpen();
        if (dataset != ArchiveDatasetId.ADDRESS_TRANSACTION) return;
        transaction(connection -> execute(connection,
                        "DELETE FROM resolver_outputs WHERE (tx_hash,output_index) IN ("
                                + "SELECT referenced_tx_hash,referenced_output_index "
                                + "FROM resolver_spends WHERE spending_block_number<=?)",
                        blockInclusive),
                "SQLite finalized resolver cleanup failed");
    }

    @Override public synchronized Optional<HotBlockCheckpoint> checkpoint(ArchiveDatasetId dataset,
                                                                           ArchiveTrack track, long block) {
        requireOpen();
        try { return checkpoint(writer, dataset, track, block); }
        catch (SQLException e) { throw failure("checkpoint read failed", e); }
    }

    private Optional<HotBlockCheckpoint> checkpoint(Connection connection, ArchiveDatasetId dataset,
                                                      ArchiveTrack track, long block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT slot,block_hash,parent_hash "
                + "FROM block_checkpoints WHERE dataset=? AND track=? AND block_number=?")) {
            statement.setString(1, dataset.name()); statement.setString(2, track.name()); statement.setLong(3, block);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new HotBlockCheckpoint(block, result.getLong(1),
                        result.getBytes(2), result.getBytes(3))) : Optional.empty();
            }
        }
    }

    @Override public synchronized Optional<ArchiveRecord> findFact(ArchiveDatasetId dataset, byte[] logicalKey) {
        requireOpen();
        try {
            for (ArchiveTableSchema table : ArchiveSchemas.schema(dataset).tables()) {
                var row = readFactByKey(writer, table, logicalKey);
                if (row.isPresent()) return row;
            }
            return Optional.empty();
        } catch (SQLException e) { throw failure("hot fact read failed", e); }
    }

    @Override public synchronized void deleteFacts(ArchiveDatasetId dataset, Collection<byte[]> logicalKeys) {
        requireOpen();
        transaction(connection -> {
            Map<ArchiveTableSchema, List<byte[]>> grouped = new LinkedHashMap<>();
            for (byte[] key : logicalKeys) grouped.computeIfAbsent(tableForKey(dataset, key), ignored -> new ArrayList<>())
                    .add(key);
            for (var group : grouped.entrySet()) {
                ArchiveTableSchema table = group.getKey();
                String sql = "DELETE FROM " + hotTable(table.physicalName()) + " WHERE hot_key=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (byte[] key : group.getValue()) { statement.setBytes(1, key); statement.addBatch(); }
                    statement.executeBatch();
                }
            }
        }, "SQLite hot promotion cleanup failed");
    }

    @Override public synchronized void clearTrack(ArchiveDatasetId dataset, ArchiveTrack track) {
        requireOpen();
        transaction(connection -> {
            if (track == ArchiveTrack.LIVE) for (ArchiveTableSchema table : ArchiveSchemas.schema(dataset).tables()) {
                execute(connection, "DELETE FROM " + hotTable(table.physicalName()));
            }
            // Resolver state has one lifecycle and is reset only through
            // resetResolver(). Cursor cleanup must preserve the handoff state.
            execute(connection, "DELETE FROM block_checkpoints WHERE dataset=? AND track=?", dataset.name(), track.name());
            execute(connection, "DELETE FROM projection_progress WHERE dataset=? AND track=?", dataset.name(), track.name());
        }, "SQLite hot track clear failed");
    }

    @Override public HotHistorySnapshot snapshot() {
        requireOpen();
        try {
            Connection read = openConnection();
            read.setAutoCommit(false);
            // Establish the WAL snapshot now. Merely disabling autocommit is
            // deferred by SQLite until the first read and would allow a
            // promotion cleanup to slip between request acquisition and use.
            try (Statement pin = read.createStatement(); ResultSet ignored = pin.executeQuery(
                    "SELECT schema_version FROM hot_schema WHERE singleton=1")) { ignored.next(); }
            return new Snapshot(read);
        } catch (SQLException e) { throw failure("cannot open SQLite hot snapshot", e); }
    }

    @Override public synchronized ArchiveSourceLease acquireBlockBodyLease(long startBlock, long endBlock,
                                                                            Instant expiresAt) {
        requireOpen();
        if (startBlock < 0 || endBlock < startBlock || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("invalid block-body lease");
        }
        UUID id = UUID.randomUUID();
        transaction(connection -> execute(connection, "INSERT INTO block_body_leases VALUES(?,?,?,?)",
                id.toString(), startBlock, endBlock, expiresAt.toEpochMilli()), "lease write failed");
        return new Lease(id, startBlock, endBlock, expiresAt);
    }

    @Override public synchronized void requireBlockBodiesFrom(ArchiveDatasetId dataset, long blockNumber) {
        requireOpen();
        if (dataset.sourceKind() != SourceKind.BLOCK || blockNumber < 0) throw new IllegalArgumentException("invalid body requirement");
        transaction(connection -> putRequirement(connection, dataset, blockNumber), "body requirement write failed");
    }

    @Override public synchronized void releaseBlockBodyRequirement(ArchiveDatasetId dataset) {
        requireOpen();
        transaction(connection -> execute(connection, "DELETE FROM block_body_requirements WHERE dataset=?",
                dataset.name()), "body requirement delete failed");
    }

    @Override public synchronized OptionalLong oldestRequiredBlockNumber() {
        requireOpen();
        transaction(connection -> execute(connection, "DELETE FROM block_body_leases WHERE expires_at<=?",
                Instant.now().toEpochMilli()), "expired lease cleanup failed");
        try (Statement statement = writer.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT MIN(value) FROM (SELECT block_number value FROM block_body_requirements UNION ALL "
                        + "SELECT start_block FROM block_body_leases)")) {
            if (!result.next()) return OptionalLong.empty();
            long value = result.getLong(1);
            return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (SQLException e) { throw failure("retention boundary read failed", e); }
    }

    @Override public synchronized Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track) {
        requireOpen();
        try { return load(writer, dataset, track); }
        catch (SQLException e) { throw failure("progress read failed", e); }
    }

    private Optional<ArchiveProgress> load(Connection connection, ArchiveDatasetId dataset,
                                           ArchiveTrack track) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT coordinate,slot,block_hash,backend_generation "
                + "FROM projection_progress WHERE dataset=? AND track=?")) {
            statement.setString(1, dataset.name()); statement.setString(2, track.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ArchiveProgress(dataset, track, result.getLong(1),
                        result.getLong(2), result.getBytes(3), result.getLong(4))) : Optional.empty();
            }
        }
    }

    @Override public synchronized void save(ArchiveProgress progress, ArchiveReceipt receipt) {
        requireOpen();
        transaction(connection -> { putProgress(connection, progress); advanceRequirement(connection, progress);
            putReceipt(connection, receipt); }, "progress/receipt write failed");
    }

    @Override public synchronized void saveCoveredProgress(ArchiveProgress progress) {
        requireOpen();
        transaction(connection -> { putProgress(connection, progress); advanceRequirement(connection, progress); },
                "covered progress write failed");
    }

    private void putCheckpoint(Connection connection, ArchiveDatasetId dataset, ArchiveTrack track,
                               HotBlockCheckpoint block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO block_checkpoints VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT(dataset,track,block_number) DO UPDATE SET slot=excluded.slot,block_hash=excluded.block_hash,"
                + "parent_hash=excluded.parent_hash")) {
            statement.setString(1, dataset.name()); statement.setString(2, track.name());
            statement.setLong(3, block.blockNumber()); statement.setLong(4, block.slot());
            statement.setBytes(5, block.blockHash()); statement.setBytes(6, block.parentHash()); statement.executeUpdate();
        }
    }

    private void putProgress(Connection connection, ArchiveProgress progress) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO projection_progress VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT(dataset,track) DO UPDATE SET coordinate=excluded.coordinate,slot=excluded.slot,"
                + "block_hash=excluded.block_hash,backend_generation=excluded.backend_generation")) {
            statement.setString(1, progress.dataset().name()); statement.setString(2, progress.track().name());
            statement.setLong(3, progress.coordinate()); statement.setLong(4, progress.slot());
            statement.setBytes(5, progress.blockHash()); statement.setLong(6, progress.backendGeneration());
            statement.executeUpdate();
        }
    }

    private void putReceipt(Connection connection, ArchiveReceipt receipt) throws SQLException {
        if (receipt == null) return;
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO archive_receipts VALUES(?,?,?,?) "
                + "ON CONFLICT(job_id) DO UPDATE SET ordered_digest=excluded.ordered_digest")) {
            statement.setString(1, receipt.jobId().toString()); statement.setString(2, receipt.dataset().name());
            statement.setLong(3, receipt.backendGeneration()); statement.setString(4, receipt.orderedDigest());
            statement.executeUpdate();
        }
    }

    private void advanceRequirement(Connection connection, ArchiveProgress progress) throws SQLException {
        if (progress.dataset().sourceKind() == SourceKind.BLOCK) {
            putRequirement(connection, progress.dataset(), Math.addExact(progress.coordinate(), 1));
        }
    }

    private void putRequirement(Connection connection, ArchiveDatasetId dataset, long block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO block_body_requirements VALUES(?,?) "
                + "ON CONFLICT(dataset) DO UPDATE SET block_number=excluded.block_number")) {
            statement.setString(1, dataset.name()); statement.setLong(2, block); statement.executeUpdate();
        }
    }

    private Optional<ArchiveRecord> readFactByKey(Connection connection, ArchiveTableSchema table,
                                                   byte[] key) throws SQLException {
        String columns = table.columns().stream().map(c -> identifier(c.name()))
                .collect(java.util.stream.Collectors.joining(","));
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + columns + " FROM "
                + hotTable(table.physicalName()) + " WHERE hot_key=?")) {
            statement.setBytes(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRecord(result, table)) : Optional.empty();
            }
        }
    }

    private ArchiveRecord readRecord(ResultSet result, ArchiveTableSchema table) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < table.columns().size(); i++) {
            ArchiveColumn column = table.columns().get(i);
            Object value = switch (column.type()) {
                case BINARY -> result.getBytes(i + 1);
                case TEXT -> result.getString(i + 1);
                case BOOLEAN -> { int n = result.getInt(i + 1); yield result.wasNull() ? null : n != 0; }
                case INT32 -> { int n = result.getInt(i + 1); yield result.wasNull() ? null : n; }
                case INT64 -> { long n = result.getLong(i + 1); yield result.wasNull() ? null : n; }
                case DECIMAL_38 -> { String n = result.getString(i + 1); yield n == null ? null : new BigDecimal(n); }
                case UUID -> { String n = result.getString(i + 1); yield n == null ? null : java.util.UUID.fromString(n); }
            };
            values.put(column.name(), value);
        }
        return new ArchiveRecord(table.physicalName(), values);
    }

    private void bind(PreparedStatement statement, int index, ArchiveColumn column, Object value) throws SQLException {
        if (value == null) { statement.setNull(index, Types.NULL); return; }
        switch (column.type()) {
            case BINARY -> statement.setBytes(index, (byte[]) value);
            case TEXT -> statement.setString(index, value.toString());
            case BOOLEAN -> statement.setInt(index, (Boolean) value ? 1 : 0);
            case INT32 -> statement.setInt(index, ((Number) value).intValue());
            case INT64 -> statement.setLong(index, ((Number) value).longValue());
            case DECIMAL_38 -> statement.setString(index, value.toString());
            case UUID -> statement.setString(index, value.toString());
        }
    }

    private ArchiveTableSchema table(ArchiveDatasetId dataset, String name) {
        return ArchiveSchemas.schema(dataset).tables().stream().filter(value -> value.physicalName().equals(name))
                .findFirst().orElseThrow(() -> new ArchiveStoreException("table does not belong to dataset: " + name));
    }
    private ArchiveTableSchema tableForKey(ArchiveDatasetId dataset, byte[] key) {
        for (ArchiveTableSchema table : ArchiveSchemas.schema(dataset).tables()) {
            byte[] prefix = ("archive-row/" + table.physicalName() + "/").getBytes(StandardCharsets.UTF_8);
            if (key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length)) {
                return table;
            }
        }
        throw new IllegalArgumentException("fact key does not belong to dataset " + dataset.logicalName());
    }

    private String hotTable(String table) { return identifier("hot_" + table); }
    private String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("unsafe SQL identifier");
        return '"' + value + '"';
    }
    private String sqlType(ArchiveValueType type) {
        return switch (type) { case BINARY -> "BLOB"; case TEXT, DECIMAL_38, UUID -> "TEXT";
            case BOOLEAN, INT32, INT64 -> "INTEGER"; };
    }
    private boolean hasColumn(ArchiveTableSchema table, String name) {
        return table.columns().stream().anyMatch(column -> column.name().equals(name));
    }
    private boolean same(Object a, Object b) { return a instanceof byte[] x && b instanceof byte[] y
            ? Arrays.equals(x, y) : Objects.equals(a, b); }
    private boolean sameOutput(ResolvedOutput a, ResolvedOutput b) {
        return same(a.addressKey(), b.addressKey()) && Objects.equals(a.address(), b.address())
                && same(a.paymentCredential(), b.paymentCredential())
                && Objects.equals(a.stakeCredentialType(), b.stakeCredentialType())
                && same(a.stakeCredential(), b.stakeCredential());
    }
    private void nullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof String text) statement.setString(i + 1, text);
                else if (value instanceof Integer n) statement.setInt(i + 1, n);
                else if (value instanceof Long n) statement.setLong(i + 1, n);
                else if (value instanceof byte[] bytes) statement.setBytes(i + 1, bytes);
                else throw new IllegalArgumentException("unsupported SQL argument");
            }
            statement.executeUpdate();
        }
    }

    private void transaction(SqlWork work, String message) {
        try {
            writer.setAutoCommit(false);
            try { work.run(writer); writer.commit(); }
            catch (Exception e) { writer.rollback(); throw e; }
            finally { writer.setAutoCommit(true); }
        } catch (Exception e) { if (e instanceof ArchiveStoreException archive) throw archive;
            throw failure(message, e); }
    }

    private ArchiveStoreException failure(String message, Exception e) { return new ArchiveStoreException(message, e); }
    private void requireOpen() { if (closed.get()) throw new IllegalStateException("SQLite hot-history store is closed"); }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { writer.close(); } catch (SQLException e) { throw failure("SQLite hot-history close failed", e); }
    }

    @FunctionalInterface private interface SqlWork { void run(Connection connection) throws Exception; }

    private final class Snapshot implements HotHistorySnapshot {
        private final Connection connection;
        private final AtomicBoolean done = new AtomicBoolean();
        private Snapshot(Connection connection) { this.connection = connection; }
        @Override public List<Entry> queryTable(ArchiveDatasetId dataset, String name,
                                                Map<String, Object> filters,
                                                Long blockFromInclusive, Long blockToInclusive) {
            if (done.get()) throw new IllegalStateException("SQLite hot snapshot is closed");
            ArchiveTableSchema table = table(dataset, name);
            String columns = table.columns().stream().map(c -> identifier(c.name()))
                    .collect(java.util.stream.Collectors.joining(","));
            StringBuilder sql = new StringBuilder("SELECT hot_key,").append(columns).append(" FROM ")
                    .append(hotTable(name)).append(" WHERE 1=1");
            List<Map.Entry<ArchiveColumn, Object>> selectedFilters = new ArrayList<>();
            for (var filter : filters.entrySet()) {
                ArchiveColumn column = table.columns().stream()
                        .filter(candidate -> candidate.name().equals(filter.getKey())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown hot fact filter " + filter.getKey()));
                sql.append(" AND ").append(identifier(column.name())).append("=?");
                selectedFilters.add(new AbstractMap.SimpleImmutableEntry<>(column, filter.getValue()));
            }
            ArchiveColumn coordinate = table.columns().stream().filter(column -> column.name().equals("block_number"))
                    .findFirst().orElse(null);
            if (blockFromInclusive != null) {
                if (blockToInclusive == null || coordinate == null) throw new IllegalArgumentException("invalid hot fact range");
                sql.append(" AND block_number BETWEEN ? AND ?");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                for (var filter : selectedFilters) bind(statement, parameter++, filter.getKey(), filter.getValue());
                if (blockFromInclusive != null) {
                    statement.setLong(parameter++, blockFromInclusive);
                    statement.setLong(parameter, blockToInclusive);
                }
                try (ResultSet rows = statement.executeQuery()) {
                List<Entry> result = new ArrayList<>();
                while (rows.next()) {
                    byte[] key = rows.getBytes(1);
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < table.columns().size(); i++) {
                        ArchiveColumn column = table.columns().get(i);
                        Object value = switch (column.type()) {
                            case BINARY -> rows.getBytes(i + 2);
                            case TEXT -> rows.getString(i + 2);
                            case BOOLEAN -> { int n = rows.getInt(i + 2); yield rows.wasNull() ? null : n != 0; }
                            case INT32 -> { int n = rows.getInt(i + 2); yield rows.wasNull() ? null : n; }
                            case INT64 -> { long n = rows.getLong(i + 2); yield rows.wasNull() ? null : n; }
                            case DECIMAL_38 -> { String n = rows.getString(i + 2); yield n == null ? null : new BigDecimal(n); }
                            case UUID -> { String n = rows.getString(i + 2); yield n == null ? null : UUID.fromString(n); }
                        };
                        values.put(column.name(), value);
                    }
                    result.add(new Entry(key, new ArchiveRecord(name, values)));
                }
                return List.copyOf(result);
                }
            } catch (SQLException e) { throw failure("SQLite hot snapshot query failed", e); }
        }
        @Override public void close() {
            if (!done.compareAndSet(false, true)) return;
            try { connection.rollback(); connection.close(); }
            catch (SQLException e) { throw failure("SQLite hot snapshot close failed", e); }
        }
    }

    private final class Lease implements ArchiveSourceLease {
        private final UUID id; private final long start; private final long end;
        private Instant expiry; private boolean released;
        private Lease(UUID id, long start, long end, Instant expiry) {
            this.id = id; this.start = start; this.end = end; this.expiry = expiry;
        }
        @Override public UUID leaseId() { return id; }
        @Override public Instant expiresAt() { return expiry; }
        @Override public synchronized ArchiveSourceLease renew(Instant value) {
            if (released || !value.isAfter(Instant.now())) throw new IllegalStateException("invalid lease renewal");
            SqliteHotHistoryStore.this.transaction(connection -> execute(connection,
                    "UPDATE block_body_leases SET expires_at=? WHERE lease_id=?", value.toEpochMilli(), id.toString()),
                    "lease renewal failed");
            expiry = value; return this;
        }
        @Override public synchronized void close() {
            if (released) return; released = true;
            SqliteHotHistoryStore.this.transaction(connection -> execute(connection,
                    "DELETE FROM block_body_leases WHERE lease_id=?", id.toString()), "lease release failed");
        }
    }
}
