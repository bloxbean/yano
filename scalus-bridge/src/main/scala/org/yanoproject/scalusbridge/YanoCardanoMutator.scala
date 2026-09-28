package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Transaction, TransactionException}
import scalus.cardano.ledger.rules.*

/**
 * Runs Scalus's standard rule set with Yano's corrections swapped in: the pool-deposit correction
 * ([[YanoValueNotConservedUTxOValidator]]) and Haskell's transaction size ([[YanoTransactionSizeValidator]]).
 */
object YanoCardanoMutator extends STS.Mutator:
  override final type Error = TransactionException

  /** Scalus default validators and the Yano validator that replaces each. */
  private[scalusbridge] lazy val replacements: Map[STS.Validator, STS.Validator] = Map(
    ValueNotConservedUTxOValidator -> YanoValueNotConservedUTxOValidator,
    TransactionSizeValidator -> YanoTransactionSizeValidator
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

  override def transit(context: Context, state: State, event: Transaction): Result =
    // TODO: Remove this override when the Scalus pool-update deposit bug described in
    // adr/reports/adr-050-scalus-pool-deposit-upstream-report.md is fixed upstream.
    STS.Mutator.transit[TransactionException](validators, mutators, context, state, event)
