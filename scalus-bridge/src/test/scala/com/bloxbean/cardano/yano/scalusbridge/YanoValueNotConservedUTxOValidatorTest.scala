package com.bloxbean.cardano.yano.scalusbridge

import org.junit.jupiter.api.Assertions.{assertEquals, assertSame, assertTrue}
import org.junit.jupiter.api.Test
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.{CardanoMutator, STS, ValueNotConservedUTxOValidator}
import scalus.uplc.builtin.ByteString

class YanoValueNotConservedUTxOValidatorTest:
  private val poolDeposit = 500_000_000L
  private val poolA = "01" * 28
  private val poolB = "02" * 28

  @Test
  def swapsExactlyOneScalusDefaultValidator(): Unit =
    val defaultValidators = CardanoMutator.defaultSTSs.values.collect {
      case validator: STS.Validator => validator
    }.toSet
    val actualValidators = YanoCardanoMutator.validators.toSet

    assertEquals(Set(ValueNotConservedUTxOValidator), defaultValidators.diff(actualValidators))
    assertEquals(Set(YanoValueNotConservedUTxOValidator), actualValidators.diff(defaultValidators))
    assertEquals(defaultValidators.size, actualValidators.size)
    assertEquals(defaultValidators.toSeq.map(_.name).sorted, YanoCardanoMutator.validators.map(_.name))
    assertEquals(
      CardanoMutator.defaultSTSs.values.count(_.isInstanceOf[STS.Mutator]),
      YanoCardanoMutator.mutators.size
    )
    assertEquals(
      CardanoMutator.defaultSTSs.values.collect { case mutator: STS.Mutator => mutator.name }.toSeq.sorted,
      YanoCardanoMutator.mutators.map(_.name)
    )
    assertSame(
      YanoValueNotConservedUTxOValidator,
      actualValidators.find(_.name == ValueNotConservedUTxOValidator.name).orNull
    )

  @Test
  def activePoolUpdateRemovesTheUnconditionalDeposit(): Unit =
    assertEquals(poolDeposit, excess(List(registration(poolA)), pools(poolA)))

  @Test
  def newPoolRegistrationKeepsOneDeposit(): Unit =
    assertEquals(0L, excess(List(registration(poolA)), pools()))

  @Test
  def duplicateNewPoolRegistrationKeepsOnlyOneDeposit(): Unit =
    assertEquals(poolDeposit, excess(List(registration(poolA), registration(poolA)), pools()))

  @Test
  def mixedActiveAndNewPoolKeepsOnlyTheNewPoolDeposit(): Unit =
    assertEquals(poolDeposit, excess(List(registration(poolA), registration(poolB)), pools(poolA)))

  @Test
  def retirementOrderDoesNotChangeTransactionStartPoolMembership(): Unit =
    val retirement = Certificate.PoolRetirement(PoolKeyHash.fromHex(poolA), 12L)

    assertEquals(poolDeposit, excess(List(retirement, registration(poolA)), pools(poolA)))
    assertEquals(poolDeposit, excess(List(registration(poolA), retirement), pools(poolA)))

  @Test
  def overrideKeepsTheSameRuleNameForStableDiagnostics(): Unit =
    assertEquals(ValueNotConservedUTxOValidator.name, YanoValueNotConservedUTxOValidator.name)
    assertTrue(YanoCardanoMutator.validators.exists(_ eq YanoValueNotConservedUTxOValidator))

  private def excess(certificates: Iterable[Certificate], state: PoolsState): Long =
    YanoValueNotConservedUTxOValidator.excessPoolRegistrationDeposit(
      certificates, state, poolDeposit)

  private def pools(activePoolIds: String*): PoolsState =
    val active = activePoolIds.map { poolId =>
      PoolKeyHash.fromHex(poolId) -> registration(poolId)
    }.toMap
    PoolsState(active, Map.empty, Map.empty, Map.empty)

  private def registration(poolId: String): Certificate.PoolRegistration =
    Certificate.PoolRegistration(
      operator = AddrKeyHash.fromHex(poolId),
      vrfKeyHash = ByteString.empty.asInstanceOf[VrfKeyHash],
      pledge = Coin.zero,
      cost = Coin.zero,
      margin = UnitInterval(0, 1),
      rewardAccount = null.asInstanceOf[RewardAccount],
      poolOwners = Set.empty,
      relays = IndexedSeq.empty,
      poolMetadata = None
    )
