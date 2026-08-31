package com.bloxbean.cardano.yano.scalusbridge

import scalus.cardano.ledger.{Transaction, TransactionException}
import scalus.cardano.ledger.rules.*

/** Runs Scalus's standard rule set with Yano's pool-deposit correction swapped in. */
object YanoCardanoMutator extends STS.Mutator:
  override final type Error = TransactionException

  private[scalusbridge] lazy val validators: Iterable[STS.Validator] =
    val defaults = CardanoMutator.defaultSTSs.values.collect {
      case validator: STS.Validator => validator
    }.toSeq.sortBy(_.name)
    require(
      defaults.exists(_ eq ValueNotConservedUTxOValidator),
      "Scalus default validator set no longer contains ValueNotConservedUTxOValidator"
    )
    defaults.map {
      case validator if validator eq ValueNotConservedUTxOValidator =>
        YanoValueNotConservedUTxOValidator
      case validator => validator
    }

  private[scalusbridge] lazy val mutators: Iterable[STS.Mutator] =
    CardanoMutator.defaultSTSs.values
      .collect { case mutator: STS.Mutator => mutator }
      .toSeq
      .sortBy(_.name)

  override def transit(context: Context, state: State, event: Transaction): Result =
    // TODO: Remove this override when the Scalus pool-update deposit bug described in
    // adr/reports/adr-050-scalus-pool-deposit-upstream-report.md is fixed upstream.
    STS.Mutator.transit[TransactionException](validators, mutators, context, state, event)
