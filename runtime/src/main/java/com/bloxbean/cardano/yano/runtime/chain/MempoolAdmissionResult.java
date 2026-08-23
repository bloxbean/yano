package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;

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
        REENTRANT_ADMISSION
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
}
