package org.yanoproject.ledger.rules.conway.ruleset;

import org.yanoproject.ledger.rules.conway.certs.CertsChecks;
import org.yanoproject.ledger.rules.conway.certs.DRepExpiries;
import org.yanoproject.ledger.rules.conway.certs.DelegChecks;
import org.yanoproject.ledger.rules.conway.certs.GovCertChecks;
import org.yanoproject.ledger.rules.conway.certs.PoolChecks;
import org.yanoproject.ledger.rules.conway.gov.GovChecks;
import org.yanoproject.ledger.rules.conway.ledger.LedgerChecks;
import org.yanoproject.ledger.rules.conway.mempool.MempoolChecks;
import org.yanoproject.ledger.rules.conway.utxo.UtxoChecks;
import org.yanoproject.ledger.rules.conway.utxos.UtxosChecks;
import org.yanoproject.ledger.rules.conway.utxow.UtxowChecks;

/**
 * The base rule set: Conway at protocol version 9, the bootstrap phase ({@code hardforkConwayBootstrapPhase},
 * Conway/Era.hs:257-258), at cardano-ledger {@code f649f975}. Every later version is this set with deltas
 * ({@link ConwayDelta10}, {@link ConwayDelta11}; {@link ConwayRuleSets}).
 *
 * <p>Each scope lists its units in Haskell's order; the comments give the Haskell line of each. Editing this file
 * changes every protocol version's rule set (the frozen manifests show it): a change that only a later version makes
 * belongs in that version's delta.</p>
 */
final class ConwayBaseRules {

    /** The first protocol version the Java engine validates. */
    static final int VERSION = 9;

    private ConwayBaseRules() {
    }

