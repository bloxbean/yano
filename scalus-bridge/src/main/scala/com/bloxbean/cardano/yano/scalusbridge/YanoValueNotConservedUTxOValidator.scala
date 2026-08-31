package com.bloxbean.cardano.yano.scalusbridge

import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.{Context, STS, State, ValueNotConservedUTxOValidator}
import scalus.cardano.ledger.utils.TxBalance

/** Corrects Scalus 0.18.2's unconditional pool-deposit charge for pool updates. */
object YanoValueNotConservedUTxOValidator extends STS.Validator:
  override final type Error = TransactionException.BadInputsUTxOException |
    TransactionException.ValueNotConservedUTxOException

  override def name: String = ValueNotConservedUTxOValidator.name

  override def validate(context: Context, state: State, tx: Transaction): Result =
    val params = context.env.params
    val certificates = tx.body.value.certificates.toSeq
    val excessPoolDeposit = excessPoolRegistrationDeposit(
      certificates,
      state.certState.pstate,
      params.stakePoolDeposit
    )

    for
      consumed <- TxBalance.consumed(tx, state.certState, state.utxos, params)
      produced = TxBalance.produced(tx, params) - Value(Coin(excessPoolDeposit))
      _ <-
        if consumed == produced then success
        else failure(TransactionException.ValueNotConservedUTxOException(tx.id, consumed, produced))
    yield ()

  private[scalusbridge] def excessPoolRegistrationDeposit(
      certificates: Iterable[Certificate],
      poolsState: PoolsState,
      poolDeposit: Long
  ): Long =
    val registrations = certificates.collect { case registration: Certificate.PoolRegistration =>
      registration.operator.asInstanceOf[PoolKeyHash]
    }.toSeq
    val distinctNewPools = registrations.toSet.count(poolId => !poolsState.stakePools.contains(poolId))
    val excessRegistrationCount = registrations.size - distinctNewPools
    Math.multiplyExact(excessRegistrationCount.toLong, poolDeposit)

