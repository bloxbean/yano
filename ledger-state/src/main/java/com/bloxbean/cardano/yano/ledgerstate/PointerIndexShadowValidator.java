package com.bloxbean.cardano.yano.ledgerstate;

import org.slf4j.Logger;

/** TODO(#97): remove after the pre-Conway clone trial unless retained for diagnostics. */
final class PointerIndexShadowValidator {
    private PointerIndexShadowValidator() {
    }

    static void requireParity(
            Logger log,
            int epoch,
            UtxoBalanceAggregator.PointerAggregation indexed,
            UtxoBalanceAggregator.PointerAggregation scanned,
            boolean backfilled) {
        boolean countsMatch = indexed.resolved() == scanned.resolved()
                && indexed.failed() == scanned.failed();
        if (backfilled) countsMatch &= indexed.records() == scanned.records();
        if (countsMatch && indexed.balances().equals(scanned.balances())) {
            log.info("Pointer UTXO shadow parity PASSED: epoch={}, indexedRecords={}, "
                            + "resolved={}, failed={}, backfilled={}",
                    epoch, indexed.records(), indexed.resolved(), indexed.failed(), backfilled);
            return;
        }
        log.error("Pointer UTXO shadow parity FAILED: epoch={}, backfilled={}, "
                        + "indexedRecords={}, scannedRecords={}, indexedResolved={}, "
                        + "scannedResolved={}, indexedFailed={}, scannedFailed={}, "
                        + "indexedBalances={}, scannedBalances={}",
                epoch, backfilled, indexed.records(), scanned.records(),
                indexed.resolved(), scanned.resolved(), indexed.failed(), scanned.failed(),
                indexed.balances(), scanned.balances());
        throw new IllegalStateException(
                "Pointer UTXO index diverged from scan at epoch " + epoch);
    }
}
