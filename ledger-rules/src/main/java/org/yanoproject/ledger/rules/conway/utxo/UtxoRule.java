package org.yanoproject.ledger.rules.conway.utxo;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.utxos.UtxosRule;

import java.math.BigInteger;

/**
 * The Conway {@code UTXO} rule ({@code conwayUtxoTransition}, Conway/Rules/Utxo.hs:211-244): Babbage's
 * {@code babbageUtxoValidation} (Babbage/Rules/Utxo.hs:325-412) — the {@link ConwayScopes#UTXO} units of the protocol
 * version's rule set ({@link UtxoChecks}) — then the {@code UTXOS} sub-rule.
 *
 * <p>Every check is a {@code runTest} (dynamic) or {@code runTestOnSignal} (static) predicate, so they all run and
 * their failures accumulate in the rule set's order; the rule reads the pre-certificate state (invariant 5).</p>
 */
public final class UtxoRule {

    /** Haskell's constant overhead of a UTxO entry in the minimum-UTxO formula (Babbage/TxOut.hs:670-689). */
    public static final BigInteger MIN_UTXO_OVERHEAD = BigInteger.valueOf(160);

    private UtxoRule() {
    }

    /**
     * Runs {@code UTXO} (with its {@code UTXOS} sub-rule) as a sub-rule of {@code UTXOW}.
     *
     * @param utxow the enclosing {@code UTXOW} frame
     */
    public static void apply(RuleFrame utxow) {
        RuleFrame utxo = utxow.child(LedgerRuleName.UTXO);
        TransitionContext ctx = utxo.context();
        utxo.run(ConwayScopes.UTXO, new UtxoSubject(ctx));
        UtxosRule.apply(utxo);
        utxow.subRule(utxo);
    }

    /** {@code babbageMinUTxOValue}: {@code (160 + sizedSize txOut) · coinsPerUTxOByte}, with the output's original size. */
    public static BigInteger minUtxo(ConwayParams pp, RawOutput out) {
        return MIN_UTXO_OVERHEAD.add(BigInteger.valueOf(out.size())).multiply(pp.coinsPerUtxoByte());
    }
}
