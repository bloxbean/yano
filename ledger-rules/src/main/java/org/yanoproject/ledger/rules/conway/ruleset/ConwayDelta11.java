package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.conway.certs.CertsChecks;
import org.yanoproject.ledger.rules.conway.certs.DelegChecks;
import org.yanoproject.ledger.rules.conway.certs.PoolChecks;
import org.yanoproject.ledger.rules.conway.gov.GovChecks;
import org.yanoproject.ledger.rules.conway.ledger.LedgerChecks;
import org.yanoproject.ledger.rules.conway.utxow.UtxowChecks;

import static org.yanoproject.ledger.rules.conway.ruleset.RuleSetDelta.Position.after;
import static org.yanoproject.ledger.rules.conway.ruleset.RuleSetDelta.Position.first;

/**
 * Protocol version 11 (cardano-ledger {@code f649f975}): the hard-fork gates of Conway/Era.hs that hold from
 * protocol version 11, and the checks that test {@code pvMajor pv >= 11} or {@code < 11} directly.
 */
final class ConwayDelta11 {

    private ConwayDelta11() {
    }

    static RuleSetDelta delta() {
        return RuleSetDelta.toVersion(11, "pv11 = pv10.with(ConwayDelta11): "
                        + "hardforkConwayDisallowUnelectedCommitteeFromVoting, "
                        + "hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule, "
                        + "hardforkConwayDELEGIncorrectDepositsAndRefunds, hardforkConwayDisallowDuplicatedVRFKeys; "
                        + "ScriptIntegrityHashMismatch, nOpt /= 0, no disjointRefInputs (pvMajor >= 11)")
                // hardforkConwayDisallowUnelectedCommitteeFromVoting: GOV checks the voters, MEMPOOL no longer
                .retire(ConwayScopes.MEMPOOL, "LEDGER.ConwayMempoolFailure#unelectedCommitteeVoters")
                .add(ConwayScopes.GOV, new GovChecks.UnelectedCommitteeVoters(), first())
                // hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule: Ledger.hs:383-392 instead of Certs.hs:222-241
                .add(ConwayScopes.LEDGER, new LedgerChecks.WithdrawalsMissingAccounts(),
                        after("LEDGER.ConwayWdrlNotDelegatedToDRep"))
                .add(ConwayScopes.LEDGER, new LedgerChecks.IncompleteWithdrawals(),
                        after("LEDGER.ConwayWithdrawalsMissingAccounts"))
                .add(ConwayScopes.LEDGER, CertsChecks.PreCertificateStep.inLedger(),
                        after("LEDGER.ConwayIncompleteWithdrawals"))
                .retire(ConwayScopes.CERTS, "CERTS.WithdrawalsNotInRewardsCERTS")
                .retire(ConwayScopes.CERTS, "CERTS.preCertificateStep")
                // hardforkConwayDELEGIncorrectDepositsAndRefunds: Deleg.hs:199-211, 242-259
                .supersede(ConwayScopes.DELEG_REG, "DELEG.IncorrectDepositDELEG#deposit",
                        new DelegChecks.DepositIncorrect())
                .supersede(ConwayScopes.DELEG_REG_DELEG, "DELEG.IncorrectDepositDELEG#deposit",
                        new DelegChecks.DepositIncorrect())
                .supersede(ConwayScopes.DELEG_UNREG, "DELEG.IncorrectDepositDELEG#refund",
                        new DelegChecks.RefundIncorrect())
                // hardforkConwayDisallowDuplicatedVRFKeys: Pool.hs:265-267, 279-282
                .add(ConwayScopes.POOL_REG, new PoolChecks.VrfKeyHashAlreadyRegistered(),
                        after("POOL.StakePoolCostTooLowPOOL"))
                // Conway/PParams.hs:952-953: pvMajor pv < 11 || nOpt /= 0
                .supersede(ConwayScopes.GOV_PROPOSAL, "GOV.MalformedProposal",
                        ConwayDelta10.MALFORMED_PROPOSAL.withNonZero("Conway/Rules/Gov.hs:393-399, 502 "
                                + "(actionWellFormed: ppuWellFormed pv, Conway/PParams.hs:935-963; nOpt (8): "
                                + "pvMajor pv < 11 || /= 0, :952-953)", 8))
                // Alonzo/Rules/Utxow.hs checkScriptIntegrityHash, pvMajor >= 11: the new constructor with the preimage
                .supersede(ConwayScopes.UTXOW, "UTXOW.PPViewHashesDontMatch",
                        new UtxowChecks.ScriptIntegrityHashMismatch())
                // Babbage/Rules/Utxo.hs:200-214, 356: disjointRefInputs only before protocol version 11
                .retire(ConwayScopes.UTXO, "UTXO.BabbageNonDisjointRefInputs")
                .build();
    }
}
