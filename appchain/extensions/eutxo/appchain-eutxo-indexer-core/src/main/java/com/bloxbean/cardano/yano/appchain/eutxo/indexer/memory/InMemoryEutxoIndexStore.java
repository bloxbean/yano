package com.bloxbean.cardano.yano.appchain.eutxo.indexer.memory;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexReader;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexWrite;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLineage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCheckpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexPage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexedAccount;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic reference store used by the reusable store conformance suite. */
public final class InMemoryEutxoIndexStore implements EutxoIndexStore {
    private final IndexIdentity identity;
    private final NavigableMap<Long, SourcePoint> sources = new TreeMap<>();
    private final NavigableMap<Long, State> snapshots = new TreeMap<>();
    private State state;
    private boolean closed;

    public InMemoryEutxoIndexStore(IndexIdentity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
        state = State.empty(IndexCheckpoint.origin(identity));
        sources.put(0L, SourcePoint.ORIGIN);
        snapshots.put(0L, state.copy());
    }

    @Override
    public synchronized IndexIdentity identity() {
        return identity;
    }

    @Override
    public synchronized EutxoIndexWrite begin(SourcePoint source) {
        requireOpen();
        Objects.requireNonNull(source, "source");
        SourcePoint existing = sources.get(source.appHeight());
        if (existing != null) {
            if (!existing.equals(source)) {
                throw new IllegalStateException(
                        "source identity differs at app height " + source.appHeight());
            }
            return new Write(source, state.copy(), true);
        }
        long expected = Math.addExact(state.checkpoint.source().appHeight(), 1);
        if (source.appHeight() != expected) {
            throw new IllegalStateException(
                    "source block gap: expected " + expected
                            + " but received " + source.appHeight());
        }
        return new Write(source, state.copy(), false);
    }

    @Override
    public synchronized IndexCheckpoint checkpoint() {
        requireOpen();
        return state.checkpoint;
    }

    @Override
    public synchronized void rollbackTo(SourcePoint source) {
        requireOpen();
        SourcePoint retained = sources.get(source.appHeight());
        if (!source.equals(retained)) {
            throw new IllegalStateException("rollback source is not retained exactly");
        }
        state = snapshots.get(source.appHeight()).copy();
        sources.tailMap(source.appHeight(), false).clear();
        snapshots.tailMap(source.appHeight(), false).clear();
    }

