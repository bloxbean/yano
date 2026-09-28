package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.api.model.{ProtocolParams as CclProtocolParams, Utxo}
import org.yanoproject.api.account.LedgerStateProvider
import org.yanoproject.ledger.rules.LedgerFailure
import org.yanoproject.ledger.rules.view.model.UtxoEntry
import scalus.bloxbean.ScriptSupplier
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.rules.*

import java.util

/**
 * Java-friendly facade for Scalus ledger validation (CardanoMutator.transit).
 * Accepts only Java/CCL types, returns TransitResult.
 *
 * Note: Script evaluation (ExUnits computation) is handled directly by
 * ScalusBasedTransactionEvaluator using scalus.bloxbean.ScalusTransactionEvaluator.
 */
object LedgerBridge:

  /**
   * Validate a transaction against full Cardano ledger rules (without script supplier).
   */
  def validate(
      txCbor: Array[Byte],
      protocolParams: CclProtocolParams,
      inputUtxos: util.Set[Utxo],
      currentSlot: Long,
      slotConfig: SlotConfig,
      networkId: Int
  ): TransitResult =
    validate(txCbor, protocolParams, inputUtxos, currentSlot, slotConfig, networkId, null)

  /**
   * Validate a transaction against full Cardano ledger rules.
   *
   * @param txCbor           serialized transaction CBOR bytes
   * @param protocolParams   CCL ProtocolParams
   * @param inputUtxos       set of resolved input UTxOs (CCL Utxo)
   * @param currentSlot      current slot (e.g. from validity start interval)
   * @param slotConfig       opaque SlotConfigHandle from SlotConfigBridge
   * @param networkId        0 = testnet, 1 = mainnet
   * @param scriptSupplier   nullable ScriptSupplier for reference script resolution
   * @return TransitResult with success/failure and error details
   */
  def validate(
      txCbor: Array[Byte],
      protocolParams: CclProtocolParams,
      inputUtxos: util.Set[Utxo],
      currentSlot: Long,
      slotConfig: SlotConfig,
      networkId: Int,
      scriptSupplier: ScriptSupplier
  ): TransitResult =
    validate(txCbor, protocolParams, inputUtxos, currentSlot, slotConfig, networkId, scriptSupplier, null)

  /**
   * Validate a transaction against full Cardano ledger rules with ledger state.
   *
   * @param txCbor              serialized transaction CBOR bytes
   * @param protocolParams      CCL ProtocolParams
   * @param inputUtxos          set of resolved input UTxOs (CCL Utxo)
   * @param currentSlot         current slot (e.g. from validity start interval)
   * @param slotConfig          opaque SlotConfigHandle from SlotConfigBridge
   * @param networkId           0 = testnet, 1 = mainnet
   * @param scriptSupplier      nullable ScriptSupplier for reference script resolution
   * @param ledgerStateProvider nullable LedgerStateProvider for account/delegation state
   * @return TransitResult with success/failure and error details
   */
  def validate(
      txCbor: Array[Byte],
      protocolParams: CclProtocolParams,
      inputUtxos: util.Set[Utxo],
      currentSlot: Long,
      slotConfig: SlotConfig,
      networkId: Int,
      scriptSupplier: ScriptSupplier,
      ledgerStateProvider: LedgerStateProvider
  ): TransitResult =
    try
      val scalusParams = ProtocolParamsBridge.toScalusProtocolParams(protocolParams)

      given ProtocolVersion = ProtocolParamsBridge.extractProtocolVersion(protocolParams)
      val scalusTx = ScalusTx.fromCbor(txCbor)

      val scalusUtxos = UtxoBridge.convert(inputUtxos, scriptSupplier)

      val network = ProtocolParamsBridge.toNetwork(networkId)
      val sc = if slotConfig != null then slotConfig else throw new RuntimeException("SlotConfig not found");

      // Build CertState from ledger state provider if available
      val (certState, totalDeposited) = if ledgerStateProvider != null then
        CertStateBridge.build(ledgerStateProvider, scalusTx)
      else (CertState.empty, Coin.zero)

      val env = UtxoEnv(currentSlot, scalusParams, certState, network)

      val context = Context(
        Coin.zero,
        env,
        sc,
        EvaluatorMode.Validate
      )

      val state = State(
        scalusUtxos,
        certState,
        totalDeposited,
        Coin.zero,   // fees
        new Array[io.bullet.borer.Dom.Element](0), // govState (empty)
        scala.collection.immutable.Map.empty,       // stakeDistribution
        Coin.zero    // donation
      )

      val result = YanoCardanoMutator.transit(context, state, scalusTx)

      result match
        case Right(_) =>
          new TransitResult(true, null, null)
        case Left(error) =>
          new TransitResult(false, error.getMessage, error.getClass.getSimpleName)

    catch
      case e: LinkageError if ScalusNativeFailures.isBlsUnavailable(e) =>
        new TransitResult(false, ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE,
          ScalusNativeFailures.BLS_UNAVAILABLE_RULE)
      case e: RuntimeException if ScalusNativeFailures.isBlsUnavailable(e) =>
        new TransitResult(false, ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE,
          ScalusNativeFailures.BLS_UNAVAILABLE_RULE)
      case e: Exception =>
        new TransitResult(false, "Validation error: " + e.getMessage, e.getClass.getSimpleName)

  /**
   * Validates a transaction against the state of a ledger view (ADR-056 step 1d, the Scalus engine
   * adapter): UTxOs already resolved from the view, account, pool and DRep state from an overlay-aware
   * [[LedgerStateProvider]].
   *
   * Unlike [[validate]], exceptions are not turned into failures: a
   * `LedgerStateUnavailableException` raised by the provider, a decoding error or a Scalus crash reach the
   * caller, which fails closed.
   *
   * @return `null` when Scalus accepts the transaction, otherwise the mapped failure
   */
  def validateAgainstView(
      txCbor: Array[Byte],
      protocolParams: CclProtocolParams,
      utxos: util.Collection[UtxoEntry],
      currentSlot: Long,
      slotConfig: SlotConfig,
      networkId: Int,
      ledgerStateProvider: LedgerStateProvider
  ): LedgerFailure =
    val scalusParams = ProtocolParamsBridge.toScalusProtocolParams(protocolParams)
    val protocolVersion = ProtocolParamsBridge.extractProtocolVersion(protocolParams)
    val scalusTx = ScalusTransactions.decode(txCbor, protocolVersion)
    val scalusUtxos = UtxoEntryBridge.convert(utxos)
    val (certState, totalDeposited) = CertStateBridge.build(ledgerStateProvider, scalusTx)
    val env = UtxoEnv(currentSlot, scalusParams, certState, ProtocolParamsBridge.toNetwork(networkId))
    val context = Context(Coin.zero, env, slotConfig, EvaluatorMode.Validate)
    val state = State(
      scalusUtxos,
      certState,
      totalDeposited,
      Coin.zero,
      new Array[io.bullet.borer.Dom.Element](0),
      scala.collection.immutable.Map.empty,
      Coin.zero
    )
    YanoCardanoMutator.transit(context, state, scalusTx) match
      case Right(_) => null
      case Left(error) => ScalusFailureMapping.toLedgerFailure(error, protocolVersion.major)
