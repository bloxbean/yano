package org.yanoproject.scalusbridge

import org.yanoproject.ledger.rules.{LedgerFailure, LedgerRuleName}
import org.yanoproject.ledger.rules.LedgerFailure.Phase
import org.yanoproject.ledger.rules.LedgerRuleName.*
import scalus.cardano.ledger.TransactionException
import scalus.cardano.ledger.TransactionException.*
import scalus.cardano.ledger.TransactionException.MetadataException.*

/**
 * Maps a Scalus ledger failure to a typed [[LedgerFailure]] named after the Haskell rule and constructor
 * (ADR-056 §2) for the Scalus engine adapter.
 *
 * '''Phase.''' Only a Plutus evaluation outcome is phase 2: `PlutusScriptValidationException` (a script
 * failed while the transaction claims `is_valid = true`, Haskell `UTXOS.ValidationTagMismatch`
 * `FailedUnexpectedly`) and Scalus's "invalid flag passed script validation" (`PassedUnexpectedly`).
 * Everything else, including the script-related phase-1 checks the legacy label put in phase 2
 * (missing or extraneous scripts, native scripts, ill-formed scripts, the script integrity hash;
 * `ScalusBasedTransactionValidator`'s `className.contains("Script")`), is phase 1.
 *
 * '''Constructors.''' Scalus folds several Haskell constructors into one exception; the first applicable
 * constructor in Haskell's check order is reported, the full Scalus message goes into the detail. Anything
 * without a Haskell counterpart is `ENGINE.ScalusEngineFailure`.
 */
