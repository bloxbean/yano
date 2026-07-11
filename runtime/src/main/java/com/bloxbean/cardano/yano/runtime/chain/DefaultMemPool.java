package com.bloxbean.cardano.yano.runtime.chain;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultMemPool implements MemPool {
    // LinkedHashMap preserves insertion order for efficient oldest-first iteration
    private final LinkedHashMap<String, MemPoolTransaction> txIndex;
    private final AtomicLong cursor = new AtomicLong(0);
    private long byteSize;

    public DefaultMemPool() {
        this.txIndex = new LinkedHashMap<>();
    }

    @Override
    public synchronized MemPoolTransaction addTransaction(byte[] txBytes) {
        var txHash = TransactionUtil.getTxHash(txBytes);
        // Deduplicate: skip if already present
        if (txIndex.containsKey(txHash)) {
            return txIndex.get(txHash);
        }
        long txSeqId = cursor.incrementAndGet();
        var memPoolTransaction = new MemPoolTransaction(txSeqId, HexUtil.decodeHexString(txHash), txBytes, TxBodyType.CONWAY);
        txIndex.put(txHash, memPoolTransaction);
        byteSize += memPoolTransaction.size();
        return memPoolTransaction;
    }

    @Override
    public synchronized MemPoolTransaction getNextTransaction() {
        var it = txIndex.entrySet().iterator();
        if (!it.hasNext()) return null;
        var entry = it.next();
        it.remove();
        byteSize -= entry.getValue().size();
        return entry.getValue();
    }

    @Override
    public synchronized boolean isEmpty() {
        return txIndex.isEmpty();
    }

    @Override
    public synchronized int size() {
        return txIndex.size();
    }

    @Override
    public synchronized long byteSize() {
        return byteSize;
    }

    @Override
    public synchronized boolean contains(String txHash) {
        return txIndex.containsKey(txHash);
    }

    @Override
    public synchronized MemPoolTransaction getTransaction(String txHash) {
        return txIndex.get(txHash);
    }

    @Override
    public synchronized List<MemPoolTransaction> snapshotTransactions(int maxCount, long maxBytes) {
        if (maxCount <= 0 || maxBytes < 0) {
            return List.of();
        }
        List<MemPoolTransaction> snapshot = new ArrayList<>(Math.min(maxCount, txIndex.size()));
        long selectedBytes = 0;
        for (MemPoolTransaction transaction : txIndex.values()) {
            int size = transaction.size();
            if (!snapshot.isEmpty() && selectedBytes + size > maxBytes) {
                break;
            }
            if (size > maxBytes && snapshot.isEmpty()) {
                break;
            }
            snapshot.add(transaction);
            selectedBytes += size;
            if (snapshot.size() >= maxCount) {
                break;
            }
        }
        return List.copyOf(snapshot);
    }

    @Override
    public synchronized void clear() {
        txIndex.clear();
        byteSize = 0;
    }

    @Override
    public synchronized int removeByTxHashes(Set<String> txHashes) {
        int removed = 0;
        for (String hash : txHashes) {
            MemPoolTransaction transaction = txIndex.remove(hash);
            if (transaction != null) {
                byteSize -= transaction.size();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized int evictOldest(int count) {
        int evicted = 0;
        var it = txIndex.entrySet().iterator();
        while (it.hasNext() && evicted < count) {
            var entry = it.next();
            it.remove();
            byteSize -= entry.getValue().size();
            evicted++;
        }
        return evicted;
    }

    @Override
    public synchronized int evictOldestUntilBytesAtMost(long maxBytes) {
        if (maxBytes < 0) {
            maxBytes = 0;
        }
        int evicted = 0;
        var it = txIndex.entrySet().iterator();
        while (it.hasNext() && byteSize > maxBytes) {
            var entry = it.next();
            it.remove();
            byteSize -= entry.getValue().size();
            evicted++;
        }
        return evicted;
    }

    @Override
    public synchronized int removeOlderThan(long beforeEpochMillis) {
        int removed = 0;
        var it = txIndex.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().insertedAt() < beforeEpochMillis) {
                it.remove();
                byteSize -= entry.getValue().size();
                removed++;
            } else {
                // LinkedHashMap is insertion-ordered, so once we hit a newer entry, stop
                break;
            }
        }
        return removed;
    }
}
