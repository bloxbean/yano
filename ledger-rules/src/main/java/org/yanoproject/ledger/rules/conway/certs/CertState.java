package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.RawCredential;
import org.yanoproject.ledger.rules.effects.IntraTxFold;
import org.yanoproject.ledger.rules.effects.LedgerChange;
import org.yanoproject.ledger.rules.effects.LedgerChange.RewardWithdrawn;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;

import java.util.ArrayList;
import java.util.List;

/**
 * How the certificate rules advance {@link TransitionContext#certState()}: with the same
 * {@link TxEffectsDeriver#preCertificateChanges} and {@link TxEffectsDeriver#certificateChanges} that derive a valid
 * transaction's effects, so validation and effects cannot disagree about the intermediate state (ADR-056 invariant 5).
 *
 * <p>Haskell's rules keep going after a failed predicate, and every later certificate of the transaction sees the
 * state the failed one produced ({@code small-steps} {@code SubTrans} continues with the sub-rule's state,
 * Extended.hs:713-724). For a failing certificate that state is what the Haskell transition returns anyway: the
 * deriver's changes, except where Haskell returns its input state unchanged (a deregistration or delegation of an
 * unregistered credential, the deregistration or update of an unregistered DRep, the retirement of an unregistered
 * pool), which the rules signal as "no state change". A failing transaction is rejected, so this only decides which
 * failures later certificates report.</p>
 */
public final class CertState {

    private CertState() {
    }

    /** @return the view key of a certificate credential */
    public static CredentialKey key(RawCredential credential) {
        return new CredentialKey(credential.script() ? CredentialType.SCRIPT : CredentialType.KEY,
                credential.hashHex());
    }

    /** @return the ledger's network id, 0 (testnet) or 1 (mainnet) */
    public static int network(TransitionContext ctx) {
        return ctx.env().networkId() == NetworkId.MAINNET ? 1 : 0;
    }

    /** @return the typed epoch-effective protocol parameters */
    static ConwayParams params(TransitionContext ctx) {
        return new ConwayParams(ctx.params());
    }

    /**
     * Applies the step before the first certificate: {@code updateDormantDRepExpiries},
     * {@code updateVotingDRepExpiries} and {@code drainAccounts} (Conway/Rules/Certs.hs:237-241 before protocol
     * version 11, Ledger.hs:384-392 from 11).
     */
    public static void advancePreCertificate(TransitionContext ctx) {
        IntraTxFold fold = ctx.certState();
        LedgerView state = fold.current();
        List<LedgerChange> changes = new ArrayList<>();
        for (LedgerChange change : TxEffectsDeriver.preCertificateChanges(state, ctx.tx().getBody(), ctx.params(),
                ctx.env())) {
            // drainAccounts skips a withdrawal without an account (updateAccountBalances,
            // cardano-ledger-core State/Account.hs:306-320); only a failing transaction has one.
            if (change instanceof RewardWithdrawn w
                    && state.account(w.credential()).orElseThrowUnavailable().isEmpty()) {
                continue;
            }
            changes.add(change);
        }
        ctx.certState(fold.step(changes));
    }

    /**
     * Applies one certificate's step.
     *
     * @param changesState false when Haskell's transition returns its input state for this certificate
     */
    static void advance(TransitionContext ctx, Certificate certificate, boolean changesState) {
        IntraTxFold fold = ctx.certState();
        List<LedgerChange> changes = changesState
                ? TxEffectsDeriver.certificateChanges(fold.current(), certificate, ctx.params(), ctx.env())
                : List.of();
        ctx.certState(fold.step(changes));
    }
}
