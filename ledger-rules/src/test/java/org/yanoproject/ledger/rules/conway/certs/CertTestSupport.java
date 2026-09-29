package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;

import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.math.BigInteger;
import java.util.List;

/**
 * Transactions with certificates against the mutation world's certificate state ({@link MutationWorld}): the simple
 * base also spending {@link MutationWorld#RICH_INPUT} (so DRep and pool deposits fit), signed by {@code dev-42} and
 * the keys a test adds. The builder balances only the UTxO side, so each test states the implicit balance
 * ({@code changeAdjust}: minus the deposits Haskell charges, plus the refunds and withdrawals it credits).
 */
final class CertTestSupport {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    private CertTestSupport() {
    }

    static BigInteger ada(long amount) {
        return ADA.multiply(BigInteger.valueOf(amount));
    }

    /** @return the base with {@code certificates}, signed by {@code dev-42} and {@code signers} */
    static TxSpec spec(List<Certificate> certificates, TestKey... signers) {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.RICH_INPUT);
        spec.certs.addAll(certificates);
        for (TestKey key : signers) {
            if (!spec.signers.contains(key)) {
                spec.signers.add(key);
            }
        }
        return spec;
    }

    static TxSpec spec(Certificate certificate, TestKey... signers) {
        return spec(List.of(certificate), signers);
    }

    /** @return {@code spec} with {@code changeAdjust} set */
    static TxSpec balanced(TxSpec spec, BigInteger implicit) {
        spec.changeAdjust = implicit;
        return spec;
    }

    static Withdrawal withdrawal(TestKey key, BigInteger amount) {
        return new Withdrawal(MutationWorld.rewardAccount(key, MutationWorld.NETWORK), amount);
    }

    /** @return the failure names against the world at protocol version 10 */
    static List<String> run(TxSpec spec) {
        return run(spec, 10);
    }

    static List<String> run(TxSpec spec, int protocolMajor) {
        return run(spec, MutationWorld.view(protocolMajor), protocolMajor);
    }

    static List<String> run(TxSpec spec, LedgerView view, int protocolMajor) {
        return EngineTestSupport.names(validate(spec, view, protocolMajor));
    }

    static TxValidationOutcome validate(TxSpec spec, LedgerView view, int protocolMajor) {
        byte[] cbor = ConwayTxBuilder.build(spec, view).cbor();
        return EngineTestSupport.validate(new StubEvaluator(), cbor, view, MutationWorld.env(protocolMajor), null);
    }
}
