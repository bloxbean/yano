package org.yanoproject.runtime.chain;

/** Stable failure for a non-ledger mempool admission rejection. */
public final class MempoolAdmissionException extends RuntimeException {
    private final MempoolAdmissionResult result;

    public MempoolAdmissionException(MempoolAdmissionResult result) {
        super("Mempool admission failed (" + result.status() + "): " + result.detail());
        this.result = result;
    }

    public MempoolAdmissionResult result() {
        return result;
    }

    /** @return true when the admission may succeed on retry ({@link MempoolAdmissionResult#retryable()}) */
    public boolean retryable() {
        return result.retryable();
    }
}
