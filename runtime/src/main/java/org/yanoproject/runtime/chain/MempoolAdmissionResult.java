package org.yanoproject.runtime.chain;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import org.yanoproject.api.model.MemPoolTransaction;

import java.util.List;

/** Typed result of one atomic mempool admission attempt. */
public record MempoolAdmissionResult(
        Status status,
        String txHash,
        MemPoolTransaction transaction,
        List<VetoableEvent.Rejection> rejections,
        String detail) {

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        MALFORMED,
        LEDGER_REJECTED,
        CONFLICT,
        TRANSACTION_CAPACITY,
        BYTE_CAPACITY,
        INDEX_CAPACITY,
        REENTRANT_ADMISSION,
        /**
         * The mempool's ledger state is catching up with the canonical chain (ADR-056 §6, rebuild step 7): the
         * transaction was not judged; retry later.
         */
        CATCHING_UP
    }

    public MempoolAdmissionResult {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    public boolean present() {
        return status == Status.ACCEPTED || status == Status.DUPLICATE;
    }

    /** @return true when the submitter should retry later rather than treat the transaction as rejected */
    public boolean retryable() {
        return status == Status.CATCHING_UP;
    }
}
