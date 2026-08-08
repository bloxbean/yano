package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexReader;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLineage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCheckpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexPage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexedAccount;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Bounded JDBC reader. No query materializes the complete index in heap. */
final class SqliteEutxoIndexReader implements EutxoIndexReader {
    private static final String LATEST_WITHDRAWALS = """
            WITH latest AS (
              SELECT event_sequence, claim_id, canonical_payload,
                     ROW_NUMBER() OVER (
                       PARTITION BY event_sequence
                       ORDER BY app_height DESC, event_ordinal DESC
                     ) AS version_rank
              FROM indexed_withdrawal_version
            )
            """;

    private final Object lock;
    private final Connection connection;
    private final Supplier<IndexCheckpoint> checkpoint;

    SqliteEutxoIndexReader(
            Object lock,
            Connection connection,
            Supplier<IndexCheckpoint> checkpoint
    ) {
        this.lock = lock;
        this.connection = connection;
        this.checkpoint = checkpoint;
    }

    @Override
    public IndexCheckpoint checkpoint() {
        synchronized (lock) {
            return checkpoint.get();
        }
    }

    @Override
    public Optional<EutxoTransactionSummary> transaction(
            String transactionId
    ) {
        // transaction_id is NOT unique: an undecodable transaction indexes
        // with an empty id, and a replayed one is committed again as
        // DUPLICATE_TRANSACTION. Blank matches nothing, and among real
        // duplicates the ACCEPTED row is the meaningful answer.
        if (transactionId == null || transactionId.isBlank()) {
            return Optional.empty();
        }
        return one(
                "SELECT canonical_payload FROM indexed_transaction"
                        + " WHERE transaction_id = ?"
                        + " ORDER BY (status = 'ACCEPTED') DESC,"
                        + " event_sequence ASC LIMIT 1",
                transactionId,
                EutxoTransactionSummary::decode);
    }

    @Override
    public Optional<EutxoTransactionSummary> message(String messageId) {
        return one(
                "SELECT canonical_payload FROM indexed_transaction"
                        + " WHERE message_id = ?",
                messageId,
                EutxoTransactionSummary::decode);
    }

    @Override
    public IndexPage<EutxoTransactionSummary> transactions(
            long before,
            int limit
    ) {
        return page(
                "SELECT event_sequence, canonical_payload"
                        + " FROM indexed_transaction"
                        + " WHERE (? = 0 OR event_sequence < ?)"
                        + " ORDER BY event_sequence DESC LIMIT ?",
                before, limit, EutxoTransactionSummary::decode);
    }

    @Override
    public Optional<EutxoDepositRecord> deposit(String acceptedOutpoint) {
        return one(
                "SELECT canonical_payload FROM indexed_deposit"
                        + " WHERE accepted_outpoint = ?",
                acceptedOutpoint,
                EutxoDepositRecord::decode);
    }

    @Override
    public IndexPage<EutxoDepositRecord> deposits(long before, int limit) {
        return page(
                "SELECT event_sequence, canonical_payload"
                        + " FROM indexed_deposit"
                        + " WHERE (? = 0 OR event_sequence < ?)"
                        + " ORDER BY event_sequence DESC LIMIT ?",
                before, limit, EutxoDepositRecord::decode);
    }

