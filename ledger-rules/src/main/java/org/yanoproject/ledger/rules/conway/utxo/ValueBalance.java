package org.yanoproject.ledger.rules.conway.utxo;

import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteRegDelegCert;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.LedgerValue;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Conway value conservation ({@code validateValueNotConservedUTxO}, Shelley/Rules/Utxo.hs:506-522), against the
 * <em>pre-certificate</em> state (ADR-056 invariant 5; Conway/Rules/Ledger.hs:428-436 passes UTXOW the certState
 * from before CERTS):
 *
 * <pre>
 * consumed = Σ inputs ∈ UTxO + refunds + withdrawals + minted          getConsumedMaryValue (Mary/UTxO.hs:65-81)
 * produced = Σ outputs + fee + deposits + burned + donation            conwayProducedValue (Conway/UTxO.hs:107-117)
 *                                                                     via getProducedMaryValue (Mary/UTxO.hs:83-91)
 * </pre>
 *
 * <ul>
 *   <li><b>deposits</b> ({@code conwayTotalDepositsTxBody}, Conway/TxBody.hs:364-391): {@code ppKeyDeposit} per
 *       stake registration ({@code RegTxCert}, {@code RegDepositTxCert}, {@code RegDepositDelegTxCert};
 *       Shelley/TxCert.hs:611-630), {@code ppPoolDeposit} per pool that is neither registered nor registered
 *       earlier in the transaction, {@code ppDRepDeposit} per DRep registration (Conway/TxCert.hs:828-835),
 *       {@code ppGovActionDeposit} per proposal ({@code conwayProposalsDeposits}).</li>
 *   <li><b>refunds</b> ({@code conwayTotalRefundsTxCerts}, Conway/TxCert.hs:837-860): a stake deregistration
 *       refunds {@code ppKeyDeposit} when the credential was registered earlier in the same transaction,
 *       otherwise the deposit recorded in the pre-certificate account, otherwise nothing
 *       ({@code shelleyTotalRefundsTxCerts}, Shelley/TxCert.hs:633-660); a DRep deregistration refunds the
 *       amount its certificate states.</li>
 * </ul>
 */
public final class ValueBalance {

    /** Both sides of the balance. */
    public record Balance(LedgerValue consumed, LedgerValue produced) {
        public boolean conserved() {
            return consumed.equals(produced);
        }
    }

    private ValueBalance() {
    }

