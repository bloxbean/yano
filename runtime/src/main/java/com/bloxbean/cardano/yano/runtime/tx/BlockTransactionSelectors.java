package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockBuildUtxoOverlay;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yano.runtime.chain.MemPool;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Factory methods for block transaction selection strategies.
 */
public final class BlockTransactionSelectors {
    private BlockTransactionSelectors() {
    }

    public static BlockTransactionSelector fromMemPool(MemPool memPool,
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
     * Mempool-backed selector that optionally validates transactions against a
     * block-local UTXO overlay before inclusion.
     */
    private record MempoolBlockTransactionSelector(MemPool memPool,
                                                   Supplier<TransactionValidationService> validatorServiceSupplier,
                                                   Supplier<UtxoState> utxoStateSupplier,
                                                   Logger log)
            implements BlockTransactionSelector {
        @Override
        public boolean hasPendingTransactions() {
            return !memPool.isEmpty();
        }

        @Override
        public List<byte[]> drainForBlock() {
            return drainMempool(validatorServiceSupplier.get(), utxoStateSupplier.get());
        }

        private List<byte[]> drainMempool(TransactionValidationService validatorService,
                                          UtxoState utxoState) {
            if (validatorService == null || utxoState == null) {
                List<byte[]> txList = new ArrayList<>();
                while (!memPool.isEmpty()) {
                    MemPoolTransaction mpt = memPool.getNextTransaction();
                    if (mpt == null) break;
                    txList.add(mpt.txBytes());
                }
                return txList;
            }

            BlockBuildUtxoOverlay overlay = new BlockBuildUtxoOverlay(utxoState);
            List<byte[]> txList = new ArrayList<>();
            while (!memPool.isEmpty()) {
                MemPoolTransaction mpt = memPool.getNextTransaction();
                if (mpt == null) break;

                ValidationResult result = validatorService.validate(mpt.txBytes(), overlay.resolver());
                if (result.valid()) {
                    txList.add(mpt.txBytes());
                    overlay.markSpent(mpt.txBytes());
                } else {
                    String txHashHex = HexUtil.encodeHexString(mpt.txHash());
                    log.warn("Dropping invalid tx {} during block production: {}",
                            txHashHex, result.firstErrorMessage("unknown error"));
                }
            }
            return txList;
        }
    }
}
