package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.conway.certs.DRepExpiries;
import org.yanoproject.ledger.rules.conway.certs.DelegChecks;
import org.yanoproject.ledger.rules.conway.gov.GovChecks;
import org.yanoproject.ledger.rules.conway.ledger.LedgerChecks;
import org.yanoproject.ledger.rules.conway.utxos.UtxosChecks;

import static org.yanoproject.ledger.rules.conway.ruleset.RuleSetDelta.Position.after;

/**
 * Protocol version 10: the end of the Conway bootstrap phase ({@code hardforkConwayBootstrapPhase pv = pvMajor pv ==
 * 9}, Conway/Era.hs:257-258, false from 10). Every rule Haskell guards with {@code unless
 * hardforkConwayBootstrapPhase} starts, every rule it runs only while the phase lasts stops.
 */
final class ConwayDelta10 {

    /** {@code ppuWellFormed} from protocol version 10: coinsPerUTxOByte (17) must not be 0 either. */
    static final GovChecks.MalformedProposal MALFORMED_PROPOSAL = new GovChecks.MalformedProposal(
            GovChecks.NON_ZERO_KEYS, "").withNonZero("Conway/Rules/Gov.hs:393-399, 502 (actionWellFormed: "
            + "ppuWellFormed pv, Conway/PParams.hs:935-963; coinsPerUTxOByte (17): hardforkConwayBootstrapPhase pv || "
            + "/= 0, :949-950)", 17);

    private ConwayDelta10() {
    }

    static RuleSetDelta delta() {
        return RuleSetDelta.toVersion(10, "pv10 = pv9.with(ConwayDelta10): the bootstrap phase ends "
                        + "(hardforkConwayBootstrapPhase, Conway/Era.hs:257-258)")
                // Ledger.hs:379-381: unless hardforkConwayBootstrapPhase $ validateWithdrawalsDelegated
                .add(ConwayScopes.LEDGER, new LedgerChecks.WdrlNotDelegatedToDRep(),
                        after("LEDGER.ConwayTxRefScriptsSizeTooBig"))
                // Deleg.hs:220-226: the DRep delegatee must be registered
                .add(ConwayScopes.DELEG_DELEG, new DelegChecks.DelegateeDRepNotRegistered(),
                        after("DELEG.DelegateeStakePoolNotRegisteredDELEG"))
                .add(ConwayScopes.DELEG_REG_DELEG, new DelegChecks.DelegateeDRepNotRegistered(),
                        after("DELEG.DelegateeStakePoolNotRegisteredDELEG"))
                // Gov.hs:483 checkBootstrapProposal only while the bootstrap phase lasts
                .retire(ConwayScopes.GOV_PROPOSAL, "GOV.DisallowedProposalDuringBootstrap")
                // Conway/PParams.hs:949-950
                .supersede(ConwayScopes.GOV_PROPOSAL, "GOV.MalformedProposal", MALFORMED_PROPOSAL)
                // Gov.hs:504-520: unless hardforkConwayBootstrapPhase, the return accounts must exist
                .add(ConwayScopes.GOV_PROPOSAL, new GovChecks.ProposalReturnAccountDoesNotExist(),
                        after("GOV.MalformedProposal"))
                .add(ConwayScopes.GOV_PROPOSAL, new GovChecks.TreasuryWithdrawalReturnAccountsDoNotExist(),
                        after("GOV.ProposalReturnAccountDoesNotExist"))
                // Gov.hs:606 checkBootstrapVotes only while the bootstrap phase lasts
                .retire(ConwayScopes.GOV_VOTES, "GOV.DisallowedVotesDuringBootstrap")
                // Conway/TxInfo.hs:572-581: PlutusV3 contexts carry the certificate deposits again
                .supersede(ConwayScopes.UTXOS, "UTXOS.ValidationTagMismatch",
                        new UtxosChecks.PlutusExecutionAfterBootstrap())
                // GovCert.hs:282-292: a new DRep's expiry counts the dormant epochs
                .supersede(ConwayPolicies.DREP_EXPIRY, new DRepExpiries.DormantAdjusted())
                .build();
    }
}
