package com.bloxbean.cardano.yano.scalusbridge

import com.bloxbean.cardano.client.api.model.ProtocolParams as CclProtocolParams
import scalus.bloxbean.Interop
import scalus.cardano.ledger.*

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Converts CCL ProtocolParams to Scalus ProtocolParams.
 * All Scala types are internal — Java code never sees them.
 */
private[scalusbridge] object ProtocolParamsBridge:

  def toScalusProtocolParams(pp: CclProtocolParams): scalus.cardano.ledger.ProtocolParams =
    val protocolMajor = validateMandatoryProtocolParams(pp)
    val costModels = convertCostModels(pp)

    val exUnitPrices = ExUnitPrices(
      toNonNegativeInterval(pp.getPriceMem, "priceMem"),
      toNonNegativeInterval(pp.getPriceStep, "priceStep")
    )

    val maxBlockExUnits = ExUnits(
      parseLong(pp.getMaxBlockExSteps),
      parseLong(pp.getMaxBlockExMem)
    )

    val maxTxExUnits = ExUnits(
      parseLong(pp.getMaxTxExSteps),
      parseLong(pp.getMaxTxExMem)
    )

    val dRepVotingThresholds = DRepVotingThresholds(
      toUnitInterval(pp.getDvtMotionNoConfidence, "dvtMotionNoConfidence"),
      toUnitInterval(pp.getDvtCommitteeNormal, "dvtCommitteeNormal"),
      toUnitInterval(pp.getDvtCommitteeNoConfidence, "dvtCommitteeNoConfidence"),
      toUnitInterval(pp.getDvtUpdateToConstitution, "dvtUpdateToConstitution"),
      toUnitInterval(pp.getDvtHardForkInitiation, "dvtHardForkInitiation"),
      toUnitInterval(pp.getDvtPPNetworkGroup, "dvtPPNetworkGroup"),
      toUnitInterval(pp.getDvtPPEconomicGroup, "dvtPPEconomicGroup"),
      toUnitInterval(pp.getDvtPPTechnicalGroup, "dvtPPTechnicalGroup"),
      toUnitInterval(pp.getDvtPPGovGroup, "dvtPPGovGroup"),
      toUnitInterval(pp.getDvtTreasuryWithdrawal, "dvtTreasuryWithdrawal")
    )

    val poolVotingThresholds = PoolVotingThresholds(
      toUnitInterval(pp.getPvtMotionNoConfidence, "pvtMotionNoConfidence"),
      toUnitInterval(pp.getPvtCommitteeNormal, "pvtCommitteeNormal"),
      toUnitInterval(pp.getPvtCommitteeNoConfidence, "pvtCommitteeNoConfidence"),
      toUnitInterval(pp.getPvtHardForkInitiation, "pvtHardForkInitiation"),
      toUnitInterval(pp.getPvtPPSecurityGroup, "pvtPPSecurityGroup")
    )

    val protocolVersion = ProtocolVersion(
      intOrDefault(pp.getProtocolMajorVer, 10),
      intOrDefault(pp.getProtocolMinorVer, 0)
    )

    scalus.cardano.ledger.ProtocolParams(
      /* 1  collateralPercentage    */ toLong(pp.getCollateralPercent),
      /* 2  committeeMaxTermLength   */ intOrDefault(pp.getCommitteeMaxTermLength, 146).toLong,
      /* 3  committeeMinSize         */ intOrDefault(pp.getCommitteeMinSize, 7).toLong,
      /* 4  costModels               */ costModels,
      /* 5  dRepActivity             */ intOrDefault(pp.getDrepActivity, 20).toLong,
      /* 6  dRepDeposit              */ if pp.getDrepDeposit != null then pp.getDrepDeposit.longValue() else 500000000L,
      /* 7  dRepVotingThresholds     */ dRepVotingThresholds,
      /* 8  executionUnitPrices      */ exUnitPrices,
      /* 9  govActionDeposit         */ if pp.getGovActionDeposit != null then pp.getGovActionDeposit.longValue() else 100000000000L,
      /* 10 govActionLifetime        */ intOrDefault(pp.getGovActionLifetime, 6).toLong,
      /* 11 maxBlockBodySize         */ intOrDefault(pp.getMaxBlockSize, 90112).toLong,
      /* 12 maxBlockExecutionUnits   */ maxBlockExUnits,
      /* 13 maxBlockHeaderSize       */ intOrDefault(pp.getMaxBlockHeaderSize, 1100).toLong,
      /* 14 maxCollateralInputs      */ intOrDefault(pp.getMaxCollateralInputs, 3).toLong,
      /* 15 maxTxExecutionUnits      */ maxTxExUnits,
      /* 16 maxTxSize                */ intOrDefault(pp.getMaxTxSize, 16384).toLong,
      /* 17 maxValueSize             */ parseLong(pp.getMaxValSize),
      /* 18 minFeeRefScriptCostPerByte */ if pp.getMinFeeRefScriptCostPerByte != null then toLongExact(pp.getMinFeeRefScriptCostPerByte, "minFeeRefScriptCostPerByte") else 15L,
      /* 19 minPoolCost              */ parseLong(pp.getMinPoolCost),
      /* 20 monetaryExpansion        */ toDouble(pp.getRho),
      /* 21 poolPledgeInfluence      */ toDouble(pp.getA0),
      /* 22 poolRetireMaxEpoch       */ intOrDefault(pp.getEMax, 18).toLong,
      /* 23 poolVotingThresholds     */ poolVotingThresholds,
      /* 24 protocolVersion          */ protocolVersion,
      /* 25 stakeAddressDeposit      */ parseLong(pp.getKeyDeposit),
      /* 26 stakePoolDeposit         */ parseLong(pp.getPoolDeposit),
      /* 27 stakePoolTargetNum       */ intOrDefault(pp.getNOpt, 500).toLong,
      /* 28 treasuryCut              */ toDouble(pp.getTau),
      /* 29 txFeeFixed               */ intOrDefault(pp.getMinFeeB, 155381).toLong,
      /* 30 txFeePerByte             */ intOrDefault(pp.getMinFeeA, 44).toLong,
      /* 31 utxoCostPerByte          */ utxoCostPerByte(pp, protocolMajor)
    )

  private def convertCostModels(pp: CclProtocolParams): CostModels =
    if pp.getCostModels == null || pp.getCostModels.isEmpty then
      CostModels(scala.collection.immutable.Map.empty)
    else
      Interop.getCostModels(pp)

  private def toUnitInterval(value: BigDecimal, field: String): UnitInterval =
    if value == null then UnitInterval.zero
    else
      val (numerator, denominator) = toReducedRational(value, field)
      if numerator > denominator then
        throw new IllegalArgumentException(s"Protocol parameter $field must be between 0 and 1")
      UnitInterval(numerator, denominator)

  private def toNonNegativeInterval(value: BigDecimal, field: String): NonNegativeInterval =
    if value == null then NonNegativeInterval.zero
    else
      val (numerator, denominator) = toReducedRational(value, field)
      NonNegativeInterval(numerator, denominator)

  private def toReducedRational(value: BigDecimal, field: String): (Long, Long) =
    val normalized = value.stripTrailingZeros()
    val scale = normalized.scale()
    val unscaled = normalized.unscaledValue()
    val numerator =
      if scale < 0 then unscaled.multiply(BigInteger.TEN.pow(-scale))
      else unscaled
    val denominator =
      if scale < 0 then BigInteger.ONE
      else BigInteger.TEN.pow(scale)

    if numerator.signum() < 0 then
      throw new IllegalArgumentException(s"Protocol parameter $field must be non-negative")

    val gcd = numerator.gcd(denominator)
    val reducedNumerator = numerator.divide(gcd)
    val reducedDenominator = denominator.divide(gcd)
    (toLongExact(reducedNumerator, field), toLongExact(reducedDenominator, field))

  private def toDouble(value: BigDecimal): Double =
    if value != null then value.doubleValue() else 0.0

  private def toLong(value: BigDecimal): Long =
    if value != null then value.longValue() else 0L

  private def toLongExact(value: BigDecimal, field: String): Long =
    try value.longValueExact()
    catch
      case _: ArithmeticException =>
        throw new IllegalArgumentException(s"Protocol parameter $field must be an integral 64-bit value")

  private def intOrDefault(value: Integer, default: Int): Int =
    if value != null then value.intValue() else default

  private def parseLong(value: String): Long =
    if value == null || value.isEmpty then 0L
    else java.lang.Long.parseLong(value)

  private def parseLongRequired(value: String, field: String): Long =
    requireString(value, field)
    java.lang.Long.parseLong(value)

  private def toLongExact(value: BigInteger, field: String): Long =
    try value.longValueExact()
    catch
      case _: ArithmeticException =>
        throw new IllegalArgumentException(s"Protocol parameter $field does not fit in signed 64-bit integer")

  private def validateMandatoryProtocolParams(pp: CclProtocolParams): Int =
    if pp == null then throw new IllegalArgumentException("Protocol parameters are required")

    val protocolMajor = requireInt(pp.getProtocolMajorVer, "protocolMajorVer")
    requireInt(pp.getProtocolMinorVer, "protocolMinorVer")

    requireInt(pp.getMinFeeA, "minFeeA")
    requireInt(pp.getMinFeeB, "minFeeB")
    requireInt(pp.getMaxBlockSize, "maxBlockSize")
    requireInt(pp.getMaxTxSize, "maxTxSize")
    requireInt(pp.getMaxBlockHeaderSize, "maxBlockHeaderSize")
    requireString(pp.getKeyDeposit, "keyDeposit")
    requireString(pp.getPoolDeposit, "poolDeposit")
    requireInt(pp.getEMax, "eMax")
    requireInt(pp.getNOpt, "nOpt")
    requireBigDecimal(pp.getA0, "a0")
    requireBigDecimal(pp.getRho, "rho")
    requireBigDecimal(pp.getTau, "tau")
    requireString(pp.getMinPoolCost, "minPoolCost")

    if protocolMajor >= 5 then
      requireString(pp.getMaxValSize, "maxValSize")
      requireBigDecimal(pp.getPriceMem, "priceMem")
      requireBigDecimal(pp.getPriceStep, "priceStep")
      requireString(pp.getMaxTxExMem, "maxTxExMem")
      requireString(pp.getMaxTxExSteps, "maxTxExSteps")
      requireString(pp.getMaxBlockExMem, "maxBlockExMem")
      requireString(pp.getMaxBlockExSteps, "maxBlockExSteps")
      requireBigDecimal(pp.getCollateralPercent, "collateralPercent")
      requireInt(pp.getMaxCollateralInputs, "maxCollateralInputs")
      requireUtxoCost(pp, protocolMajor)
      if pp.getCostModels == null || pp.getCostModels.isEmpty then
        throw new IllegalArgumentException("Protocol parameter costModels is required for Alonzo or later")

    if protocolMajor >= 9 then
      requireBigInteger(pp.getGovActionDeposit, "govActionDeposit")
      requireInt(pp.getGovActionLifetime, "govActionLifetime")
      requireBigInteger(pp.getDrepDeposit, "drepDeposit")
      requireInt(pp.getDrepActivity, "drepActivity")
      requireInt(pp.getCommitteeMinSize, "committeeMinSize")
      requireInt(pp.getCommitteeMaxTermLength, "committeeMaxTermLength")
      requireBigDecimal(pp.getMinFeeRefScriptCostPerByte, "minFeeRefScriptCostPerByte")
      requireDRepVotingThresholds(pp)
      requirePoolVotingThresholds(pp)

    protocolMajor

  private def requireDRepVotingThresholds(pp: CclProtocolParams): Unit =
    requireBigDecimal(pp.getDvtMotionNoConfidence, "dvtMotionNoConfidence")
    requireBigDecimal(pp.getDvtCommitteeNormal, "dvtCommitteeNormal")
    requireBigDecimal(pp.getDvtCommitteeNoConfidence, "dvtCommitteeNoConfidence")
    requireBigDecimal(pp.getDvtUpdateToConstitution, "dvtUpdateToConstitution")
    requireBigDecimal(pp.getDvtHardForkInitiation, "dvtHardForkInitiation")
    requireBigDecimal(pp.getDvtPPNetworkGroup, "dvtPPNetworkGroup")
    requireBigDecimal(pp.getDvtPPEconomicGroup, "dvtPPEconomicGroup")
    requireBigDecimal(pp.getDvtPPTechnicalGroup, "dvtPPTechnicalGroup")
    requireBigDecimal(pp.getDvtPPGovGroup, "dvtPPGovGroup")
    requireBigDecimal(pp.getDvtTreasuryWithdrawal, "dvtTreasuryWithdrawal")

  private def requirePoolVotingThresholds(pp: CclProtocolParams): Unit =
    requireBigDecimal(pp.getPvtMotionNoConfidence, "pvtMotionNoConfidence")
    requireBigDecimal(pp.getPvtCommitteeNormal, "pvtCommitteeNormal")
    requireBigDecimal(pp.getPvtCommitteeNoConfidence, "pvtCommitteeNoConfidence")
    requireBigDecimal(pp.getPvtHardForkInitiation, "pvtHardForkInitiation")
    requireBigDecimal(pp.getPvtPPSecurityGroup, "pvtPPSecurityGroup")

  private def requireUtxoCost(pp: CclProtocolParams, protocolMajor: Int): Unit =
    if protocolMajor >= 7 then requireString(pp.getCoinsPerUtxoSize, "coinsPerUtxoSize")
    else if isBlank(pp.getCoinsPerUtxoSize) && isBlank(pp.getCoinsPerUtxoWord) then
      throw new IllegalArgumentException("Protocol parameter coinsPerUtxoSize or coinsPerUtxoWord is required")

  private def utxoCostPerByte(pp: CclProtocolParams, protocolMajor: Int): Long =
    if !isBlank(pp.getCoinsPerUtxoSize) then parseLongRequired(pp.getCoinsPerUtxoSize, "coinsPerUtxoSize")
    else if protocolMajor < 7 && !isBlank(pp.getCoinsPerUtxoWord) then
      parseLongRequired(pp.getCoinsPerUtxoWord, "coinsPerUtxoWord") / 8
    else 0L

  private def requireInt(value: Integer, field: String): Int =
    if value == null then throw new IllegalArgumentException(s"Protocol parameter $field is required")
    value.intValue()

  private def requireBigDecimal(value: BigDecimal, field: String): BigDecimal =
    if value == null then throw new IllegalArgumentException(s"Protocol parameter $field is required")
    value

  private def requireBigInteger(value: BigInteger, field: String): BigInteger =
    if value == null then throw new IllegalArgumentException(s"Protocol parameter $field is required")
    value

  private def requireString(value: String, field: String): String =
    if isBlank(value) then throw new IllegalArgumentException(s"Protocol parameter $field is required")
    value

  private def isBlank(value: String): Boolean =
    value == null || value.isBlank

  private[scalusbridge] def extractProtocolVersion(pp: CclProtocolParams): ProtocolVersion =
    ProtocolVersion(
      intOrDefault(pp.getProtocolMajorVer, 10),
      intOrDefault(pp.getProtocolMinorVer, 0)
    )

  private[scalusbridge] def toNetwork(networkId: Int): scalus.cardano.address.Network =
    if networkId == 1 then scalus.cardano.address.Network.Mainnet
    else scalus.cardano.address.Network.Testnet
