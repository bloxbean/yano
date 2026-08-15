package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-memory FIFO mempool with one fair mutation lane and a derived, bounded UTXO
 * overlay. Transaction bodies and every index transition share that lane.
 */
public class DefaultMemPool implements MemPool {
    private static final long SLOW_VALIDATION_NANOS = 1_000_000_000L;

    private final ReentrantLock lane = new ReentrantLock(true);
    private final LinkedHashMap<TxId, Entry> transactionsByHash = new LinkedHashMap<>();
    private final Map<IndexedOutpoint, ProducedOutput> producedByOutpoint = new HashMap<>();
    private final Map<IndexedOutpoint, TxId> spentByOutpoint = new HashMap<>();
    private final Map<TxId, Set<TxId>> parentsByTransaction = new HashMap<>();
    private final Map<TxId, Set<TxId>> childrenByTransaction = new HashMap<>();
    private final AtomicLong cursor = new AtomicLong();

    private long byteSize;
    private int indexEntryCount;
    private long duplicateRejections;
    private long conflictRejections;
    private long capacityRejections;
    private long malformedRejections;
    private long ledgerRejections;
    private long cascadedRemovals;
    private long totalAdmissionWaitNanos;
    private long totalAdmissionHoldNanos;
    private long totalValidationNanos;
    private long slowValidations;

