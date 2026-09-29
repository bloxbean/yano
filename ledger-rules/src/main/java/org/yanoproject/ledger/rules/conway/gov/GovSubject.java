package org.yanoproject.ledger.rules.conway.gov;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.util.Objects;

/**
 * What the {@code GOV} units read ({@code ConwayScopes.GOV}): the transition, the certificate state after
 * {@code CERTS} ({@code certStateAfterCERTS}, Ledger.hs:394-421) and {@code Proposals} as {@code GOV} folds it.
 *
 * @param ctx          the transition
 * @param afterCerts   the state after the transaction's certificates
 * @param proposals    the proposals before the transaction plus this transaction's accepted ones
 * @param proposalTxId the transaction id this transaction's proposals are identified by
 */
public record GovSubject(TransitionContext ctx, LedgerView afterCerts, Proposals proposals, String proposalTxId) {

    public GovSubject {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(afterCerts, "afterCerts");
        Objects.requireNonNull(proposals, "proposals");
        Objects.requireNonNull(proposalTxId, "proposalTxId");
    }
}