    public static Balance of(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        ConwayParams pp = new ConwayParams(ctx.params());
        List<Certificate> certs = ctx.tx().getBody().getCerts() != null ? ctx.tx().getBody().getCerts() : List.of();

        LedgerValue consumed = LedgerValue.ZERO;
        for (TxInRef in : raw.inputSet()) {
            Optional<LedgerValue> value = ctx.utxo(in).map(e -> LedgerValue.of(e.output().getValue()));
            if (value.isPresent()) {
                consumed = consumed.add(value.get());
            }
        }
        BigInteger withdrawals = raw.withdrawals().stream().map(RawTransaction.Withdrawal::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        consumed = consumed.add(LedgerValue.ofCoin(refunds(certs, pp, ctx.preState()).add(withdrawals)))
                .add(new LedgerValue(BigInteger.ZERO, filterMint(raw.mint(), true)));

        LedgerValue produced = LedgerValue.ZERO;
        for (RawOutput out : raw.outputs()) {
            produced = produced.add(out.value());
        }
        BigInteger deposits = deposits(certs, raw.proposalCount(), pp, ctx.preState());
        produced = produced.add(LedgerValue.ofCoin(raw.fee().add(deposits).add(raw.donation())))
                .add(new LedgerValue(BigInteger.ZERO, filterMint(raw.mint(), false)));
        return new Balance(consumed, produced);
    }

    /** Minted quantities (positive) or burned ones (negated negatives), as {@code getConsumedMaryValue}/{@code burnedMultiAssets}. */
    private static Map<String, Map<String, BigInteger>> filterMint(Map<String, Map<String, BigInteger>> mint,
                                                                   boolean minted) {
        Map<String, Map<String, BigInteger>> result = new TreeMap<>();
        mint.forEach((policy, names) -> names.forEach((name, quantity) -> {
            if (minted && quantity.signum() > 0) {
                result.computeIfAbsent(policy, k -> new TreeMap<>()).put(name, quantity);
            } else if (!minted && quantity.signum() < 0) {
                result.computeIfAbsent(policy, k -> new TreeMap<>()).put(name, quantity.negate());
            }
        }));
        return result;
    }

    /** {@code conwayTotalDepositsTxBody} with the pre-certificate pool registrations. */
    static BigInteger deposits(List<Certificate> certs, int proposals, ConwayParams pp, LedgerView preState) {
        long stakeRegistrations = 0;
        long drepRegistrations = 0;
        Set<PoolId> newPools = new HashSet<>();
        for (Certificate cert : certs) {
            if (isStakeRegistration(cert)) {
                stakeRegistrations++;
            } else if (cert instanceof RegDRepCert) {
                drepRegistrations++;
            } else if (cert instanceof PoolRegistration pool) {
                PoolId id = PoolId.of(pool.getOperator());
                boolean registered = preState.pool(id).orElseThrowUnavailable().isPresent();
                if (!registered) {
                    newPools.add(id);
                }
            }
        }
        BigInteger total = pp.keyDeposit().multiply(BigInteger.valueOf(stakeRegistrations))
                .add(pp.poolDeposit().multiply(BigInteger.valueOf(newPools.size())))
                .add(pp.drepDeposit().multiply(BigInteger.valueOf(drepRegistrations)));
        if (proposals > 0) {
            total = total.add(pp.govActionDeposit().multiply(BigInteger.valueOf(proposals)));
        }
        return total;
    }

    /** {@code conwayTotalRefundsTxCerts} against the pre-certificate accounts. */
    static BigInteger refunds(List<Certificate> certs, ConwayParams pp, LedgerView preState) {
        Set<CredentialKey> registeredHere = new HashSet<>();
        BigInteger total = BigInteger.ZERO;
        for (Certificate cert : certs) {
            Optional<CredentialKey> registration = registeredCredential(cert);
            if (registration.isPresent()) {
                registeredHere.add(registration.get());
                continue;
            }
            Optional<CredentialKey> deregistration = deregisteredCredential(cert);
            if (deregistration.isPresent()) {
                CredentialKey cred = deregistration.get();
                if (registeredHere.remove(cred)) {
                    total = total.add(pp.keyDeposit());
                } else {
                    Optional<AccountState> account = preState.account(cred).orElseThrowUnavailable();
                    if (account.isPresent()) {
                        total = total.add(account.get().deposit());
                    }
                }
            } else if (cert instanceof UnregDRepCert drep) {
                total = total.add(drep.getCoin() != null ? drep.getCoin() : BigInteger.ZERO);
            }
        }
        return total;
    }

    /** {@code lookupRegStakeTxCert} (Conway/TxCert.hs:135-139). */
    private static boolean isStakeRegistration(Certificate cert) {
        return registeredCredential(cert).isPresent();
    }

    private static Optional<CredentialKey> registeredCredential(Certificate cert) {
        return switch (cert) {
            case StakeRegistration c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            case RegCert c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            case StakeRegDelegCert c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            case VoteRegDelegCert c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            case StakeVoteRegDelegCert c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            default -> Optional.empty();
        };
    }

    /** {@code lookupUnRegStakeTxCert} (Conway/TxCert.hs:140-143). */
    private static Optional<CredentialKey> deregisteredCredential(Certificate cert) {
        return switch (cert) {
            case StakeDeregistration c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            case UnregCert c -> Optional.of(CredentialKey.of(c.getStakeCredential()));
            default -> Optional.empty();
        };
    }
}
