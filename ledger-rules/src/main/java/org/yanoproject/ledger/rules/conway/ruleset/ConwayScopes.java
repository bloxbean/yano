package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.certs.CertSubject;
import org.yanoproject.ledger.rules.conway.gov.GovSubject;
import org.yanoproject.ledger.rules.conway.gov.ProposalSubject;
import org.yanoproject.ledger.rules.conway.gov.VotesSubject;
import org.yanoproject.ledger.rules.conway.mempool.MempoolSubject;
import org.yanoproject.ledger.rules.conway.utxo.UtxoSubject;
import org.yanoproject.ledger.rules.conway.utxow.UtxowSubject;

import java.util.List;

/**
 * The scopes of the Conway transition, in the order a transaction meets them (ADR-056 §4): {@code MEMPOOL}, the
 * {@code LEDGER} pre-checks, {@code CERTS} (the base case, then per certificate its {@code DELEG}, {@code POOL} or
 * {@code GOVCERT} kind and the {@code CERT} step), {@code GOV} (the transaction, each proposal, the votes), then
 * {@code UTXOW}, {@code UTXO}, {@code UTXOS}. The families ({@code MempoolRule}, {@code LedgerPreChecks},
 * {@code CertsRule}, {@code GovRule}, {@code UtxowRule}, {@code UtxoRule}, {@code UtxosRule}) run them; the rule set
 * decides what each holds.
 */
public final class ConwayScopes {

    /** {@code MEMPOOL}'s own checks (Conway/Rules/Mempool.hs:103-138). */
    public static final Scope<MempoolSubject> MEMPOOL = new Scope<>("MEMPOOL", MempoolSubject.class);
    /** The {@code LEDGER} predicates and step before {@code CERTS} (Conway/Rules/Ledger.hs:361-392). */
    public static final Scope<TransitionContext> LEDGER = new Scope<>("LEDGER", TransitionContext.class);
    /** The {@code CERTS} base case, before the first certificate (Conway/Rules/Certs.hs:223-241). */
    public static final Scope<TransitionContext> CERTS = new Scope<>("CERTS", TransitionContext.class);
    /** {@code ConwayRegCert} (Conway/Rules/Deleg.hs:233-239). */
    public static final Scope<CertSubject> DELEG_REG = new Scope<>("DELEG.ConwayRegCert", CertSubject.class);
    /** {@code ConwayUnRegCert} (Deleg.hs:240-278). */
    public static final Scope<CertSubject> DELEG_UNREG = new Scope<>("DELEG.ConwayUnRegCert", CertSubject.class);
    /** {@code ConwayDelegCert} (Deleg.hs:279-292). */
    public static final Scope<CertSubject> DELEG_DELEG = new Scope<>("DELEG.ConwayDelegCert", CertSubject.class);
    /** {@code ConwayRegDelegCert} (Deleg.hs:293-301). */
    public static final Scope<CertSubject> DELEG_REG_DELEG = new Scope<>("DELEG.ConwayRegDelegCert",
            CertSubject.class);
    /** {@code RegPool} (Shelley/Rules/Pool.hs:225-306). */
    public static final Scope<CertSubject> POOL_REG = new Scope<>("POOL.RegPool", CertSubject.class);
    /** {@code RetirePool} (Pool.hs:307-323). */
    public static final Scope<CertSubject> POOL_RETIRE = new Scope<>("POOL.RetirePool", CertSubject.class);
    /** {@code ConwayRegDRep} (Conway/Rules/GovCert.hs:210-232). */
    public static final Scope<CertSubject> GOVCERT_REG_DREP = new Scope<>("GOVCERT.ConwayRegDRep", CertSubject.class);
    /** {@code ConwayUnRegDRep} (GovCert.hs:234-255). */
    public static final Scope<CertSubject> GOVCERT_UNREG_DREP = new Scope<>("GOVCERT.ConwayUnRegDRep",
            CertSubject.class);
    /** {@code ConwayUpdateDRep} (GovCert.hs:256-272). */
    public static final Scope<CertSubject> GOVCERT_UPDATE_DREP = new Scope<>("GOVCERT.ConwayUpdateDRep",
            CertSubject.class);
    /** {@code ConwayAuthCommitteeHotKey} (GovCert.hs:273-274, 185-209). */
    public static final Scope<CertSubject> GOVCERT_AUTH_COMMITTEE_HOT = new Scope<>(
            "GOVCERT.ConwayAuthCommitteeHotKey", CertSubject.class);
    /** {@code ConwayResignCommitteeColdKey} (GovCert.hs:275-276, 185-209). */
    public static final Scope<CertSubject> GOVCERT_RESIGN_COMMITTEE_COLD = new Scope<>(
            "GOVCERT.ConwayResignCommitteeColdKey", CertSubject.class);
    /** A certificate's state step, after its {@code DELEG}, {@code POOL} or {@code GOVCERT} checks (Cert.hs:210-223). */
    public static final Scope<CertSubject> CERT = new Scope<>("CERT", CertSubject.class);
    /** {@code GOV}'s transaction-level checks, before the proposals (Conway/Rules/Gov.hs:478-481). */
    public static final Scope<GovSubject> GOV = new Scope<>("GOV", GovSubject.class);
    /** {@code processProposal}, once per proposal (Gov.hs:483-566). */
    public static final Scope<ProposalSubject> GOV_PROPOSAL = new Scope<>("GOV.proposal", ProposalSubject.class);
    /** The vote checks, when the transaction votes (Gov.hs:568-608). */
    public static final Scope<VotesSubject> GOV_VOTES = new Scope<>("GOV.votes", VotesSubject.class);
    /** {@code babbageUtxowTransition} (Babbage/Rules/Utxow.hs:328-391). */
    public static final Scope<UtxowSubject> UTXOW = new Scope<>("UTXOW", UtxowSubject.class);
    /** {@code babbageUtxoValidation} (Babbage/Rules/Utxo.hs:325-412). */
    public static final Scope<UtxoSubject> UTXO = new Scope<>("UTXO", UtxoSubject.class);
    /** {@code utxosTransition} (Conway/Rules/Utxos.hs:207-242). */
    public static final Scope<TransitionContext> UTXOS = new Scope<>("UTXOS", TransitionContext.class);

    /** Every scope, in execution order. */
    public static final List<Scope<?>> ALL = List.of(MEMPOOL, LEDGER, CERTS, DELEG_REG, DELEG_UNREG, DELEG_DELEG,
            DELEG_REG_DELEG, POOL_REG, POOL_RETIRE, GOVCERT_REG_DREP, GOVCERT_UNREG_DREP, GOVCERT_UPDATE_DREP,
            GOVCERT_AUTH_COMMITTEE_HOT, GOVCERT_RESIGN_COMMITTEE_COLD, CERT, GOV, GOV_PROPOSAL, GOV_VOTES, UTXOW, UTXO,
            UTXOS);

    private ConwayScopes() {
    }
}