    @Override
    public MempoolAdmissionResult tryAdmit(byte[] txBytes,
                                           Function<Outpoint, Utxo> canonicalResolver,
                                           AdmissionValidator validator,
                                           MempoolAdmissionLimits limits,
                                           Consumer<MemPoolTransaction> acceptedListener) {
        Objects.requireNonNull(txBytes, "txBytes");
        if (lane.isHeldByCurrentThread()) {
            return result(MempoolAdmissionResult.Status.REENTRANT_ADMISSION, null, null,
                    "recursive admission from the mempool mutation lane is not allowed");
        }
        MempoolAdmissionLimits effectiveLimits = limits != null
                ? limits : MempoolAdmissionLimits.unbounded();
        if (effectiveLimits.maxBytes() > 0 && txBytes.length > effectiveLimits.maxBytes()) {
            lane.lock();
            try {
                capacityRejections++;
            } finally {
                lane.unlock();
            }
            return result(MempoolAdmissionResult.Status.BYTE_CAPACITY, null, null,
                    "transaction body exceeds the mempool byte limit");
        }

        // The mempool owns one immutable-by-convention body copy. Without this,
        // caller mutation could make the body disagree with its hash and indexes.
        byte[] ownedTxBytes = Arrays.copyOf(txBytes, txBytes.length);

        final Projection projection;
        try {
            projection = project(ownedTxBytes);
        } catch (Exception e) {
            lane.lock();
            try {
                malformedRejections++;
            } finally {
                lane.unlock();
            }
            return result(MempoolAdmissionResult.Status.MALFORMED, safeHash(ownedTxBytes), null,
                    "transaction projection failed: " + stableMessage(e));
        }

        Function<Outpoint, Utxo> canonical = canonicalResolver != null
                ? canonicalResolver : ignored -> null;
        AdmissionValidator effectiveValidator = validator != null
                ? validator : (ignoredBytes, ignoredHash, ignoredResolver) -> List.of();

        long waitStarted = System.nanoTime();
        lane.lock();
        long holdStarted = System.nanoTime();
        totalAdmissionWaitNanos += holdStarted - waitStarted;
        try {
            Entry existing = transactionsByHash.get(projection.txId());
            if (existing != null) {
                duplicateRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.DUPLICATE,
                        projection.txHash(), externalCopy(existing.transaction()), List.of(), "already present");
            }

            for (IndexedOutpoint input : projection.regularInputs()) {
                TxId spender = spentByOutpoint.get(input);
                if (spender != null) {
                    conflictRejections++;
                    return result(MempoolAdmissionResult.Status.CONFLICT, projection.txHash(), null,
                            "regular input is already claimed by " + spender.hex() + ": " + input);
                }
            }

            Set<TxId> parents = discoverParents(projection.allInputs());
            int addedIndexEntries = projection.outputs().size()
                    + projection.regularInputs().size()
                    + (parents.size() * 2);

            if (effectiveLimits.maxTransactions() > 0
                    && transactionsByHash.size() + 1 > effectiveLimits.maxTransactions()) {
                capacityRejections++;
                return result(MempoolAdmissionResult.Status.TRANSACTION_CAPACITY,
                        projection.txHash(), null, "transaction-count limit reached");
            }
            if (effectiveLimits.maxBytes() > 0
                    && byteSize + ownedTxBytes.length > effectiveLimits.maxBytes()) {
                capacityRejections++;
                return result(MempoolAdmissionResult.Status.BYTE_CAPACITY,
                        projection.txHash(), null, "transaction-byte limit reached");
            }
            if (effectiveLimits.maxUtxoIndexEntries() > 0
                    && indexEntryCount + addedIndexEntries > effectiveLimits.maxUtxoIndexEntries()) {
                capacityRejections++;
                return result(MempoolAdmissionResult.Status.INDEX_CAPACITY,
                        projection.txHash(), null, "UTXO-index limit reached");
            }

            ScopedResolver resolver = new ScopedResolver(canonical);
            long validationStarted = System.nanoTime();
            List<com.bloxbean.cardano.yaci.events.api.VetoableEvent.Rejection> rejections;
            try {
                rejections = effectiveValidator.validate(ownedTxBytes, projection.txHash(), resolver);
            } finally {
                resolver.close();
                long duration = System.nanoTime() - validationStarted;
                totalValidationNanos += duration;
                if (duration >= SLOW_VALIDATION_NANOS) slowValidations++;
            }
            if (rejections != null && !rejections.isEmpty()) {
                ledgerRejections++;
                return new MempoolAdmissionResult(MempoolAdmissionResult.Status.LEDGER_REJECTED,
                        projection.txHash(), null, rejections, "ledger validation rejected transaction");
            }

            // Validation callbacks cannot mutate the mempool, but recheck the
            // claims explicitly so this invariant remains local to commit.
            for (IndexedOutpoint input : projection.regularInputs()) {
                if (spentByOutpoint.containsKey(input)) {
                    conflictRejections++;
                    return result(MempoolAdmissionResult.Status.CONFLICT, projection.txHash(), null,
                            "regular input was claimed before commit: " + input);
                }
            }
            if (!allParentsLive(parents)) {
                return result(MempoolAdmissionResult.Status.CONFLICT, projection.txHash(), null,
                        "a mempool parent disappeared before commit");
            }

            MemPoolTransaction transaction = new MemPoolTransaction(
                    cursor.incrementAndGet(), projection.txId().bytes(), ownedTxBytes, TxBodyType.CONWAY);
            Entry entry = new Entry(transaction, projection);
            try {
                commit(entry, parents);
            } catch (RuntimeException | Error commitFailure) {
                recoverFailedCommit(projection.txId(), commitFailure);
                throw commitFailure;
            }
            try {
                if (acceptedListener != null) acceptedListener.accept(externalCopy(transaction));
            } catch (RuntimeException | Error e) {
                removeInternal(Set.of(projection.txId()), true, false);
                throw e;
            }
            return new MempoolAdmissionResult(MempoolAdmissionResult.Status.ACCEPTED,
                    projection.txHash(), externalCopy(transaction), List.of(), "accepted");
        } finally {
            totalAdmissionHoldNanos += System.nanoTime() - holdStarted;
            lane.unlock();
        }
    }

    @Override
    public MemPoolTransaction addTransaction(byte[] txBytes) {
        MempoolAdmissionResult admission = tryAdmit(txBytes, ignored -> null,
                (ignoredBytes, ignoredHash, ignoredResolver) -> List.of(),
                MempoolAdmissionLimits.unbounded(), null);
        if (admission.present()) return admission.transaction();
        throw new MempoolAdmissionException(admission);
    }

    @Override
    public MemPoolTransaction getNextTransaction() {
        lane.lock();
        try {
            if (transactionsByHash.isEmpty()) return null;
            TxId oldest = transactionsByHash.keySet().iterator().next();
            MemPoolTransaction transaction = externalCopy(transactionsByHash.get(oldest).transaction());
            removeInternal(Set.of(oldest), true, true);
            return transaction;
        } finally {
            lane.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lane.lock();
        try {
            return transactionsByHash.isEmpty();
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int size() {
        lane.lock();
        try {
            return transactionsByHash.size();
        } finally {
            lane.unlock();
        }
    }

    @Override
    public long byteSize() {
        lane.lock();
        try {
            return byteSize;
        } finally {
            lane.unlock();
        }
    }

    @Override
    public boolean contains(String txHash) {
        TxId id = tryTxId(txHash);
        if (id == null) return false;
        lane.lock();
        try {
            return transactionsByHash.containsKey(id);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public MemPoolTransaction getTransaction(String txHash) {
        TxId id = tryTxId(txHash);
        if (id == null) return null;
        lane.lock();
        try {
            Entry entry = transactionsByHash.get(id);
            return entry != null ? externalCopy(entry.transaction()) : null;
        } finally {
            lane.unlock();
        }
    }

    @Override
    public List<MemPoolTransaction> snapshotTransactions(int maxCount, long maxBytes) {
        if (maxCount <= 0 || maxBytes < 0) return List.of();
        lane.lock();
        try {
            List<MemPoolTransaction> snapshot = new ArrayList<>(
                    Math.min(maxCount, transactionsByHash.size()));
            long selectedBytes = 0;
            for (Entry entry : transactionsByHash.values()) {
                // Snapshot consumers treat transaction records as immutable. The
                // stable admission-owned body is shared here to avoid duplicating
                // up to the full configured mempool byte budget.
                MemPoolTransaction transaction = entry.transaction();
                int size = transaction.size();
                if (selectedBytes + size > maxBytes) break;
                snapshot.add(transaction);
                selectedBytes += size;
                if (snapshot.size() >= maxCount) break;
            }
            return List.copyOf(snapshot);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public void clear() {
        lane.lock();
        try {
            transactionsByHash.clear();
            producedByOutpoint.clear();
            spentByOutpoint.clear();
            parentsByTransaction.clear();
            childrenByTransaction.clear();
            byteSize = 0;
            indexEntryCount = 0;
        } finally {
            lane.unlock();
        }
    }

    /** Confirmation removal deliberately does not cascade to descendants. */
    @Override
    public int removeByTxHashes(Set<String> txHashes) {
        lane.lock();
        try {
            return removeInternal(toIds(txHashes), false, false);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int removeConflictingInputs(Set<Outpoint> consumedOutpoints) {
        if (consumedOutpoints == null || consumedOutpoints.isEmpty()) return 0;
        lane.lock();
        try {
            Set<TxId> roots = new LinkedHashSet<>();
            for (Outpoint outpoint : consumedOutpoints) {
                IndexedOutpoint indexed = tryIndexedOutpoint(outpoint);
                if (indexed == null) continue;
                TxId spender = spentByOutpoint.get(indexed);
                if (spender != null) roots.add(spender);
            }
            return removeInternal(roots, true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int removeInvalidated(Set<String> txHashes) {
        lane.lock();
        try {
            return removeInternal(toIds(txHashes), true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int evictOldest(int count) {
        if (count <= 0) return 0;
        lane.lock();
        try {
            Set<TxId> roots = new LinkedHashSet<>();
            for (TxId id : transactionsByHash.keySet()) {
                roots.add(id);
                if (roots.size() >= count) break;
            }
            return removeInternal(roots, true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int evictOldestUntilBytesAtMost(long maxBytes) {
        long target = Math.max(0, maxBytes);
        lane.lock();
        try {
            Set<TxId> roots = new LinkedHashSet<>();
            Set<TxId> plannedRemovals = new HashSet<>();
            long projectedBytes = byteSize;
            for (Map.Entry<TxId, Entry> entry : transactionsByHash.entrySet()) {
                if (projectedBytes <= target) break;
                if (plannedRemovals.contains(entry.getKey())) continue;
                roots.add(entry.getKey());
                ArrayDeque<TxId> queue = new ArrayDeque<>();
                queue.add(entry.getKey());
                while (!queue.isEmpty()) {
                    TxId removal = queue.removeFirst();
                    if (!plannedRemovals.add(removal)) continue;
                    Entry removedEntry = transactionsByHash.get(removal);
                    if (removedEntry != null) projectedBytes -= removedEntry.transaction().size();
                    childrenByTransaction.getOrDefault(removal, Set.of()).forEach(queue::addLast);
                }
            }
            return removeInternal(roots, true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int removeOlderThan(long beforeEpochMillis) {
        lane.lock();
        try {
            Set<TxId> roots = new LinkedHashSet<>();
            for (Map.Entry<TxId, Entry> entry : transactionsByHash.entrySet()) {
                if (entry.getValue().transaction().insertedAt() >= beforeEpochMillis) break;
                roots.add(entry.getKey());
            }
            return removeInternal(roots, true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public int revalidate(Function<Outpoint, Utxo> canonicalResolver) {
        Function<Outpoint, Utxo> canonical = canonicalResolver != null
                ? canonicalResolver : ignored -> null;
        lane.lock();
        try {
            Map<IndexedOutpoint, ProducedOutput> validProduced = new HashMap<>();
            Set<IndexedOutpoint> validSpent = new HashSet<>();
            Set<TxId> invalidRoots = new LinkedHashSet<>();
            for (Map.Entry<TxId, Entry> mapEntry : transactionsByHash.entrySet()) {
                Entry entry = mapEntry.getValue();
                Projection projection = entry.projection();
                boolean valid = true;
                for (IndexedOutpoint input : projection.allInputs()) {
                    if (validSpent.contains(input)) {
                        valid = false;
                        break;
                    }
                    if (!validProduced.containsKey(input)
                            && safeCanonicalResolve(input, canonical) == null) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    for (IndexedOutpoint input : projection.regularInputs()) {
                        if (!validSpent.add(input)) {
                            valid = false;
                            break;
                        }
                    }
                }
                if (!valid) {
                    invalidRoots.add(mapEntry.getKey());
                    continue;
                }
                projection.outputs().forEach((outpoint, utxo) ->
                        validProduced.put(outpoint, new ProducedOutput(mapEntry.getKey(), utxo)));
            }
            return removeInternal(invalidRoots, true, true);
        } finally {
            lane.unlock();
        }
    }

    @Override
    public MempoolStats stats() {
        lane.lock();
        try {
            int dependencyIndexRecords = indexEntryCount
                    - producedByOutpoint.size() - spentByOutpoint.size();
            int dependencyEdges = dependencyIndexRecords / 2;
            long estimatedIndexBytes = (producedByOutpoint.size() * 256L)
                    + (spentByOutpoint.size() * 96L)
                    + (dependencyEdges * 256L);
            return new MempoolStats(
                    transactionsByHash.size(), byteSize, indexEntryCount,
                    producedByOutpoint.size(), spentByOutpoint.size(), dependencyEdges,
                    estimatedIndexBytes,
                    duplicateRejections, conflictRejections, capacityRejections,
                    malformedRejections, ledgerRejections, cascadedRemovals,
                    lane.getQueueLength(), totalAdmissionWaitNanos, totalAdmissionHoldNanos,
                    totalValidationNanos, slowValidations);
        } finally {
            lane.unlock();
        }
    }

    private void commit(Entry entry, Set<TxId> parents) {
        TxId id = entry.projection().txId();
        transactionsByHash.put(id, entry);
        byteSize += entry.transaction().size();
        entry.projection().outputs().forEach((outpoint, utxo) ->
                producedByOutpoint.put(outpoint, new ProducedOutput(id, utxo)));
        entry.projection().regularInputs().forEach(outpoint -> spentByOutpoint.put(outpoint, id));
        if (!parents.isEmpty()) {
            parentsByTransaction.put(id, new HashSet<>(parents));
            parents.forEach(parent -> childrenByTransaction
                    .computeIfAbsent(parent, ignored -> new HashSet<>()).add(id));
        }
        indexEntryCount += entry.projection().outputs().size()
                + entry.projection().regularInputs().size()
                + (parents.size() * 2);
    }

    private void recoverFailedCommit(TxId failedId, Throwable commitFailure) {
        transactionsByHash.remove(failedId);
        try {
            rebuildIndexesFromEntries();
        } catch (RuntimeException | Error rebuildFailure) {
            commitFailure.addSuppressed(rebuildFailure);
            transactionsByHash.clear();
            producedByOutpoint.clear();
            spentByOutpoint.clear();
            parentsByTransaction.clear();
            childrenByTransaction.clear();
            byteSize = 0;
            indexEntryCount = 0;
        }
    }

    private void rebuildIndexesFromEntries() {
        producedByOutpoint.clear();
        spentByOutpoint.clear();
        parentsByTransaction.clear();
        childrenByTransaction.clear();
        byteSize = 0;
        indexEntryCount = 0;

        for (Map.Entry<TxId, Entry> mapEntry : transactionsByHash.entrySet()) {
            TxId id = mapEntry.getKey();
            Entry entry = mapEntry.getValue();
            Projection projection = entry.projection();
            Set<TxId> parents = discoverParents(projection.allInputs());
            for (IndexedOutpoint input : projection.regularInputs()) {
                TxId previous = spentByOutpoint.putIfAbsent(input, id);
                if (previous != null) {
                    throw new IllegalStateException("conflicting spend while rebuilding mempool indexes");
                }
            }
            projection.outputs().forEach((outpoint, utxo) ->
                    producedByOutpoint.put(outpoint, new ProducedOutput(id, utxo)));
            if (!parents.isEmpty()) {
                parentsByTransaction.put(id, new HashSet<>(parents));
                parents.forEach(parent -> childrenByTransaction
                        .computeIfAbsent(parent, ignored -> new HashSet<>()).add(id));
            }
            byteSize += entry.transaction().size();
        }
        indexEntryCount = calculateIndexEntryCount();
    }

    private int removeInternal(Set<TxId> requested, boolean cascade, boolean countCascade) {
        if (requested == null || requested.isEmpty()) return 0;
        Set<TxId> direct = new LinkedHashSet<>();
        requested.forEach(id -> {
            if (transactionsByHash.containsKey(id)) direct.add(id);
        });
        if (direct.isEmpty()) return 0;

        Set<TxId> removals = new LinkedHashSet<>(direct);
        if (cascade) {
            ArrayDeque<TxId> queue = new ArrayDeque<>(direct);
            while (!queue.isEmpty()) {
                TxId parent = queue.removeFirst();
                for (TxId child : childrenByTransaction.getOrDefault(parent, Set.of())) {
                    if (removals.add(child)) queue.addLast(child);
                }
            }
        }

        int removedIndexEntries = 0;
        for (TxId id : removals) {
            Entry entry = transactionsByHash.remove(id);
            if (entry == null) continue;
            byteSize -= entry.transaction().size();
            for (IndexedOutpoint outpoint : entry.projection().outputs().keySet()) {
                if (producedByOutpoint.remove(outpoint) != null) removedIndexEntries++;
            }
            for (IndexedOutpoint outpoint : entry.projection().regularInputs()) {
                if (spentByOutpoint.remove(outpoint, id)) removedIndexEntries++;
            }

            Set<TxId> parents = parentsByTransaction.remove(id);
            if (parents != null) {
                removedIndexEntries += parents.size();
                for (TxId parent : parents) {
                    if (removeEdge(childrenByTransaction, parent, id)) removedIndexEntries++;
                }
            }
            Set<TxId> children = childrenByTransaction.remove(id);
            if (children != null) {
                removedIndexEntries += children.size();
                for (TxId child : children) {
                    if (removeEdge(parentsByTransaction, child, id)) removedIndexEntries++;
                }
            }
        }
        indexEntryCount -= removedIndexEntries;
        if (indexEntryCount < 0) {
            throw new IllegalStateException("mempool UTXO index count became negative");
        }
        if (countCascade) cascadedRemovals += Math.max(0, removals.size() - direct.size());
        return removals.size();
    }

    private static boolean removeEdge(Map<TxId, Set<TxId>> index, TxId key, TxId value) {
        Set<TxId> values = index.get(key);
        if (values == null) return false;
        boolean removed = values.remove(value);
        if (values.isEmpty()) index.remove(key);
        return removed;
    }

    private int calculateIndexEntryCount() {
        return producedByOutpoint.size()
                + spentByOutpoint.size()
                + parentsByTransaction.values().stream().mapToInt(Set::size).sum()
                + childrenByTransaction.values().stream().mapToInt(Set::size).sum();
    }

    private Set<TxId> discoverParents(Set<IndexedOutpoint> inputs) {
        Set<TxId> parents = new HashSet<>();
        for (IndexedOutpoint input : inputs) {
            ProducedOutput output = producedByOutpoint.get(input);
            if (output != null) parents.add(output.owner());
        }
        return parents;
    }

    private boolean allParentsLive(Set<TxId> parents) {
        return parents.stream().allMatch(transactionsByHash::containsKey);
    }

    private Utxo resolve(Outpoint outpoint, Function<Outpoint, Utxo> canonicalResolver) {
        IndexedOutpoint indexed = tryIndexedOutpoint(outpoint);
        if (indexed == null) return null;
        if (spentByOutpoint.containsKey(indexed)) return null;
        ProducedOutput produced = producedByOutpoint.get(indexed);
        if (produced != null) return produced.utxo();
        return canonicalResolver.apply(outpoint);
    }

    /** Resolver capability that is valid only for the synchronous validation callback. */
    private final class ScopedResolver implements Function<Outpoint, Utxo> {
        private final Function<Outpoint, Utxo> canonicalResolver;
        private boolean active = true;

        private ScopedResolver(Function<Outpoint, Utxo> canonicalResolver) {
            this.canonicalResolver = canonicalResolver;
        }

        @Override
        public synchronized Utxo apply(Outpoint outpoint) {
            if (!active) {
                throw new IllegalStateException(
                        "mempool admission UTXO resolver is no longer active");
            }
            return resolve(outpoint, canonicalResolver);
        }

        private synchronized void close() {
            active = false;
        }
    }

    private static Utxo safeCanonicalResolve(IndexedOutpoint outpoint,
                                             Function<Outpoint, Utxo> canonicalResolver) {
        try {
            return canonicalResolver.apply(outpoint.external());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Projection project(byte[] txBytes) throws Exception {
        String txHash = TransactionUtil.getTxHash(txBytes);
        TxId txId = TxId.fromHex(txHash);
        Transaction transaction = Transaction.deserialize(txBytes);
        if (transaction.getBody() == null) {
            throw new IllegalArgumentException("transaction body is null");
        }

        Set<IndexedOutpoint> regularInputs = projectInputs(transaction.getBody().getInputs());
        Set<IndexedOutpoint> allInputs = new LinkedHashSet<>(regularInputs);
        allInputs.addAll(projectInputs(transaction.getBody().getReferenceInputs()));
        allInputs.addAll(projectInputs(transaction.getBody().getCollateral()));

        Map<IndexedOutpoint, Utxo> outputs = new LinkedHashMap<>();
        if (transaction.getBody().getOutputs() != null) {
            for (int index = 0; index < transaction.getBody().getOutputs().size(); index++) {
                IndexedOutpoint outpoint = new IndexedOutpoint(txId, index);
                outputs.put(outpoint, TransactionOutputProjector.project(
                        txHash, index, transaction.getBody().getOutputs().get(index)));
            }
        }
        return new Projection(txId, txHash, Set.copyOf(regularInputs),
                Set.copyOf(allInputs), Collections.unmodifiableMap(outputs));
    }

    private static Set<IndexedOutpoint> projectInputs(Collection<TransactionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return Set.of();
        Set<IndexedOutpoint> projected = new LinkedHashSet<>();
        for (TransactionInput input : inputs) {
            projected.add(new IndexedOutpoint(TxId.fromHex(input.getTransactionId()), input.getIndex()));
        }
        return projected;
    }

    private static Set<TxId> toIds(Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return Set.of();
        Set<TxId> ids = new LinkedHashSet<>();
        hashes.forEach(hash -> {
            TxId id = tryTxId(hash);
            if (id != null) ids.add(id);
        });
        return ids;
    }

    private static TxId tryTxId(String hash) {
        try {
            return TxId.fromHex(hash);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IndexedOutpoint tryIndexedOutpoint(Outpoint outpoint) {
        if (outpoint == null) return null;
        try {
            return new IndexedOutpoint(TxId.fromHex(outpoint.txHash()), outpoint.index());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeHash(byte[] txBytes) {
        try {
            return TransactionUtil.getTxHash(txBytes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stableMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static MemPoolTransaction externalCopy(MemPoolTransaction transaction) {
        return new MemPoolTransaction(
                transaction.seqId(),
                transaction.txHash().clone(),
                transaction.txBytes().clone(),
                transaction.txBodyType(),
                transaction.insertedAt());
    }

    private static MempoolAdmissionResult result(MempoolAdmissionResult.Status status,
                                                  String txHash,
                                                  MemPoolTransaction transaction,
                                                  String detail) {
        return new MempoolAdmissionResult(status, txHash, transaction, List.of(), detail);
    }

    private record Entry(MemPoolTransaction transaction, Projection projection) {
    }

    private record Projection(TxId txId,
                              String txHash,
                              Set<IndexedOutpoint> regularInputs,
                              Set<IndexedOutpoint> allInputs,
                              Map<IndexedOutpoint, Utxo> outputs) {
    }

    private record ProducedOutput(TxId owner, Utxo utxo) {
    }

    private record IndexedOutpoint(TxId txId, int index) {
        Outpoint external() {
            return new Outpoint(txId.hex(), index);
        }

        @Override
        public String toString() {
            return txId.hex() + "#" + index;
        }
    }

    /** Immutable value-equality wrapper. Raw byte arrays are never map keys. */
    private static final class TxId {
        private final byte[] bytes;
        private final int hashCode;

        private TxId(byte[] bytes) {
            if (bytes == null || bytes.length != 32) {
                throw new IllegalArgumentException("transaction id must be 32 bytes");
            }
            this.bytes = Arrays.copyOf(bytes, bytes.length);
            this.hashCode = Arrays.hashCode(this.bytes);
        }

        static TxId fromHex(String hex) {
            if (hex == null) throw new IllegalArgumentException("transaction id is null");
            return new TxId(HexUtil.decodeHexString(hex));
        }

        byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        String hex() {
            return HexUtil.encodeHexString(bytes);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof TxId txId && Arrays.equals(bytes, txId.bytes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
