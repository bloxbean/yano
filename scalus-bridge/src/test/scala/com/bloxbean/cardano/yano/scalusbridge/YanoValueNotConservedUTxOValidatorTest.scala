package com.bloxbean.cardano.yano.scalusbridge

import org.junit.jupiter.api.Assertions.{assertEquals, assertSame, assertTrue}
import org.junit.jupiter.api.Test
import scalus.cardano.address.{Address, Network, StakeAddress, StakePayload}
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.{CardanoMutator, Context, STS, State, ValueNotConservedUTxOValidator}

class YanoValueNotConservedUTxOValidatorTest:
  private val poolDeposit = 500_000_000L
  private val poolA = "01" * 28
  private val poolB = "02" * 28

  @Test
  def swapsExactlyOneScalusDefaultValidator(): Unit =
    val defaultValidators = CardanoMutator.defaultSTSs.values.collect {
      case validator: STS.Validator => validator
    }.toSeq
    val actualValidators = YanoCardanoMutator.validators.toSeq

    assertEquals(1, defaultValidators.count(_ eq ValueNotConservedUTxOValidator))
    assertEquals(0, actualValidators.count(_ eq ValueNotConservedUTxOValidator))
    assertEquals(1, actualValidators.count(_ eq YanoValueNotConservedUTxOValidator))

    assertEquals(Set(ValueNotConservedUTxOValidator), defaultValidators.toSet.diff(actualValidators.toSet))
    assertEquals(Set(YanoValueNotConservedUTxOValidator), actualValidators.toSet.diff(defaultValidators.toSet))
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

  @Test
  def completeValidationAcceptsBalancedActivePoolUpdate(): Unit =
    val (context, state, tx) = validationFixture(Value.ada(1_000))

    assertEquals(poolDeposit, context.env.params.stakePoolDeposit)
    assertTrue(ValueNotConservedUTxOValidator.validate(context, state, tx).isLeft)
    assertEquals(Right(()), YanoValueNotConservedUTxOValidator.validate(context, state, tx))

  @Test
  def completeValidationRejectsGenuinelyUnbalancedActivePoolUpdate(): Unit =
    val (context, state, tx) = validationFixture(Value.lovelace(Coin.ada(1_000).value - 1))
    val result = YanoValueNotConservedUTxOValidator.validate(context, state, tx)

    assertTrue(
      result.left.exists(_.isInstanceOf[TransactionException.ValueNotConservedUTxOException])
    )

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
      vrfKeyHash = VrfKeyHash.fromHex("03" * 32),
      pledge = Coin.zero,
      cost = Coin.zero,
      margin = UnitInterval(0, 1),
      rewardAccount = RewardAccount(
        StakeAddress(Network.Mainnet, StakePayload.Stake(StakeKeyHash.fromHex("04" * 28)))
      ),
      poolOwners = Set.empty,
      relays = IndexedSeq.empty,
      poolMetadata = None
    )

  private def validationFixture(outputValue: Value): (Context, State, Transaction) =
    val input = TransactionInput(TransactionHash.fromHex("05" * 32), 0)
    val inputValue = Value.ada(1_000)
    val address = Address(Network.Mainnet, Credential.KeyHash(AddrKeyHash.fromHex("06" * 28)))
    val certState = CertState(pstate = pools(poolA))
    val tx = Transaction(
      TransactionBody(
        inputs = TaggedSortedSet(input),
        outputs = IndexedSeq(Sized(TransactionOutput(address, outputValue))),
        fee = Coin.zero,
        certificates = TaggedOrderedStrictSet(registration(poolA))
      )
    )
    val state = State(
      utxos = Map(input -> TransactionOutput(address, inputValue)),
      certState = certState
    )

    (Context.testMainnet(), state, tx)