    static ConwayRuleSet pv9() {
        return ConwayRuleSet.base(VERSION, "pv9 = base: Conway during the bootstrap phase "
                        + "(hardforkConwayBootstrapPhase, Conway/Era.hs:257-258)")
                // Conway/Rules/Mempool.hs:103-138
                .scope(ConwayScopes.MEMPOOL,
                        new MempoolChecks.AllInputsSpent(),                       // :113-118, whenFailureFreeDefault
                        new MempoolChecks.UnelectedCommitteeVoters())             // :120-138, PV <= 10
                // Conway/Rules/Ledger.hs:361-392 (isValid = True)
                .scope(ConwayScopes.LEDGER,
                        new LedgerChecks.TreasuryValueMismatch(),                 // :364
                        new LedgerChecks.TxRefScriptsSizeTooBig())                // :365
                // Conway/Rules/Certs.hs:223-241, the base case (Empty)
                .scope(ConwayScopes.CERTS,
                        new CertsChecks.WithdrawalsNotInRewards(),                // :222-236
                        CertsChecks.PreCertificateStep.inCerts())                 // :237-241
                // Conway/Rules/Deleg.hs:187-301
                .scope(ConwayScopes.DELEG_REG,
                        new DelegChecks.IncorrectDeposit(),                       // :199-211
                        new DelegChecks.StakeKeyRegistered())                     // :212-214
                .scope(ConwayScopes.DELEG_UNREG,
                        new DelegChecks.IncorrectRefund(),                        // :242-259
                        new DelegChecks.NonZeroAccountBalance(),                  // :260-268
                        new DelegChecks.StakeKeyNotRegistered())                  // :271
                .scope(ConwayScopes.DELEG_DELEG,
                        new DelegChecks.DelegateeStakePoolNotRegistered(),        // :215-219
                        new DelegChecks.StakeKeyNotRegistered())                  // :283
                .scope(ConwayScopes.DELEG_REG_DELEG,
                        new DelegChecks.IncorrectDeposit(),                       // :199-211
                        new DelegChecks.StakeKeyRegistered(),                     // :212-214
                        new DelegChecks.DelegateeStakePoolNotRegistered())        // :215-219
                // Shelley/Rules/Pool.hs:209-323
                .scope(ConwayScopes.POOL_REG,
                        new PoolChecks.WrongNetwork(),                            // :231-243
                        new PoolChecks.MetadataHashTooBig(),                      // :245-250
                        new PoolChecks.CostTooLow())                              // :252-261
                .scope(ConwayScopes.POOL_RETIRE,
                        new PoolChecks.NotRegisteredOnKey(),                      // :308
                        new PoolChecks.RetirementWrongEpoch())                    // :309-322
                // Conway/Rules/GovCert.hs:180-276
                .scope(ConwayScopes.GOVCERT_REG_DREP,
                        new GovCertChecks.DRepAlreadyRegistered(),                // :210-212
                        new GovCertChecks.DRepIncorrectDeposit())                 // :213-219
                .scope(ConwayScopes.GOVCERT_UNREG_DREP,
                        new GovCertChecks.DRepNotRegistered(),                    // :241
                        new GovCertChecks.DRepIncorrectRefund())                  // :236-242
                .scope(ConwayScopes.GOVCERT_UPDATE_DREP,
                        new GovCertChecks.DRepNotRegistered())                    // :257-258
                .scope(ConwayScopes.GOVCERT_AUTH_COMMITTEE_HOT,
                        new GovCertChecks.CommitteeHasPreviouslyResigned(),       // :190-196
                        new GovCertChecks.CommitteeIsUnknown())                   // :197-205
                .scope(ConwayScopes.GOVCERT_RESIGN_COMMITTEE_COLD,
                        new GovCertChecks.CommitteeHasPreviouslyResigned(),       // :190-196
                        new GovCertChecks.CommitteeIsUnknown())                   // :197-205
                // Conway/Rules/Cert.hs:210-223
                .scope(ConwayScopes.CERT,
                        new CertsChecks.ApplyCertificate())
                // Conway/Rules/Gov.hs:446-613
                // ConwayScopes.GOV is empty until protocol version 11
                .scope(ConwayScopes.GOV_PROPOSAL,
                        new GovChecks.DisallowedProposalDuringBootstrap(),        // :483, bootstrap only
                        new GovChecks.ProposalCantFollow(),                       // :488-499
                        new GovChecks.MalformedProposal(GovChecks.NON_ZERO_KEYS,  // :502
                                "Conway/Rules/Gov.hs:393-399, 502 (actionWellFormed: ppuWellFormed pv, "
                                        + "Conway/PParams.hs:935-963; coinsPerUTxOByte may be 0 during bootstrap)"),
                        new GovChecks.ProposalDepositIncorrect(),                 // :522-530
                        new GovChecks.ProposalProcedureNetworkIdMismatch(),       // :532-535
                        new GovChecks.TreasuryWithdrawalsNetworkIdMismatch(),     // :539-544
                        GovChecks.InvalidGuardrailsScriptHash.forTreasuryWithdrawals(), // :547
                        new GovChecks.ZeroTreasuryWithdrawals(),                  // :550
                        new GovChecks.ConflictingCommitteeUpdate(),               // :551-553
                        new GovChecks.ExpirationEpochTooSmall(),                  // :555-556
                        GovChecks.InvalidGuardrailsScriptHash.forParameterChange(),     // :557-558
                        new GovChecks.ProposalsAddAction(),                       // :561-566
                        new GovChecks.InvalidPrevGovActionId())                   // :561-566
                .scope(ConwayScopes.GOV_VOTES,
                        new GovChecks.VotersDoNotExist(),                         // :604
                        new GovChecks.GovActionsDoNotExist(),                     // :605
                        new GovChecks.DisallowedVotesDuringBootstrap(),           // :606, bootstrap only
                        new GovChecks.VotingOnExpiredGovAction(),                 // :607
                        new GovChecks.DisallowedVoters())                         // :608
                // Babbage/Rules/Utxow.hs:328-391
                .scope(ConwayScopes.UTXOW,
                        new UtxowChecks.PrepareScripts(),
                        new UtxowChecks.ScriptWitnessNotValidating(),             // :349
                        new UtxowChecks.ExtraneousScriptWitnesses(),              // :354
                        new UtxowChecks.MissingScriptWitnesses(),                 // :354
                        new UtxowChecks.UnspendableUtxoNoDatumHash(),             // :357
                        new UtxowChecks.MissingRequiredDatums(),                  // :357
                        new UtxowChecks.NotAllowedSupplementalDatums(),           // :357
                        new UtxowChecks.ExtraRedeemers(),                         // :361
                        new UtxowChecks.MissingRedeemers(),                       // :361
                        new UtxowChecks.InvalidWitnesses(),                       // :366
                        new UtxowChecks.MissingVKeyWitnesses(),                   // :369
                        new UtxowChecks.MissingTxMetadata(),                      // :374
                        new UtxowChecks.MissingTxBodyMetadataHash(),              // :374
                        new UtxowChecks.ConflictingMetadataHash(),                // :374
                        new UtxowChecks.InvalidMetadata(),                        // :374
                        new UtxowChecks.MalformedScriptWitnesses(),               // :379
                        new UtxowChecks.MalformedReferenceScripts(),              // :379
                        new UtxowChecks.PpViewHashesDontMatch())                  // :387-389
                // Babbage/Rules/Utxo.hs:342-412
                .scope(ConwayScopes.UTXO,
                        new UtxoChecks.NonDisjointRefInputs(),                    // :356
                        new UtxoChecks.OutsideValidityInterval(),                 // :359
                        new UtxoChecks.OutsideForecast(),                         // :365
                        new UtxoChecks.InputSetEmpty(),                           // :368
                        new UtxoChecks.FeeTooSmall(),                             // :371 feesOK part 1
                        new UtxoChecks.ScriptsNotPaid(),                          // feesOK part 3
                        new UtxoChecks.CollateralContainsNonAda(),                // feesOK part 4
                        new UtxoChecks.InsufficientCollateral(),                  // feesOK part 5
                        new UtxoChecks.IncorrectTotalCollateralField(),           // feesOK part 6
                        new UtxoChecks.NoCollateralInputs(),                      // feesOK part 7
                        new UtxoChecks.BadInputs(),                               // :375
                        new UtxoChecks.ValueNotConserved(),                       // :378
                        new UtxoChecks.OutputTooSmall(),                          // :385
                        new UtxoChecks.OutputTooBig(),                            // :389
                        new UtxoChecks.OutputBootAddrAttrsTooBig(),               // :392
                        new UtxoChecks.WrongNetwork(),                            // :397
                        new UtxoChecks.WrongNetworkWithdrawal(),                  // :400
                        new UtxoChecks.WrongNetworkInTxBody(),                    // :403
                        new UtxoChecks.MaxTxSize(),                               // :406
                        new UtxoChecks.ExUnitsTooBig(),                           // :409
                        new UtxoChecks.TooManyCollateralInputs())                 // :412
                // Conway/Rules/Utxos.hs:207-242
                .scope(ConwayScopes.UTXOS,
                        new UtxosChecks.CollectErrors(),                          // Babbage/Rules/Utxos.hs:143, 206
                        new UtxosChecks.BootstrapPhasePlutusExecution())          // :145-157, 208-222
                .policy(ConwayPolicies.DREP_EXPIRY, new DRepExpiries.Bootstrap())
                .build();
    }
}
