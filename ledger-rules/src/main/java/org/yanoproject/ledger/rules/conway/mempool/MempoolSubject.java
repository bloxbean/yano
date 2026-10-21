package org.yanoproject.ledger.rules.conway.mempool;

import com.bloxbean.cardano.client.transaction.spec.TransactionBody;

import org.yanoproject.ledger.rules.view.LedgerView;

import java.util.Objects;

/**
 * What the {@code MEMPOOL} units read ({@code ConwayScopes.MEMPOOL}).
 *
 * @param body     the decoded transaction body
 * @param incoming the state the transaction is admitted against (before any of its own certificates)
 */
public record MempoolSubject(TransactionBody body, LedgerView incoming) {

    public MempoolSubject {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(incoming, "incoming");
    }
}