    @Override
    public synchronized EutxoIndexReader reader() {
        requireOpen();
        return new Reader(state.copy());
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("index store is closed");
        }
    }

    private final class Write implements EutxoIndexWrite {
        private final SourcePoint source;
        private final State working;
        private final boolean duplicate;
        private boolean finished;

        private Write(SourcePoint source, State working, boolean duplicate) {
            this.source = source;
            this.working = working;
            this.duplicate = duplicate;
        }

        @Override
        public void apply(EutxoIndexEvent event) {
            requireActive();
            if (!duplicate) {
                working.apply(Objects.requireNonNull(event, "event"));
            }
        }

        @Override
        public void commit(IndexCheckpoint checkpoint) {
            requireActive();
            Objects.requireNonNull(checkpoint, "checkpoint");
            if (!identity.digest().equals(checkpoint.identityDigest())
                    || !source.equals(checkpoint.source())) {
                throw new IllegalArgumentException(
                        "checkpoint identity or source differs from write");
            }
            synchronized (InMemoryEutxoIndexStore.this) {
                requireOpen();
                if (duplicate) {
                    if (!checkpoint.equals(state.checkpoint)) {
                        throw new IllegalStateException(
                                "duplicate replay checkpoint differs");
                    }
                } else {
                    working.checkpoint = checkpoint;
                    state = working.copy();
                    sources.put(source.appHeight(), source);
                    snapshots.put(source.appHeight(), state.copy());
                }
            }
            finished = true;
        }

        @Override
        public void abort() {
            finished = true;
        }

        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("index write is already finished");
            }
        }
    }

    private static final class State {
        private IndexCheckpoint checkpoint;
        private final NavigableMap<Long, EutxoTransactionSummary> transactions;
        private final Map<String, EutxoTransactionSummary> transactionsById;
        private final Map<String, EutxoTransactionSummary> messages;
        private final NavigableMap<Long, EutxoDepositRecord> deposits;
        private final Map<String, EutxoDepositRecord> depositsByOutpoint;
        private final NavigableMap<Long, EutxoWithdrawalRecord> withdrawals;
        private final Map<String, EutxoWithdrawalRecord> withdrawalsById;
        private final Map<EutxoOutpoint, EutxoTransactionSummary.Entry> utxos;
        private final Map<String, NavigableMap<Long, String>> addressActivity;
        private final Map<String, Set<String>> parents;
        private final Map<String, Set<String>> children;
        private final Map<String, String> depositByMirroredTransaction;

        private State(
                IndexCheckpoint checkpoint,
                NavigableMap<Long, EutxoTransactionSummary> transactions,
                Map<String, EutxoTransactionSummary> transactionsById,
                Map<String, EutxoTransactionSummary> messages,
                NavigableMap<Long, EutxoDepositRecord> deposits,
                Map<String, EutxoDepositRecord> depositsByOutpoint,
                NavigableMap<Long, EutxoWithdrawalRecord> withdrawals,
                Map<String, EutxoWithdrawalRecord> withdrawalsById,
                Map<EutxoOutpoint, EutxoTransactionSummary.Entry> utxos,
                Map<String, NavigableMap<Long, String>> addressActivity,
                Map<String, Set<String>> parents,
                Map<String, Set<String>> children,
                Map<String, String> depositByMirroredTransaction
        ) {
            this.checkpoint = checkpoint;
            this.transactions = transactions;
            this.transactionsById = transactionsById;
            this.messages = messages;
            this.deposits = deposits;
            this.depositsByOutpoint = depositsByOutpoint;
            this.withdrawals = withdrawals;
            this.withdrawalsById = withdrawalsById;
            this.utxos = utxos;
            this.addressActivity = addressActivity;
            this.parents = parents;
            this.children = children;
            this.depositByMirroredTransaction = depositByMirroredTransaction;
        }

        private static State empty(IndexCheckpoint checkpoint) {
            return new State(
                    checkpoint, new TreeMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new TreeMap<>(), new LinkedHashMap<>(),
                    new TreeMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        private State copy() {
            Map<String, NavigableMap<Long, String>> activityCopy =
                    new LinkedHashMap<>();
            addressActivity.forEach((address, activity) ->
                    activityCopy.put(address, new TreeMap<>(activity)));
            return new State(
                    checkpoint,
                    new TreeMap<>(transactions),
                    new LinkedHashMap<>(transactionsById),
                    new LinkedHashMap<>(messages),
                    new TreeMap<>(deposits),
                    new LinkedHashMap<>(depositsByOutpoint),
                    new TreeMap<>(withdrawals),
                    new LinkedHashMap<>(withdrawalsById),
                    new LinkedHashMap<>(utxos),
                    activityCopy,
                    copySets(parents),
                    copySets(children),
                    new LinkedHashMap<>(depositByMirroredTransaction));
        }

        private void apply(EutxoIndexEvent event) {
            if (event instanceof EutxoIndexEvent.Transaction transaction) {
                applyTransaction(transaction.sequence(), transaction.summary());
            } else if (event instanceof EutxoIndexEvent.Deposit deposit) {
                applyDeposit(deposit.sequence(), deposit.record());
            } else if (event instanceof EutxoIndexEvent.Withdrawal withdrawal) {
                applyWithdrawal(withdrawal.sequence(), withdrawal.record());
            }
        }

        private void applyTransaction(
                long sequence,
                EutxoTransactionSummary summary
        ) {
            requireSameOrAbsent(
                    transactions.get(sequence), summary, "transaction sequence");
            requireSameOrAbsent(
                    transactionsById.get(summary.transactionId()),
                    summary, "transaction id");
            requireSameOrAbsent(
                    messages.get(summary.messageId()), summary, "message id");
            if (transactions.containsKey(sequence)) {
                return;
            }
            transactions.put(sequence, summary);
            transactionsById.put(summary.transactionId(), summary);
            messages.put(summary.messageId(), summary);
            addActivity(summary, sequence);
            if (summary.status() != EutxoTransactionSummary.Status.ACCEPTED) {
                return;
            }
            for (EutxoTransactionSummary.Entry input : summary.inputs()) {
                utxos.remove(input.outpoint());
                String parent = input.outpoint().transactionId();
                parents.computeIfAbsent(summary.transactionId(),
                                ignored -> new LinkedHashSet<>())
                        .add(parent);
                children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>())
                        .add(summary.transactionId());
            }
            summary.outputs().forEach(output -> utxos.put(output.outpoint(), output));
        }

        private void applyDeposit(long sequence, EutxoDepositRecord record) {
            String acceptedOutpoint = record.claim().acceptedOutpoint().toString();
            requireSameOrAbsent(deposits.get(sequence), record, "deposit sequence");
            requireSameOrAbsent(
                    depositsByOutpoint.get(acceptedOutpoint), record,
                    "deposit outpoint");
            if (deposits.containsKey(sequence)) {
                return;
            }
            deposits.put(sequence, record);
            depositsByOutpoint.put(acceptedOutpoint, record);
            EutxoTransactionSummary.Entry entry = new EutxoTransactionSummary.Entry(
                    record.mirroredOutpoint(),
                    record.claim().l2Address(),
                    outputLovelace(record.claim().mirroredOutputCbor()));
            utxos.put(entry.outpoint(), entry);
            depositByMirroredTransaction.put(
                    entry.outpoint().transactionId(), acceptedOutpoint);
        }

        private void applyWithdrawal(
                long sequence,
                EutxoWithdrawalRecord record
        ) {
            String claimId = record.claim().claimId();
            EutxoWithdrawalRecord existing = withdrawals.get(sequence);
            if (existing != null
                    && !existing.claim().equals(record.claim())) {
                throw new IllegalStateException(
                        "withdrawal sequence maps to another claim");
            }
            EutxoWithdrawalRecord byId = withdrawalsById.get(claimId);
            if (byId != null && !byId.claim().equals(record.claim())) {
                throw new IllegalStateException(
                        "withdrawal id maps to another claim");
            }
            withdrawals.put(sequence, record);
            withdrawalsById.put(claimId, record);
            utxos.remove(record.claim().withdrawalOutpoint());
        }

        private void addActivity(EutxoTransactionSummary summary, long sequence) {
            Set<String> addresses = new LinkedHashSet<>();
            summary.inputs().forEach(entry -> addresses.add(entry.address()));
            summary.outputs().forEach(entry -> addresses.add(entry.address()));
            addresses.forEach(address -> addressActivity
                    .computeIfAbsent(address, ignored -> new TreeMap<>())
                    .put(sequence, summary.transactionId()));
        }

        private static BigInteger outputLovelace(byte[] outputCbor) {
            try {
                TransactionOutput output = TransactionOutput.deserialize(
                        CborSerializationUtil.deserialize(outputCbor));
                return output.getValue().getCoin();
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "committed deposit output cannot be decoded", failure);
            }
        }

        private static Map<String, Set<String>> copySets(
                Map<String, Set<String>> source
        ) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            source.forEach((key, values) ->
                    copy.put(key, new LinkedHashSet<>(values)));
            return copy;
        }

        private static void requireSameOrAbsent(
                Object existing,
                Object incoming,
                String identity
        ) {
            if (existing != null && !existing.equals(incoming)) {
                throw new IllegalStateException(identity + " maps to another record");
            }
        }
    }

    private static final class Reader implements EutxoIndexReader {
        private final State state;

        private Reader(State state) {
            this.state = state;
        }

        @Override
        public IndexCheckpoint checkpoint() {
            return state.checkpoint;
        }

        @Override
        public Optional<EutxoTransactionSummary> transaction(String transactionId) {
            return Optional.ofNullable(state.transactionsById.get(transactionId));
        }

        @Override
        public Optional<EutxoTransactionSummary> message(String messageId) {
            return Optional.ofNullable(state.messages.get(messageId));
        }

        @Override
        public IndexPage<EutxoTransactionSummary> transactions(
                long before,
                int limit
        ) {
            return page(state.transactions, before, limit);
        }

        @Override
        public Optional<EutxoDepositRecord> deposit(String acceptedOutpoint) {
            return Optional.ofNullable(state.depositsByOutpoint.get(acceptedOutpoint));
        }

        @Override
        public IndexPage<EutxoDepositRecord> deposits(long before, int limit) {
            return page(state.deposits, before, limit);
        }

        @Override
        public Optional<EutxoWithdrawalRecord> withdrawal(String claimId) {
            return Optional.ofNullable(state.withdrawalsById.get(claimId));
        }

        @Override
        public IndexPage<EutxoWithdrawalRecord> withdrawals(
                long before,
                int limit
        ) {
            return page(state.withdrawals, before, limit);
        }

        @Override
        public IndexedAccount account(String address, int activityLimit) {
            if (activityLimit < 1 || activityLimit > 100) {
                throw new IllegalArgumentException(
                        "activityLimit must be between 1 and 100");
            }
            List<EutxoTransactionSummary.Entry> utxos = state.utxos.values()
                    .stream()
                    .filter(entry -> entry.address().equals(address))
                    .sorted(Comparator.comparing(entry -> entry.outpoint().toString()))
                    .toList();
            BigInteger balance = utxos.stream()
                    .map(EutxoTransactionSummary.Entry::lovelace)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            NavigableMap<Long, String> activity = state.addressActivity.get(address);
            List<String> ids = activity == null ? List.of()
                    : activity.descendingMap().values().stream()
                    .limit(activityLimit).toList();
            return new IndexedAccount(address, balance, utxos, ids);
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
            if (!state.transactionsById.containsKey(transactionId)) {
                return new EutxoLineage(List.of(), List.of(), false);
            }
            record Visit(String id, int depth) {
            }
            ArrayDeque<Visit> queue = new ArrayDeque<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            LinkedHashSet<EutxoLineage.Edge> edges = new LinkedHashSet<>();
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
                if (visit.depth() >= maximumDepth) {
                    truncated |= hasRelations(visit.id());
                    continue;
                }
                for (String parent : state.parents.getOrDefault(
                        visit.id(), Set.of())) {
                    edges.add(new EutxoLineage.Edge(
                            nodeId(parent), "tx:" + visit.id(), "SPENT_BY"));
                    queue.addLast(new Visit(parent, visit.depth() + 1));
                }
                for (String child : state.children.getOrDefault(
                        visit.id(), Set.of())) {
                    edges.add(new EutxoLineage.Edge(
                            "tx:" + visit.id(), "tx:" + child, "SPENT_BY"));
                    queue.addLast(new Visit(child, visit.depth() + 1));
                }
            }
            List<EutxoLineage.Node> nodes = seen.stream()
                    .map(this::node)
                    .toList();
            return new EutxoLineage(nodes, List.copyOf(edges), truncated);
        }

        @Override
        public String normalizedDigest() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, state.checkpoint.toString());
                state.transactions.forEach((sequence, value) -> {
                    update(digest, Long.toString(sequence));
                    digest.update(value.encode());
                });
                state.deposits.forEach((sequence, value) -> {
                    update(digest, Long.toString(sequence));
                    digest.update(value.encode());
                });
                state.withdrawals.forEach((sequence, value) -> {
                    update(digest, Long.toString(sequence));
                    digest.update(value.encode());
                });
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private boolean hasRelations(String transactionId) {
            return !state.parents.getOrDefault(transactionId, Set.of()).isEmpty()
                    || !state.children.getOrDefault(
                            transactionId, Set.of()).isEmpty();
        }

        private String nodeId(String transactionId) {
            String deposit = state.depositByMirroredTransaction.get(transactionId);
            return deposit == null ? "tx:" + transactionId : "deposit:" + deposit;
        }

        private EutxoLineage.Node node(String transactionId) {
            EutxoTransactionSummary summary =
                    state.transactionsById.get(transactionId);
            if (summary != null) {
                return new EutxoLineage.Node(
                        "TRANSACTION", "tx:" + transactionId,
                        summary.status().name());
            }
            String deposit = state.depositByMirroredTransaction.get(transactionId);
            return new EutxoLineage.Node(
                    deposit == null ? "UNKNOWN" : "DEPOSIT",
                    nodeId(transactionId),
                    deposit == null ? "UNAVAILABLE" : "ACCEPTED");
        }

        private static <T> IndexPage<T> page(
                NavigableMap<Long, T> values,
                long before,
                int limit
        ) {
            if (before < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("invalid page bounds");
            }
            NavigableMap<Long, T> eligible = before == 0
                    ? values : values.headMap(before, false);
            List<Map.Entry<Long, T>> entries = eligible.descendingMap()
                    .entrySet().stream().limit(limit).toList();
            if (entries.isEmpty()) {
                return new IndexPage<>(List.of(), 0, false);
            }
            long last = entries.getLast().getKey();
            boolean hasMore = values.lowerKey(last) != null;
            return new IndexPage<>(
                    entries.stream().map(Map.Entry::getValue).toList(),
                    hasMore ? last : 0,
                    hasMore);
        }

        private static void update(MessageDigest digest, String value) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
    }
}
