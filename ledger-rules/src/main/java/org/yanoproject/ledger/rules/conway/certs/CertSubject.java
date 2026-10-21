package org.yanoproject.ledger.rules.conway.certs;

import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;

import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.RawCertificate;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;

import java.util.Objects;
import java.util.Optional;

/**
 * What the certificate units read (the {@code DELEG}, {@code POOL}, {@code GOVCERT} and {@code CERT} scopes of
 * {@code ConwayScopes}): one certificate, the state the earlier certificates left ({@code CERTS} threads it,
 * {@link TransitionContext#certState()}), and what its rule reads of that state up front — the account for
 * {@code DELEG}, the pool for {@code POOL}, the DRep for the DRep certificates of {@code GOVCERT}.
 */
public final class CertSubject {

    private final TransitionContext ctx;
    private final RawCertificate raw;
    private final Certificate certificate;
    private final LedgerView state;
    private final CredentialKey credential;
    private final Optional<AccountState> account;
    private final PoolId poolId;
    private final Optional<PoolState> pool;
    private final Optional<DRepState> drep;

    private CertSubject(TransitionContext ctx, RawCertificate raw, Certificate certificate, LedgerView state,
                        CredentialKey credential, Optional<AccountState> account, PoolId poolId,
                        Optional<PoolState> pool, Optional<DRepState> drep) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.raw = Objects.requireNonNull(raw, "raw");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        this.state = Objects.requireNonNull(state, "state");
        this.credential = credential;
        this.account = account;
        this.poolId = poolId;
        this.pool = pool;
        this.drep = drep;
    }

    /** A {@code DELEG} certificate: reads the credential's account. */
    static CertSubject deleg(TransitionContext ctx, RawCertificate raw, Certificate certificate, LedgerView state) {
        CredentialKey credential = CertState.key(raw.credential());
        Optional<AccountState> account = state.account(credential).orElseThrowUnavailable();
        return new CertSubject(ctx, raw, certificate, state, credential, account, null, null, null);
    }

    /** A {@code POOL} certificate: reads the pool. */
    static CertSubject pool(TransitionContext ctx, RawCertificate raw, Certificate certificate, LedgerView state) {
        PoolId id = PoolId.of(raw.poolId());
        Optional<PoolState> registered = state.pool(id).orElseThrowUnavailable();
        return new CertSubject(ctx, raw, certificate, state, null, null, id, registered, null);
    }

    /** A {@code GOVCERT} certificate: reads the DRep of a DRep certificate. */
    static CertSubject govCert(TransitionContext ctx, RawCertificate raw, Certificate certificate, LedgerView state) {
        CredentialKey credential = CertState.key(raw.credential());
        Optional<DRepState> drep = switch (raw.tag()) {
            case RawCertificate.REG_DREP, RawCertificate.UNREG_DREP, RawCertificate.UPDATE_DREP ->
                    state.drep(credential).orElseThrowUnavailable();
            default -> null;
        };
        return new CertSubject(ctx, raw, certificate, state, credential, null, null, null, drep);
    }

    public TransitionContext ctx() {
        return ctx;
    }

    /** @return the certificate as its bytes encode it */
    public RawCertificate raw() {
        return raw;
    }

    /** @return the certificate as CCL decoded it (for the state step) */
    public Certificate certificate() {
        return certificate;
    }

    /** @return the state after the transaction's earlier certificates */
    public LedgerView state() {
        return state;
    }

    /** @return the stake, DRep or committee cold credential; null for a pool certificate */
    public CredentialKey credential() {
        return credential;
    }

    /** @return the credential's account ({@code DELEG} certificates only) */
    public Optional<AccountState> account() {
        return require(account, "account");
    }

    public PoolId poolId() {
        return require(poolId, "pool id");
    }

    /** @return the pool's registration ({@code POOL} certificates only) */
    public Optional<PoolState> pool() {
        return require(pool, "pool");
    }

    /** @return the DRep's registration (the DRep certificates of {@code GOVCERT} only) */
    public Optional<DRepState> drep() {
        return require(drep, "drep");
    }

    /** @return the typed epoch-effective protocol parameters */
    public ConwayParams params() {
        return CertState.params(ctx);
    }

    /** @return the ledger's network id, 0 (testnet) or 1 (mainnet) */
    public int network() {
        return CertState.network(ctx);
    }

    private <T> T require(T value, String what) {
        if (value == null) {
            throw new IllegalStateException("certificate " + raw.index() + " (tag " + raw.tag() + ") has no " + what);
        }
        return value;
    }
}
