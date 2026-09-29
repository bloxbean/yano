package org.yanoproject.runtime.tx;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.model.MemPoolTransaction;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.runtime.blockproducer.BlockBuildUtxoOverlay;
import org.yanoproject.runtime.blockproducer.TransactionValidationService;
import org.yanoproject.runtime.chain.MemPool;
import org.yanoproject.runtime.mempool.LedgerMempool;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Factory methods for block transaction selection strategies. */
public final class BlockTransactionSelectors {
    private BlockTransactionSelectors() {
    }

    public static BlockTransactionSelector fromMemPool(
            MemPool memPool,
            Supplier<TransactionValidationService> validatorServiceSupplier,
            Supplier<UtxoState> utxoStateSupplier,
            Logger log) {
        Objects.requireNonNull(memPool, "memPool");
        return fromMemPool(() -> memPool, validatorServiceSupplier, utxoStateSupplier, log);
    }

    /** As {@link #fromMemPool(MemPool, Supplier, Supplier, Logger)}, for a mempool chosen after construction. */
    public static BlockTransactionSelector fromMemPool(
            Supplier<MemPool> memPool,
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
     *
     * <p>With a ledger-state mempool (an engine-API admission engine, ADR-056 §6) selection is
     * {@link LedgerMempool#selectForBlock(long)}: a block-local overlay over its own {@code BLOCK_BUILD}
     * snapshot ticked to the forge slot, rule {@code LEDGER}, each entry's {@code ValidatedTx} as
     * {@code previous}. The legacy {@code DefaultMemPool} keeps the legacy validator over
     * {@link BlockBuildUtxoOverlay} until Phase 8.</p>
     */
    private static final class MempoolBlockTransactionSelector implements BlockTransactionSelector {
        private final Supplier<MemPool> memPools;
        private final Supplier<TransactionValidationService> validatorServiceSupplier;
        private final Supplier<UtxoState> utxoStateSupplier;
        private final Logger log;
        private final AtomicBoolean selectionInFlight = new AtomicBoolean();
        private volatile Set<String> selectedHashes = Set.of();
        // ADR-056 §6: the canonical generation a ledger-state selection was validated against (-1: none).
        private volatile long selectedGeneration = -1;
        private volatile LedgerMempool selectedFrom;

        private MempoolBlockTransactionSelector(
                Supplier<MemPool> memPools,
                Supplier<TransactionValidationService> validatorServiceSupplier,
                Supplier<UtxoState> utxoStateSupplier,
                Logger log) {
            this.memPools = memPools;
            this.validatorServiceSupplier = validatorServiceSupplier;
            this.utxoStateSupplier = utxoStateSupplier;
            this.log = log;
        }

        private MemPool memPool() {
            return memPools.get();
        }

        @Override
        public boolean hasPendingTransactions() {
            return !memPool().isEmpty();
        }

        @Override
        public List<byte[]> drainForBlock() {
            return drainForBlock(-1);
        }

        @Override
        public List<byte[]> drainForBlock(long forgeSlot) {
            if (!selectionInFlight.compareAndSet(false, true)) {
                throw new IllegalStateException("a block transaction selection is already in flight");
            }
            try {
                MemPool memPool = memPool();
                List<byte[]> selected;
                if (memPool instanceof LedgerMempool ledger) {
                    LedgerMempool.BlockSelection selection = ledger.selectForBlock(forgeSlot);
                    selected = selection.transactions();
                    selectedGeneration = selected.isEmpty() ? -1 : selection.generation();
                    selectedFrom = selected.isEmpty() ? null : ledger;
                    if (!selection.rejected().isEmpty() || !selection.skipped().isEmpty()) {
                        log.info("Block selection for slot {}: {} selected ({} re-applied), {} rejected, {} skipped",
                                selection.forgeSlot(), selected.size(), selection.reapplied(),
                                selection.rejected().size(), selection.skipped().size());
                    }
                } else {
                    selected = selectMempool(validatorServiceSupplier.get(), utxoStateSupplier.get());
                }
                if (selected.isEmpty()) {
                    clearSelection();
                } else {
                    selectedHashes = selected.stream()
                            .map(TransactionUtil::getTxHash).collect(Collectors.toUnmodifiableSet());
                }
                return selected;
            } catch (RuntimeException | Error e) {
                clearSelection();
                throw e;
            }
        }

        @Override
        public boolean selectionCurrent() {
            LedgerMempool ledger = selectedFrom;
            long generation = selectedGeneration;
            return ledger == null || generation < 0 || ledger.canonicalGeneration() == generation;
        }

        @Override
        public void blockSelectionCompleted() {
            clearSelection();
        }

        @Override
        public void blockSelectionFailed() {
            clearSelection();
        }

        private void clearSelection() {
            selectedHashes = Set.of();
            selectedGeneration = -1;
            selectedFrom = null;
            selectionInFlight.set(false);
        }

        @Override
        public int invalidateSelectedTransaction(String txHash) {
            return memPool().removeInvalidated(Set.of(txHash));
        }

        @Override
        public void blockCandidatePublished() {
            Set<String> published = selectedHashes;
            if (!published.isEmpty()) memPool().removeByTxHashes(published);
            blockSelectionCompleted();
        }

        /** The legacy selection (the default {@code engine: scalus} path, until ADR-056 Phase 8). */
        private List<byte[]> selectMempool(TransactionValidationService validatorService,
                                           UtxoState utxoState) {
            MemPool memPool = memPool();
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