    @Override
    public Optional<EutxoWithdrawalRecord> withdrawal(String claimId) {
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT canonical_payload"
                            + " FROM indexed_withdrawal_version"
                            + " WHERE claim_id = ?"
                            + " ORDER BY app_height DESC,"
                            + " event_ordinal DESC LIMIT 1")) {
                statement.setString(1, claimId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next()
                            ? Optional.of(EutxoWithdrawalRecord.decode(
                            rows.getBytes(1)))
                            : Optional.empty();
                }
            } catch (SQLException failure) {
                throw sql("cannot query indexed withdrawal", failure);
            }
        }
    }

    @Override
    public IndexPage<EutxoWithdrawalRecord> withdrawals(
            long before,
            int limit
    ) {
        return page(
                LATEST_WITHDRAWALS
                        + "SELECT event_sequence, canonical_payload"
                        + " FROM latest WHERE version_rank = 1"
                        + " AND (? = 0 OR event_sequence < ?)"
                        + " ORDER BY event_sequence DESC LIMIT ?",
                before, limit, EutxoWithdrawalRecord::decode);
    }

    @Override
    public IndexedAccount account(String address, int activityLimit) {
        if (activityLimit < 1 || activityLimit > 100) {
            throw new IllegalArgumentException(
                    "activityLimit must be between 1 and 100");
        }
        synchronized (lock) {
            try {
                List<EutxoTransactionSummary.Entry> utxos =
                        accountUtxos(address);
                BigInteger balance = utxos.stream()
                        .map(EutxoTransactionSummary.Entry::lovelace)
                        .reduce(BigInteger.ZERO, BigInteger::add);
                List<String> activity = new ArrayList<>();
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "SELECT transaction_id"
                                             + " FROM indexed_address_activity"
                                             + " WHERE address = ?"
                                             + " ORDER BY event_sequence DESC"
                                             + " LIMIT ?")) {
                    statement.setString(1, address);
                    statement.setInt(2, activityLimit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            activity.add(rows.getString(1));
                        }
                    }
                }
                return new IndexedAccount(
                        address, balance, utxos, activity);
            } catch (SQLException failure) {
                throw sql("cannot query indexed account", failure);
            }
        }
    }

    @Override
    public EutxoLineage lineage(
            String transactionId,
            int maximumDepth,
            int maximumNodes
    ) {
        if (maximumDepth < 0 || maximumDepth > 20
                || maximumNodes < 1 || maximumNodes > 500) {
            throw new IllegalArgumentException("invalid lineage bounds");
        }
        synchronized (lock) {
            try {
                if (!transactionExists(transactionId)) {
                    return new EutxoLineage(
                            List.of(), List.of(), false);
                }
                record Visit(String id, int depth) {
                }
                ArrayDeque<Visit> queue = new ArrayDeque<>();
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                LinkedHashSet<EutxoLineage.Edge> edges =
                        new LinkedHashSet<>();
                queue.add(new Visit(transactionId, 0));
                boolean truncated = false;
                while (!queue.isEmpty()) {
                    Visit visit = queue.removeFirst();
                    if (seen.size() >= maximumNodes
                            && !seen.contains(visit.id())) {
                        truncated = true;
                        break;
                    }
                    if (!seen.add(visit.id())) {
                        continue;
                    }
                    Set<String> parents = relations(
                            "SELECT parent_transaction_id"
                                    + " FROM indexed_transaction_input"
                                    + " WHERE transaction_id = ?"
                                    + " ORDER BY parent_transaction_id",
                            visit.id());
                    Set<String> children = relations(
                            "SELECT transaction_id"
                                    + " FROM indexed_transaction_input"
                                    + " WHERE parent_transaction_id = ?"
                                    + " ORDER BY transaction_id",
                            visit.id());
                    if (visit.depth() >= maximumDepth) {
                        truncated |= !parents.isEmpty()
                                || !children.isEmpty();
                        continue;
                    }
                    for (String parent : parents) {
                        edges.add(new EutxoLineage.Edge(
                                nodeId(parent), "tx:" + visit.id(),
                                "SPENT_BY"));
                        queue.addLast(new Visit(
                                parent, visit.depth() + 1));
                    }
                    for (String child : children) {
                        edges.add(new EutxoLineage.Edge(
                                "tx:" + visit.id(), "tx:" + child,
                                "SPENT_BY"));
                        queue.addLast(new Visit(
                                child, visit.depth() + 1));
                    }
                }
                List<EutxoLineage.Node> nodes = seen.stream()
                        .map(this::node)
                        .toList();
                return new EutxoLineage(
                        nodes, List.copyOf(edges), truncated);
            } catch (SQLException failure) {
                throw sql("cannot query indexed lineage", failure);
            }
        }
    }

    @Override
    public String normalizedDigest() {
        synchronized (lock) {
            try {
                MessageDigest digest =
                        MessageDigest.getInstance("SHA-256");
                update(digest, checkpoint.get().toString());
                digestRows(
                        digest,
                        "SELECT event_sequence, canonical_payload"
                                + " FROM indexed_transaction"
                                + " ORDER BY event_sequence");
                digestRows(
                        digest,
                        "SELECT event_sequence, canonical_payload"
                                + " FROM indexed_deposit"
                                + " ORDER BY event_sequence");
                digestRows(
                        digest,
                        LATEST_WITHDRAWALS
                                + "SELECT event_sequence,"
                                + " canonical_payload FROM latest"
                                + " WHERE version_rank = 1"
                                + " ORDER BY event_sequence");
                return HexFormat.of().formatHex(digest.digest());
            } catch (SQLException failure) {
                throw sql("cannot digest indexed projection", failure);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private List<EutxoTransactionSummary.Entry> accountUtxos(
            String address
    ) throws SQLException {
        String query = """
                WITH candidates(outpoint, address, lovelace) AS (
                  SELECT outpoint, address, lovelace
                  FROM indexed_transaction_output
                  UNION ALL
                  SELECT mirrored_outpoint, address, lovelace
                  FROM indexed_deposit
                ),
                spent(outpoint) AS (
                  SELECT input_outpoint FROM indexed_transaction_input
                  UNION
                  SELECT withdrawal_outpoint
                  FROM indexed_withdrawal_version
                )
                SELECT outpoint, address, lovelace
                FROM candidates
                WHERE address = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM spent
                    WHERE spent.outpoint = candidates.outpoint
                  )
                ORDER BY outpoint
                """;
        List<EutxoTransactionSummary.Entry> result =
                new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(query)) {
            statement.setString(1, address);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new EutxoTransactionSummary.Entry(
                            EutxoOutpoint.parse(rows.getString(1)),
                            rows.getString(2),
                            new BigInteger(rows.getString(3))));
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean transactionExists(String transactionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM indexed_transaction"
                        + " WHERE transaction_id = ?")) {
            statement.setString(1, transactionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Set<String> relations(String query, String transactionId)
            throws SQLException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(query)) {
            statement.setString(1, transactionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return result;
    }

    private String nodeId(String transactionId) {
        String deposit = depositOutpoint(transactionId);
        return deposit == null
                ? "tx:" + transactionId
                : "deposit:" + deposit;
    }

    private EutxoLineage.Node node(String transactionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM indexed_transaction"
                        + " WHERE transaction_id = ?")) {
            statement.setString(1, transactionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return new EutxoLineage.Node(
                            "TRANSACTION", "tx:" + transactionId,
                            rows.getString(1));
                }
            }
            String deposit = depositOutpoint(transactionId);
            return new EutxoLineage.Node(
                    deposit == null ? "UNKNOWN" : "DEPOSIT",
                    deposit == null
                            ? "tx:" + transactionId
                            : "deposit:" + deposit,
                    deposit == null ? "UNAVAILABLE" : "ACCEPTED");
        } catch (SQLException failure) {
            throw sql("cannot query indexed lineage node", failure);
        }
    }

    private String depositOutpoint(String mirroredTransactionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT accepted_outpoint FROM indexed_deposit"
                        + " WHERE mirrored_transaction_id = ?")) {
            statement.setString(1, mirroredTransactionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw sql("cannot query indexed deposit node", failure);
        }
    }

    private <T> Optional<T> one(
            String query,
            String identity,
            Decoder<T> decoder
    ) {
        synchronized (lock) {
            try (PreparedStatement statement =
                         connection.prepareStatement(query)) {
                statement.setString(1, identity);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next()
                            ? Optional.of(decoder.decode(
                            rows.getBytes(1)))
                            : Optional.empty();
                }
            } catch (SQLException failure) {
                throw sql("cannot query indexed record", failure);
            }
        }
    }

    private <T> IndexPage<T> page(
            String query,
            long before,
            int limit,
            Decoder<T> decoder
    ) {
        if (before < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("invalid page bounds");
        }
        synchronized (lock) {
            try (PreparedStatement statement =
                         connection.prepareStatement(query)) {
                statement.setLong(1, before);
                statement.setLong(2, before);
                statement.setInt(3, limit + 1);
                List<Sequenced<T>> values =
                        new ArrayList<>(limit + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        values.add(new Sequenced<>(
                                rows.getLong(1),
                                decoder.decode(rows.getBytes(2))));
                    }
                }
                boolean hasMore = values.size() > limit;
                if (hasMore) {
                    values.removeLast();
                }
                long nextBefore = hasMore && !values.isEmpty()
                        ? values.getLast().sequence() : 0;
                return new IndexPage<>(
                        values.stream().map(Sequenced::value).toList(),
                        nextBefore, hasMore);
            } catch (SQLException failure) {
                throw sql("cannot query indexed page", failure);
            }
        }
    }

    private void digestRows(
            MessageDigest digest,
            String query
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(query);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                update(digest, Long.toString(rows.getLong(1)));
                digest.update(rows.getBytes(2));
            }
        }
    }

    private static void update(
            MessageDigest digest,
            String value
    ) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static IllegalStateException sql(
            String message,
            SQLException failure
    ) {
        return new IllegalStateException(message, failure);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(byte[] bytes);
    }

    private record Sequenced<T>(long sequence, T value) {
    }
}
