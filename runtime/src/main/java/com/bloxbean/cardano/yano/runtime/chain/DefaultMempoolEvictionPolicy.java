package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default mempool eviction policy with three triggers:
 * 1. Block confirmation — remove txs that appear in a confirmed block
 * 2. TTL expiry — remove txs older than maxAgeMillis
 * 3. Max size cap — evict oldest when mempool exceeds maxSize
 * 4. Max byte cap — evict oldest when mempool exceeds maxBytes
 */
@Slf4j
public class DefaultMempoolEvictionPolicy implements MempoolEvictionPolicy {

    private final MemPool memPool;
    private final long maxAgeMillis;
    private final int maxSize;
    private final long maxBytes;

    public DefaultMempoolEvictionPolicy(MemPool memPool, long maxAgeMillis, int maxSize) {
        this(memPool, maxAgeMillis, maxSize, 0);
    }

    public DefaultMempoolEvictionPolicy(MemPool memPool, long maxAgeMillis, int maxSize, long maxBytes) {
        this.memPool = memPool;
        this.maxAgeMillis = maxAgeMillis;
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
    }

    @Override
    public void onBlockApplied(BlockAppliedEvent event) {
        if (event.block() == null || event.block().getTransactionBodies() == null) {
            return;
        }

        List<TransactionBody> transactionBodies = event.block().getTransactionBodies();
        Set<String> confirmedHashes = transactionBodies.stream()
                .map(TransactionBody::getTxHash)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        int removed = memPool.removeByTxHashes(confirmedHashes);
        if (removed > 0) {
            log.info("Evicted {} confirmed txs from mempool (block #{})", removed, event.blockNumber());
        }

        // Remove other mempool transactions that conflict with what canonical
        // apply actually consumed. Valid transactions consume regular inputs;
        // phase-2-invalid transactions consume collateral inputs instead.
        Set<Integer> invalidIndexes = event.block().getInvalidTransactions() != null
                ? new HashSet<>(event.block().getInvalidTransactions()) : Set.of();
        Set<Outpoint> consumed = new LinkedHashSet<>();
        for (int index = 0; index < transactionBodies.size(); index++) {
            TransactionBody body = transactionBodies.get(index);
            var inputs = invalidIndexes.contains(index)
                    ? body.getCollateralInputs() : body.getInputs();
            if (inputs == null) continue;
            inputs.forEach(input -> consumed.add(
                    new Outpoint(input.getTransactionId(), input.getIndex())));
        }
        int invalidated = memPool.removeConflictingInputs(consumed);
        if (invalidated > 0) {
            log.info("Invalidated {} mempool txs conflicting with canonical block #{}",
                    invalidated, event.blockNumber());
        }
    }

    @Override
    public void onPeriodicCheck() {
        // TTL expiry
        if (maxAgeMillis > 0) {
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            int expired = memPool.removeOlderThan(cutoff);
            if (expired > 0) {
                log.info("Evicted {} expired txs from mempool (age > {}ms)", expired, maxAgeMillis);
            }
        }

        // Max size cap
        if (maxSize > 0) {
            int excess = memPool.size() - maxSize;
            if (excess > 0) {
                int evicted = memPool.evictOldest(excess);
                log.info("Evicted {} txs from mempool (exceeded max size {})", evicted, maxSize);
            }
        }

        // Max byte cap
        if (maxBytes > 0) {
            long excess = memPool.byteSize() - maxBytes;
            if (excess > 0) {
                int evicted = memPool.evictOldestUntilBytesAtMost(maxBytes);
                log.info("Evicted {} txs from mempool (exceeded max bytes {})", evicted, maxBytes);
            }
        }
    }
}
