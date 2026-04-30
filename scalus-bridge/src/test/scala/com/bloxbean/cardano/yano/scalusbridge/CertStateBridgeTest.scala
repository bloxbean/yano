package com.bloxbean.cardano.yano.scalusbridge

import com.bloxbean.cardano.client.address.Credential
import com.bloxbean.cardano.client.spec.Era
import com.bloxbean.cardano.client.transaction.spec.{Transaction as CclTransaction, TransactionBody, TransactionInput, TransactionWitnessSet}
import com.bloxbean.cardano.client.transaction.spec.cert.{Certificate as CclCertificate, RegCert, RegDRepCert, StakeCredential, StakeDelegation, StakePoolId}
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider.DRepDelegation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scalus.cardano.ledger.{Transaction as ScalusTransaction}

import java.math.BigInteger
import java.util.Optional

class CertStateBridgeTest:

  private val stakeHash: Array[Byte] = Array.fill[Byte](28)(1)
  private val drepHash: Array[Byte] = Array.fill[Byte](28)(2)
  private val poolHash: String = "03" * 28

  @Test
  def totalDepositedOverflowRejectsInsteadOfZeroing(): Unit =
    val provider = new TestProvider(totalDeposited = BigInteger.valueOf(Long.MaxValue).add(BigInteger.ONE))

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegCert.builder()
        .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
        .coin(BigInteger.valueOf(2_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("total deposited"))
    assertTrue(ex.getMessage.contains("does not fit"))

  @Test
  def nullTotalDepositedRejectsInsteadOfZeroing(): Unit =
    val provider = new TestProvider(totalDeposited = null)

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegCert.builder()
        .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
        .coin(BigInteger.valueOf(2_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("total deposited"))
    assertTrue(ex.getMessage.contains("null"))

  @Test
  def nullProviderKeepsLegacyEmptyCertStateForFixedParameterValidation(): Unit =
    assertDoesNotThrow(() => CertStateBridge.build(null, scalusTx(RegCert.builder()
      .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
      .coin(BigInteger.valueOf(2_000_000))
      .build())))

  @Test
  def negativeRewardBalanceRejectsInsteadOfProjectingInvalidCoin(): Unit =
    val provider = new TestProvider(rewardBalance = Optional.of(BigInteger.valueOf(-1)))

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegCert.builder()
        .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
        .coin(BigInteger.valueOf(2_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("reward balance"))
    assertTrue(ex.getMessage.contains("non-negative"))

  @Test
  def registeredStakeCredentialWithoutDepositRejectsInsteadOfProjectingUnregistered(): Unit =
    val provider = new TestProvider(stakeRegistered = true)

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegCert.builder()
        .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
        .coin(BigInteger.valueOf(2_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("Missing stake deposit"))

  @Test
  def registeredDRepWithoutDepositRejectsInsteadOfUsingZero(): Unit =
    val provider = new TestProvider(drepRegistered = true)

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegDRepCert.builder()
        .drepCredential(Credential.fromKey(drepHash))
        .coin(BigInteger.valueOf(500_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("Missing DRep deposit"))

  @Test
  def unknownDRepDelegationTypeRejectsInsteadOfFallingBackToAbstain(): Unit =
    val provider = new TestProvider(
      drepDelegation = Optional.of(new DRepDelegation(99, null)))

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(RegCert.builder()
        .stakeCredential(StakeCredential.fromKeyHash(stakeHash))
        .coin(BigInteger.valueOf(2_000_000))
        .build())))

    assertTrue(ex.getMessage.contains("Unsupported DRep delegation type"))

  @Test
  def negativePoolRetirementEpochRejectsInsteadOfProjectingInvalidEpoch(): Unit =
    val provider = new TestProvider(
      poolRegistered = true,
      poolDeposit = Optional.of(BigInteger.valueOf(500_000_000)),
      poolRetirementEpoch = Optional.of(java.lang.Long.valueOf(-1)))

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(new StakeDelegation(
        StakeCredential.fromKeyHash(stakeHash),
        StakePoolId.fromHexPoolId(poolHash)))))

    assertTrue(ex.getMessage.contains("pool retirement epoch"))
    assertTrue(ex.getMessage.contains("non-negative"))

  @Test
  def registeredPoolWithoutDepositRejectsInsteadOfProjectingIncompletePoolState(): Unit =
    val provider = new TestProvider(poolRegistered = true)

    val ex = assertThrows(classOf[IllegalStateException],
      () => CertStateBridge.build(provider, scalusTx(new StakeDelegation(
        StakeCredential.fromKeyHash(stakeHash),
        StakePoolId.fromHexPoolId(poolHash)))))

    assertTrue(ex.getMessage.contains("Missing pool deposit"))

  private def scalusTx(cert: CclCertificate): ScalusTransaction =
    val input = TransactionInput.builder()
      .transactionId("aa" * 32)
      .index(0)
      .build()
    val tx = CclTransaction.builder()
      .era(Era.Conway)
      .body(TransactionBody.builder()
        .inputs(java.util.List.of(input))
        .certs(java.util.List.of(cert))
        .fee(BigInteger.ZERO)
        .build())
      .witnessSet(new TransactionWitnessSet())
      .isValid(true)
      .build()
    ScalusTransaction.fromCbor(tx.serialize())

  private class TestProvider(
      rewardBalance: Optional[BigInteger] = Optional.empty(),
      stakeDeposit: Optional[BigInteger] = Optional.empty(),
      stakeRegistered: Boolean = false,
      delegatedPool: Optional[String] = Optional.empty(),
      drepDelegation: Optional[DRepDelegation] = Optional.empty(),
      poolRegistered: Boolean = false,
      poolDeposit: Optional[BigInteger] = Optional.empty(),
      poolRetirementEpoch: Optional[java.lang.Long] = Optional.empty(),
      drepRegistered: Boolean = false,
      drepDeposit: Optional[BigInteger] = Optional.empty(),
      totalDeposited: BigInteger = BigInteger.ZERO
  ) extends LedgerStateProvider:

    override def getRewardBalance(credType: Int, credentialHash: String): Optional[BigInteger] =
      rewardBalance

    override def getStakeDeposit(credType: Int, credentialHash: String): Optional[BigInteger] =
      stakeDeposit

    override def getDelegatedPool(credType: Int, credentialHash: String): Optional[String] =
      delegatedPool

    override def getDRepDelegation(credType: Int, credentialHash: String): Optional[DRepDelegation] =
      drepDelegation

    override def isStakeCredentialRegistered(credType: Int, credentialHash: String): Boolean =
      stakeRegistered

    override def getTotalDeposited(): BigInteger = totalDeposited

    override def isPoolRegistered(poolHash: String): Boolean = poolRegistered

    override def getPoolDeposit(poolHash: String): Optional[BigInteger] = poolDeposit

    override def getPoolRetirementEpoch(poolHash: String): Optional[java.lang.Long] =
      poolRetirementEpoch

    override def isDRepRegistered(credType: Int, credentialHash: String): Boolean = drepRegistered

    override def getDRepDeposit(credType: Int, credentialHash: String): Optional[BigInteger] =
      drepDeposit
