package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Transaction, TransactionException}
import scalus.cardano.ledger.rules.*

/**
 * Runs Scalus's standard rule set with Yano's corrections swapped in: the pool-deposit correction
 * ([[YanoValueNotConservedUTxOValidator]]), Haskell's transaction size ([[YanoTransactionSizeValidator]]) and
 * Haskell's output value size ([[YanoOutputValueSizeValidator]]).
 */
object YanoCardanoMutator extends STS.Mutator:
  override final type Error = TransactionException

  /** Scalus default validators and the Yano validator that replaces each. */
  private[scalusbridge] lazy val replacements: Map[STS.Validator, STS.Validator] = Map(
    ValueNotConservedUTxOValidator -> YanoValueNotConservedUTxOValidator,
    TransactionSizeValidator -> YanoTransactionSizeValidator,
    OutputsHaveTooBigValueStorageSizeValidator -> YanoOutputValueSizeValidator
  )

  private[scalusbridge] lazy val validators: Iterable[STS.Validator] =
    val defaults = CardanoMutator.defaultSTSs.values.collect {
      case validator: STS.Validator => validator
    }.toSeq.sortBy(_.name)
    replacements.keys.foreach { upstream =>
      val upstreamValidatorCount = defaults.count(_ eq upstream)
      require(
        upstreamValidatorCount == 1,
        s"Scalus default validator set must contain ${upstream.name} exactly once, found $upstreamValidatorCount"
      )
    }
    defaults.map(validator => replacements.find(_._1 eq validator).fold(validator)(_._2))

  private[scalusbridge] lazy val mutators: Iterable[STS.Mutator] =
    CardanoMutator.defaultSTSs.values
      .collect { case mutator: STS.Mutator => mutator }
      .toSeq
      .sortBy(_.name)

  /**
   * Haskell's `lblStatic` checks (ADR-056 §6 table), which re-application skips: vkey and bootstrap
   * signatures, metadata, malformed scripts, empty inputs, bootstrap address attributes, the three network
   * checks and the transaction size.
   */
  private[scalusbridge] lazy val staticValidators: Set[STS.Validator] = Set(
    VerifiedSignaturesInWitnessesValidator,
    MetadataValidator,
    ScriptsWellFormedValidator,
    EmptyInputsValidator,
    OutputBootAddrAttrsSizeValidator,
    WrongNetworkValidator,
    WrongNetworkInTxBodyValidator,
    WrongNetworkWithdrawalValidator,
    YanoTransactionSizeValidator
  )

  /** The validators re-application runs: every dynamic one. */
  private[scalusbridge] lazy val reapplyValidators: Iterable[STS.Validator] =
    validators.filterNot(v => staticValidators.exists(_ eq v))

  /**
   * The mutators re-application runs: Plutus execution (`when2Phase`) is static, so
   * [[PlutusScriptsTransactionMutator]] is left out. The resulting state is not used by the bridge (only the
   * verdict), so its UTxO bookkeeping is not needed.
   */
  private[scalusbridge] lazy val reapplyMutators: Iterable[STS.Mutator] =
    mutators.filterNot(_ eq PlutusScriptsTransactionMutator)

  override def transit(context: Context, state: State, event: Transaction): Result =
    // TODO: Remove this override when the Scalus pool-update deposit bug described in
    // adr/reports/adr-050-scalus-pool-deposit-upstream-report.md is fixed upstream.
    STS.Mutator.transit[TransactionException](validators, mutators, context, state, event)

  /** Re-application (ADR-056 §6): the dynamic checks only, no signatures, no Plutus. */
  def reapply(context: Context, state: State, event: Transaction): Result =
    STS.Mutator.transit[TransactionException](reapplyValidators, reapplyMutators, context, state, event)
