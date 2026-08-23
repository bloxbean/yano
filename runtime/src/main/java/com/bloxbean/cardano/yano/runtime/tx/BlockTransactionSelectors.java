package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockBuildUtxoOverlay;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yano.runtime.chain.MemPool;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Factory methods for block transaction selection strategies. */
public final class BlockTransactionSelectors {
    private BlockTransactionSelectors() {
    }

    public static BlockTransactionSelector fromMemPool(
            MemPool memPool,
            Supplier<TransactionValidationService> validatorServiceSupplier,
            Supplier<UtxoState> utxoStateSupplier,
            Logger log) {
        return new MempoolBlockTransactionSelector(
                Objects.requireNonNull(memPool, "memPool"),
                Objects.requireNonNull(validatorServiceSupplier, "validatorServiceSupplier"),
                Objects.requireNonNull(utxoStateSupplier, "utxoStateSupplier"),
                Objects.requireNonNull(log, "log"));
    }

    /**
     * Selection uses an insertion-ordered immutable snapshot. It never removes
     * selected transactions; confirmation cleanup happens after canonical UTXO
     * apply. One selection may be in flight at a time.
     */
    private static final class MempoolBlockTransactionSelector implements BlockTransactionSelector {
        private final MemPool memPool;
        private final Supplier<TransactionValidationService> validatorServiceSupplier;
        private final Supplier<UtxoState> utxoStateSupplier;
        private final Logger log;
        private final AtomicBoolean selectionInFlight = new AtomicBoolean();
        private volatile Set<String> selectedHashes = Set.of();

        private MempoolBlockTransactionSelector(
                MemPool memPool,
                Supplier<TransactionValidationService> validatorServiceSupplier,
                Supplier<UtxoState> utxoStateSupplier,
                Logger log) {
            this.memPool = memPool;
            this.validatorServiceSupplier = validatorServiceSupplier;
            this.utxoStateSupplier = utxoStateSupplier;
            this.log = log;
        }

        @Override
        public boolean hasPendingTransactions() {
            return !memPool.isEmpty();
        }

        @Override
        public List<byte[]> drainForBlock() {
            if (!selectionInFlight.compareAndSet(false, true)) {
                throw new IllegalStateException("a block transaction selection is already in flight");
            }
            try {
                List<byte[]> selected = selectMempool(
                        validatorServiceSupplier.get(), utxoStateSupplier.get());
                if (selected.isEmpty()) {
                    selectionInFlight.set(false);
                } else {
                    selectedHashes = selected.stream()
                            .map(TransactionUtil::getTxHash).collect(java.util.stream.Collectors.toUnmodifiableSet());
                }
                return selected;
            } catch (RuntimeException | Error e) {
                selectionInFlight.set(false);
                throw e;
            }
        }

        @Override
        public void blockSelectionCompleted() {
            selectedHashes = Set.of();
            selectionInFlight.set(false);
        }

        @Override
        public void blockSelectionFailed() {
            selectedHashes = Set.of();
            selectionInFlight.set(false);
        }

        @Override
        public int invalidateSelectedTransaction(String txHash) {
            return memPool.removeInvalidated(Set.of(txHash));
        }

        @Override
        public void blockCandidatePublished() {
            Set<String> published = selectedHashes;
            if (!published.isEmpty()) memPool.removeByTxHashes(published);
            blockSelectionCompleted();
        }

        private List<byte[]> selectMempool(TransactionValidationService validatorService,
                                           UtxoState utxoState) {
            List<MemPoolTransaction> snapshot = memPool.snapshotTransactions(
                    Integer.MAX_VALUE, Long.MAX_VALUE);
            if (validatorService == null || utxoState == null) {
                return snapshot.stream().map(MemPoolTransaction::txBytes).toList();
            }

            BlockBuildUtxoOverlay overlay = new BlockBuildUtxoOverlay(utxoState);
            List<byte[]> selected = new ArrayList<>();
            for (MemPoolTransaction candidate : snapshot) {
                String txHash = HexUtil.encodeHexString(candidate.txHash());
                if (!memPool.contains(txHash)) continue;

                ValidationResult result = validatorService.validate(candidate.txBytes(), overlay.resolver());
                if (result.valid()) {
                    selected.add(candidate.txBytes());
                    overlay.applyTransaction(candidate.txBytes());
                } else {
                    log.warn("Dropping invalid tx {} during block production: {}",
                            txHash, result.firstErrorMessage("unknown error"));
                    memPool.removeInvalidated(Set.of(txHash));
                }
            }
            return List.copyOf(selected);
        }
    }
}
