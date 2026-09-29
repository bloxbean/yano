package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteRegDelegCert;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;
import org.yanoproject.ledger.rules.conway.ruleset.Scope;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conway {@code CERTS} and {@code CERT} ({@code conwayCertsTransition}, Conway/Rules/Certs.hs:204-246;
 * {@code certTransition}, Cert.hs:210-223), run by {@code LEDGER} when {@code isValid = True} after its pre-checks
 * and before {@code GOV} (Ledger.hs:394-400). The units are the protocol version's rule set's: the base case
 * ({@link ConwayScopes#CERTS}, {@link CertsChecks}), one scope per certificate kind of {@code DELEG}
 * ({@link DelegChecks}), {@code POOL} ({@link PoolChecks}) and {@code GOVCERT} ({@link GovCertChecks}), and the
 * certificate's state step ({@link ConwayScopes#CERT}).
 *
 * <ul>
 *   <li><b>Recursion.</b> {@code CERTS} on {@code gamma :|> c} runs {@code CERTS} on {@code gamma} as a sub-rule,
 *       then {@code CERT} on {@code c} with the state {@code gamma} left. The base case ({@code Empty}) therefore runs
 *       before the first certificate, and certificates run in body order, each against the state after the earlier
 *       ones ({@link TransitionContext#certState()}). The failure list is built by exactly this nesting
 *       ({@link RuleFrame}): {@code CERTS(k)} folds in {@code CERTS(k-1)} and then {@code CERT(k)}, and {@code CERT}
 *       folds in {@code DELEG}, {@code POOL} or {@code GOVCERT} ({@code CERT} records no failure of its own).</li>
 *   <li><b>After a failure.</b> Nothing stops: a failed certificate's predicates are recorded and the next
 *       certificate sees the state the failed one produced ({@link CertState}).</li>
 *   <li><b>Base case.</b> Before protocol version 11 ({@code hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule}
 *       off): {@code WithdrawalsNotInRewardsCERTS} against the incoming accounts, then the dormant-DRep bump, the
 *       voting DReps' expiry refresh and the withdrawal drain (Certs.hs:223-241). From 11 the base case is the
 *       identity and {@code LEDGER} does both (Ledger.hs:384-392): the protocol version 11 delta moves the units.</li>
 *   <li><b>Environment.</b> {@code DELEG} reads the pools of the running state, {@code POOL} the current epoch and
 *       parameters, {@code GOVCERT} the current committee and the committee proposals as they were before the
 *       transaction ({@code committeeProposals}, Ledger.hs:367-370: {@code GOV} adds this transaction's proposals
 *       only after {@code CERTS}).</li>
 * </ul>
 */
public final class CertsRule {

    private CertsRule() {
    }

    /** Runs {@code CERTS} over the transaction's certificates and folds it into {@code ledger}. */
    public static void apply(RuleFrame ledger) {
        TransitionContext ctx = ledger.context();
        List<RawCertificate> certificates = ctx.raw().certificates();
        List<Certificate> decoded = ctx.tx().getBody().getCerts() != null ? ctx.tx().getBody().getCerts()
                : List.of();
        if (decoded.size() != certificates.size()) {
            throw new IllegalStateException("the decoded transaction has " + decoded.size()
                    + " certificates, its bytes " + certificates.size());
        }

        RuleFrame certs = ledger.child(LedgerRuleName.CERTS);
        certs.run(ConwayScopes.CERTS, ctx);
        for (int i = 0; i < certificates.size(); i++) {
            RawCertificate raw = certificates.get(i);
            Certificate certificate = decoded.get(i);
            if (tagOf(certificate) != raw.tag()) {
                throw new IllegalStateException("certificate " + i + " decodes as " + certificate.getClass()
                        .getSimpleName() + " but has tag " + raw.tag());
            }
            RuleFrame next = ledger.child(LedgerRuleName.CERTS);
            next.subRule(certs);
            cert(next, raw, certificate);
            certs = next;
        }
        ledger.subRule(certs);
    }

    /** {@code CERT} (Cert.hs:210-223): dispatch to {@code DELEG}, {@code POOL} or {@code GOVCERT}, then the step. */
    private static void cert(RuleFrame certs, RawCertificate raw, Certificate certificate) {
        TransitionContext ctx = certs.context();
        RuleFrame cert = certs.child(LedgerRuleName.CERT);
        LedgerView state = ctx.certState().current();
        Scope<CertSubject> scope = scopeOf(raw);
        LedgerRuleName family = familyOf(raw);
        RuleFrame leaf = cert.child(family);
        CertSubject subject = switch (family) {
            case POOL -> CertSubject.pool(ctx, raw, certificate, state);
            case GOVCERT -> CertSubject.govCert(ctx, raw, certificate, state);
            default -> CertSubject.deleg(ctx, raw, certificate, state);
        };
        leaf.run(scope, subject);
        cert.subRule(leaf);
        cert.run(ConwayScopes.CERT, subject);
        certs.subRule(cert);
    }

    /** @return the rule a certificate belongs to */
    static LedgerRuleName familyOf(RawCertificate raw) {
        return switch (raw.tag()) {
            case RawCertificate.POOL_REGISTRATION, RawCertificate.POOL_RETIREMENT -> LedgerRuleName.POOL;
            case RawCertificate.AUTH_COMMITTEE_HOT, RawCertificate.RESIGN_COMMITTEE_COLD, RawCertificate.REG_DREP,
                 RawCertificate.UNREG_DREP, RawCertificate.UPDATE_DREP -> LedgerRuleName.GOVCERT;
            default -> LedgerRuleName.DELEG;
        };
    }

    /** @return the scope of a certificate's kind */
    static Scope<CertSubject> scopeOf(RawCertificate raw) {
        return switch (raw.tag()) {
            case RawCertificate.STAKE_REGISTRATION, RawCertificate.REG -> ConwayScopes.DELEG_REG;
            case RawCertificate.STAKE_DEREGISTRATION, RawCertificate.UNREG -> ConwayScopes.DELEG_UNREG;
            case RawCertificate.STAKE_DELEGATION, RawCertificate.VOTE_DELEG, RawCertificate.STAKE_VOTE_DELEG ->
                    ConwayScopes.DELEG_DELEG;
            case RawCertificate.STAKE_REG_DELEG, RawCertificate.VOTE_REG_DELEG, RawCertificate.STAKE_VOTE_REG_DELEG ->
                    ConwayScopes.DELEG_REG_DELEG;
            case RawCertificate.POOL_REGISTRATION -> ConwayScopes.POOL_REG;
            case RawCertificate.POOL_RETIREMENT -> ConwayScopes.POOL_RETIRE;
            case RawCertificate.REG_DREP -> ConwayScopes.GOVCERT_REG_DREP;
            case RawCertificate.UNREG_DREP -> ConwayScopes.GOVCERT_UNREG_DREP;
            case RawCertificate.UPDATE_DREP -> ConwayScopes.GOVCERT_UPDATE_DREP;
            case RawCertificate.AUTH_COMMITTEE_HOT -> ConwayScopes.GOVCERT_AUTH_COMMITTEE_HOT;
            case RawCertificate.RESIGN_COMMITTEE_COLD -> ConwayScopes.GOVCERT_RESIGN_COMMITTEE_COLD;
            default -> throw new IllegalArgumentException(raw + " is not a DELEG certificate");
        };
    }

    /**
     * {@code withdrawalsThatDoNotDrainAccounts} (cardano-ledger-core State/Account.hs:202-270): a withdrawal whose
     * account address is on another network or has no account is missing; one whose amount is not the exact balance
     * is incomplete.
     *
     * @param missing    the missing (or wrong-network) withdrawals
     * @param incomplete the incomplete withdrawals, with the balance
     */
    public record UndrainedWithdrawals(List<String> missing, List<String> incomplete) {

        public UndrainedWithdrawals {
            missing = List.copyOf(missing);
            incomplete = List.copyOf(incomplete);
        }

        public boolean isEmpty() {
            return missing.isEmpty() && incomplete.isEmpty();
        }
    }

    /** @return the withdrawals of the transaction that do not drain their accounts in {@code accounts} */
    public static UndrainedWithdrawals withdrawalsThatDoNotDrainAccounts(TransitionContext ctx, LedgerView accounts) {
        int network = CertState.network(ctx);
        List<String> missing = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();
        for (RawTransaction.Withdrawal w : ctx.raw().withdrawals()) {
            byte[] address = w.rewardAccount();
            Optional<AccountState> account = Optional.empty();
            if (w.network() == network) {
                account = accounts.account(accountCredential(address)).orElseThrowUnavailable();
            }
            if (account.isEmpty()) {
                missing.add(w.toString());
            } else if (!w.amount().equals(account.get().rewardBalance())) {
                incomplete.add(w + " (balance " + account.get().rewardBalance() + ")");
            }
        }
        return new UndrainedWithdrawals(missing, incomplete);
    }

    /** The credential of an account address (header bit 4: script). */
    static CredentialKey accountCredential(byte[] accountAddress) {
        byte[] hash = new byte[28];
        System.arraycopy(accountAddress, 1, hash, 0, 28);
        return new CredentialKey((accountAddress[0] & 0x10) != 0 ? CredentialType.SCRIPT : CredentialType.KEY,
                HexUtil.encodeHexString(hash));
    }

    /** @return the CDDL tag CCL decoded a certificate from */
    static int tagOf(Certificate certificate) {
        return switch (certificate) {
            case StakeRegistration c -> RawCertificate.STAKE_REGISTRATION;
            case StakeDeregistration c -> RawCertificate.STAKE_DEREGISTRATION;
            case StakeDelegation c -> RawCertificate.STAKE_DELEGATION;
            case PoolRegistration c -> RawCertificate.POOL_REGISTRATION;
            case PoolRetirement c -> RawCertificate.POOL_RETIREMENT;
            case RegCert c -> RawCertificate.REG;
            case UnregCert c -> RawCertificate.UNREG;
            case VoteDelegCert c -> RawCertificate.VOTE_DELEG;
            case StakeVoteDelegCert c -> RawCertificate.STAKE_VOTE_DELEG;
            case StakeRegDelegCert c -> RawCertificate.STAKE_REG_DELEG;
            case VoteRegDelegCert c -> RawCertificate.VOTE_REG_DELEG;
            case StakeVoteRegDelegCert c -> RawCertificate.STAKE_VOTE_REG_DELEG;
            case AuthCommitteeHotCert c -> RawCertificate.AUTH_COMMITTEE_HOT;
            case ResignCommitteeColdCert c -> RawCertificate.RESIGN_COMMITTEE_COLD;
            case RegDRepCert c -> RawCertificate.REG_DREP;
            case UnregDRepCert c -> RawCertificate.UNREG_DREP;
            case UpdateDRepCert c -> RawCertificate.UPDATE_DREP;
            default -> -1;
        };
    }

    /** @return a coin for a failure detail */
    static String coin(BigInteger coin) {
        return "Coin " + coin;
    }
}