object ScalusFailureMapping:

  /** Engine constructor for Scalus failures that are not ledger predicates. */
  val SCALUS_ENGINE_FAILURE = "ScalusEngineFailure"

  private val PassedUnexpectedlyPrefix = "Transaction with invalid flag passed script validation"

  /**
   * @param error the `Left` of a Scalus transit, or an exception it threw
   * @param protocolMajor the protocol major version (PV 11 renamed some constructors)
   */
  def toLedgerFailure(error: Throwable, protocolMajor: Int): LedgerFailure =
    val detail = s"${error.getClass.getSimpleName}: ${error.getMessage}"
    def p1(rule: LedgerRuleName, constructor: String) = new LedgerFailure(rule, constructor, Phase.PHASE_1, detail)
    val pv11 = protocolMajor >= 11
    error match
      case _: EmptyInputsException => p1(UTXO, "InputSetEmptyUTxO")
      case _: NonDisjointInputsAndReferenceInputsException => p1(UTXO, "BabbageNonDisjointRefInputs")
      case _: BadAllInputsUTxOException | _: BadInputsUTxOException | _: BadCollateralInputsUTxOException |
          _: BadReferenceInputsUTxOException =>
        p1(UTXO, "BadInputsUTxO")
      case _: InvalidSignaturesInWitnessesException => p1(UTXOW, "InvalidWitnessesUTXOW")
      case _: MissingKeyHashesException => p1(UTXOW, "MissingVKeyWitnessesUTXOW")
      case e: MissingOrExtraScriptHashesException =>
        val missing = e.missingInputsScriptHashes.nonEmpty || e.missingMintScriptHashes.nonEmpty ||
          e.missingVotingProceduresScriptHashes.nonEmpty || e.missingWithdrawalsScriptHashes.nonEmpty ||
          e.missingProposalProceduresScriptHashes.nonEmpty || e.missingCertificatesScriptHashes.nonEmpty
        p1(UTXOW, if missing then "MissingScriptWitnessesUTXOW" else "ExtraneousScriptWitnessesUTXOW")
      case _: NativeScriptsException => p1(UTXOW, "ScriptWitnessNotValidatingUTXOW")
      case _: InvalidTransactionSizeException => p1(UTXO, "MaxTxSizeUTxO")
      case _: OutputsHaveNotEnoughCoinsException => p1(UTXO, "BabbageOutputTooSmallUTxO")
      case _: OutputsHaveTooBigValueStorageSizeException => p1(UTXO, "OutputTooBigUTxO")
      case _: OutsideValidityIntervalException => p1(UTXO, "OutsideValidityIntervalUTxO")
      case _: ValueNotConservedUTxOException => p1(UTXO, "ValueNotConservedUTxO")
      case e: FeesOkException =>
        val constructor =
          if e.isTransactionFeeLessThanMinRequiredFee then "FeeTooSmallUTxO"
          else if e.hasCollateralsConsistNotVKeyAddress then "ScriptsNotPaidUTxO"
          else if e.hasCollateralsContainNotOnlyADA then "CollateralContainsNonADA"
          else if e.isCollateralInsufficient then "InsufficientCollateral"
          else if e.isCollateralNotEqualToExpected then "IncorrectTotalCollateralField"
          else if e.areCollateralInputsMissing then "NoCollateralInputs"
          else "FeeTooSmallUTxO"
        p1(UTXO, constructor)
      case e: StakeCertificatesException =>
        val constructor =
          if e.alreadyRegistered.nonEmpty then "StakeKeyRegisteredDELEG"
          else if e.missingRegistrations.nonEmpty then "StakeKeyNotRegisteredDELEG"
          else if e.nonZeroRewardAccounts.nonEmpty then "StakeKeyHasNonZeroAccountBalanceDELEG"
          else if e.invalidDeposits.nonEmpty then (if pv11 then "DepositIncorrectDELEG" else "IncorrectDepositDELEG")
          else if e.invalidRefunds.nonEmpty then (if pv11 then "RefundIncorrectDELEG" else "IncorrectDepositDELEG")
          else "StakeKeyNotRegisteredDELEG"
        p1(DELEG, constructor)
      case e: StakePoolException =>
        val constructor =
          if e.notRegistered.nonEmpty then "StakePoolNotRegisteredOnKeyPOOL"
          else if e.rewardAccountNetworkMismatch.nonEmpty then "WrongNetworkPOOL"
          else if e.costBelowMinimum.nonEmpty then "StakePoolCostTooLowPOOL"
          else "StakePoolRetirementWrongEpochPOOL"
        p1(POOL, constructor)
      case _: WithdrawalsNotInRewardsException => p1(CERTS, "WithdrawalsNotInRewardsCERTS")
      case _: ExUnitsExceedMaxException => p1(UTXO, "ExUnitsTooBigUTxO")
      case _: TooManyCollateralInputsException => p1(UTXO, "TooManyCollateralInputs")
      case _: InvalidScriptDataHashException =>
        p1(UTXOW, if pv11 then "ScriptIntegrityHashMismatch" else "PPViewHashesDontMatch")
      case e: IllFormedScriptsException =>
        p1(UTXOW, if e.invalidWitnessesScripts.nonEmpty then "MalformedScriptWitnesses"
                  else "MalformedReferenceScripts")
      case _: WrongNetworkAddress => p1(UTXO, "WrongNetwork")
      case _: WrongNetworkWithdrawal => p1(UTXO, "WrongNetworkWithdrawal")
      case _: WrongNetworkInTxBody => p1(UTXO, "WrongNetworkInTxBody")
      case _: MissingAuxiliaryDataException => p1(UTXOW, "MissingTxMetadata")
      case _: MissingAuxiliaryDataHashException => p1(UTXOW, "MissingTxBodyMetadataHash")
      case _: InvalidAuxiliaryDataHashException => p1(UTXOW, "ConflictingMetadataHash")
      case _: InvalidAuxiliaryDataException => p1(UTXOW, "InvalidMetadata")
      case e: ExactSetOfRedeemersException =>
        p1(UTXOW, if e.extraRedeemers.nonEmpty then "ExtraRedeemers" else "MissingRedeemers")
      case e: DatumsException =>
        val constructor =
          if e.inputsWithMissingDatumHashes.nonEmpty then "UnspendableUTxONoDatumHash"
          else if e.unmatchedDatumHashes.nonEmpty then "MissingRequiredDatums"
          else "NotAllowedSupplementalDatums"
        p1(UTXOW, constructor)
      case _: OutputBootAddrAttrsTooBigException => p1(UTXO, "OutputBootAddrAttrsTooBig")
      case _: PlutusScriptValidationException =>
        new LedgerFailure(UTXOS, "ValidationTagMismatch", Phase.PHASE_2, "FailedUnexpectedly: " + detail)
      case e: TransactionException.IllegalArgumentException
          if e.getMessage != null && e.getMessage.startsWith(PassedUnexpectedlyPrefix) =>
        new LedgerFailure(UTXOS, "ValidationTagMismatch", Phase.PHASE_2, "PassedUnexpectedly: " + detail)
      case _ => p1(ENGINE, SCALUS_ENGINE_FAILURE)
