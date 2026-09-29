package org.yanoproject.runtime.mempool;

import org.yanoproject.api.model.MemPoolTransaction;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;

import java.util.Objects;
import java.util.Set;

/**
 * One transaction of a {@link MempoolLedgerState}: its stored record, UTxO projection, the provenance of its last
 * successful validation (ADR-056 §6, "Re-application and invalidation") and the mempool parents whose outputs it
 * reads. Immutable.
 *
 * @param transaction the stored transaction (bytes owned by the mempool, never mutated)
 * @param projection  its UTxO projection
 * @param validated   the provenance passed back as {@code previous} on re-validation
 * @param origin      where the transaction came from ({@code LOCAL} or {@code PEER})
 * @param parents     ids of earlier mempool transactions whose outputs it spends or references
 */
public record MempoolEntry(MemPoolTransaction transaction, TxProjection projection, ValidatedTx validated,
                           TxValidationRequest.Origin origin, Set<String> parents) {

    public MempoolEntry {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(validated, "validated");
        Objects.requireNonNull(origin, "origin");
        parents = Set.copyOf(parents);
    }

    public String txHash() {
        return projection.txHash();
    }

    /** @return the stored bytes (not copied: callers treat them as read-only) */
    public byte[] txBytes() {
        return transaction.txBytes();
    }

    public int size() {
        return transaction.size();
    }

    /** @return this entry after a re-validation that produced {@code provenance} */
    public MempoolEntry revalidated(ValidatedTx provenance, Set<String> newParents) {
        return new MempoolEntry(transaction, projection, provenance, origin, newParents);
    }
}
