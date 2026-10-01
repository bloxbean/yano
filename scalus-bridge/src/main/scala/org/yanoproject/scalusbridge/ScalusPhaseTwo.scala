package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.api.model.ProtocolParams as CclProtocolParams
import com.bloxbean.cardano.client.common.model.SlotConfig as CclSlotConfig
import org.yanoproject.ledger.rules.TxIdentity
import org.yanoproject.ledger.rules.conway.utxow.PlutusScriptDecoder
import org.yanoproject.ledger.rules.phase2.ScriptCollection
import org.yanoproject.ledger.rules.phase2.ScriptCollection.NeededScript
import org.yanoproject.ledger.rules.phase2.ScriptOutcome
import org.yanoproject.ledger.rules.view.model.UtxoEntry
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.utils.{AllNeededScriptHashes, AllResolvedScripts}
import scalus.uplc.builtin.Data

import java.math.BigInteger
import java.util
import scala.jdk.CollectionConverters.*

/**
 * The Scalus calls behind [[ScalusScriptPhaseEvaluator]]: script well-formedness, the needed Plutus scripts
 * and the CEK run. Java-facing: arguments and results are Java/CCL types.
 */
object ScalusPhaseTwo:

  /** Result of [[evaluate]]. */
  final class Evaluation(val passed: Boolean, val scripts: util.List[ScriptOutcome])

  /**
   * A transaction and its resolved outputs decoded for Scalus once ([[prepare]]), with the integers
   * [[WideIntegers]] narrowed for Scalus's decoder and the function that restores them in script arguments.
   */
  final class Prepared private[ScalusPhaseTwo] (val tx: ScalusTx, val utxos: Utxos,
                                                 private[ScalusPhaseTwo] val restore: Data => Data,
                                                 private[ScalusPhaseTwo] val rewardIndexes: Map[Int, Int]):
    /** @return a redeemer index as Haskell numbers it ([[WithdrawalOrder]] renumbers `Rewarding` ones) */
    private[ScalusPhaseTwo] def index(tag: RedeemerTag, index: Int): Int =
      if tag == RedeemerTag.Reward then rewardIndexes.getOrElse(index, index) else index

  /**
   * Decodes the transaction and its resolved outputs for Scalus. Integers Scalus reads as a signed long but Haskell
   * as a `Word64` (or an `Integer`) are narrowed first ([[WideIntegers]]); the transaction keeps its original body
   * bytes, so its id (and every hash of it in a context) is unchanged.
   *
   * @return the prepared transaction, or the reason it cannot be narrowed ([[WideIntegers.Narrowing.unsupported]])
   */
  def prepare(txCbor: Array[Byte], resolved: util.Collection[UtxoEntry], protocolMajor: Int,
              protocolMinor: Int): Either[String, Prepared] =
    val entries = resolved.asScala.toIndexedSeq
    val outputs = entries.map(UtxoEntryBridge.outputBytes)
    val datums = entries.map(_.inlineDatumCbor())
    val narrowing = WideIntegers.narrow(txCbor, outputs.asJava, datums.asJava)
    if narrowing.unsupported().isPresent then Left(narrowing.unsupported().get())
    else
      val decoded = ScalusTransactions.decode(narrowing.txCbor(), ProtocolVersion(protocolMajor, protocolMinor))
      val (tx, rewardIndexes) = WithdrawalOrder.remap(
        if narrowing.txCbor() eq txCbor then decoded
        else decoded.copy(body = KeepRaw.unsafe(decoded.body.value, TxIdentity.bodyBytes(txCbor))))
      val utxos = entries.indices.map { i =>
        UtxoEntryBridge.input(entries(i).outpoint()) ->
          UtxoEntryBridge.output(narrowing.outputs().get(i), narrowing.datums().get(i))
      }.toMap
      val restore =
        if narrowing.restoresNothing() then identity[Data]
        else restoring(toScala(narrowing.integers()), toScala(narrowing.constructors()))
      Right(new Prepared(tx, utxos, restore, rewardIndexes))

  private def toScala(map: util.Map[BigInteger, BigInteger]): Map[BigInt, BigInt] =
    map.asScala.map((k, v) => BigInt(k) -> BigInt(v)).toMap

  /** Puts back narrowed integers: `I` placeholders as values, `Constr` placeholders as alternatives. */
  private def restoring(integers: Map[BigInt, BigInt], constructors: Map[BigInt, BigInt]): Data => Data =
    def restore(data: Data): Data = data match
      case Data.I(value) => integers.get(value).fold(data)(Data.I(_))
      case Data.Constr(index, args) => Data.Constr(constructors.getOrElse(index, index), args.map(restore))
      case Data.Map(entries) => Data.Map(entries.map { case (k, v) => (restore(k), restore(v)) })
      case Data.List(items) => Data.List(items.map(restore))
      case other => other
    restore

  /**
   * Haskell `validateScriptsWellFormed`, first half (Babbage/Rules/Utxow.hs:264-273): the Plutus witness
   * scripts that do not decode as a program of their language at the protocol version (flat decoding, no
   * trailing bytes from V3, only builtins available at that version; Scalus `PlutusScript.isWellFormed`).
   *
   * @return the hashes (hex) of the malformed witness scripts
   */
  def malformedWitnessScripts(prepared: Prepared, protocolMajor: Int): util.List[String] =
    AllResolvedScripts.allWitnessesPlutusScripts(prepared.tx).toSeq
      .filterNot(wellFormed(_, protocolMajor))
      .map(_.scriptHash.toHex).sorted.asJava

  /**
   * Haskell `isValidPlutusScript` (Alonzo/Scripts.hs:276-277) for one script: whether its `PlutusBinary` (the
   * contents of the script's CBOR byte string) decodes as a program of its language at the protocol version
   * (Scalus `PlutusScript.isWellFormed`), a PlutusV1/V2 one by its leading CBOR item
   * (`PlutusScriptDecoder.leadingItem`).
   *
   * @param language 1 PlutusV1, 2 PlutusV2, 3 PlutusV3
   */
  def isWellFormed(language: Int, script: Array[Byte], protocolMajor: Int): Boolean =
    val bytes = scalus.uplc.builtin.ByteString.fromArray(
      if language == 1 || language == 2 then PlutusScriptDecoder.leadingItem(script) else script)
    val plutus: PlutusScript = language match
      case 1 => Script.PlutusV1(bytes)
      case 2 => Script.PlutusV2(bytes)
      case 3 => Script.PlutusV3(bytes)
      case other => throw new IllegalArgumentException(s"not a Plutus language: $other")
    try plutus.isWellFormed(MajorProtocolVersion(protocolMajor))
    catch case _: Exception => false

  private def wellFormed(script: PlutusScript, protocolMajor: Int): Boolean =
    isWellFormed(script.language.languageId + 1, script.script.bytes, protocolMajor)

  /**
   * Haskell `validateScriptsWellFormed`, second half: reference scripts of the transaction's own outputs,
   * including the collateral return, that are not well-formed.
   *
   * @return the hashes (hex) of the malformed reference scripts
   */
  def malformedOutputReferenceScripts(prepared: Prepared, protocolMajor: Int): util.List[String] =
    val body = prepared.tx.body.value
    val outputs = body.outputs.map(_.value) ++ body.collateralReturnOutput.map(_.value).toSeq
    outputs.flatMap(_.scriptRef).map(_.script).collect { case plutus: PlutusScript => plutus }
      .filterNot(wellFormed(_, protocolMajor))
      .map(_.scriptHash.toHex).distinct.sorted.asJava

  /**
   * The needed Plutus scripts (Haskell `resolveNeededPlutusScriptsWithPurpose`): every needed script hash
   * (spending inputs, mints, certificates, withdrawals, votes, proposals) that a witness or a resolved
   * reference script provides as a Plutus script.
   */
  def neededPlutusScripts(prepared: Prepared): util.List[NeededScript] =
    val tx = prepared.tx
    val utxos = prepared.utxos
    val scripts = AllResolvedScripts.allResolvedScriptsMap(tx, utxos) match
      case Right(map) => map
      case Left(error) => throw new IllegalStateException(s"cannot resolve the transaction's scripts: $error")
    val needed = AllNeededScriptHashes.allNeededScriptData(tx, utxos) match
      case Right(data) => data.toSeq
      case Left(error) => throw new IllegalStateException(s"cannot compute the needed scripts: $error")
    val redeemers = tx.witnessSet.redeemers.map(_.value.toMap).getOrElse(Map.empty)
    needed.flatMap { case (tag, index, hash, _) =>
      scripts.get(hash) match
        case Some(plutus: PlutusScript) =>
          Some(NeededScript(purpose(tag), prepared.index(tag, index).toLong, hash.toHex,
            plutus.language.languageId + 1, redeemers.contains((tag, index)), plutus.script.bytes))
        case _ => None
    }.asJava

  /**
   * Runs every needed Plutus script with its redeemer's declared ExUnits as budget, stopping at the first failure,
   * in the bridge's loop ([[ContextEvaluation]], the same as Scalus's `PlutusScriptEvaluator` in validate mode),
   * which also applies the protocol-version-9 PlutusV3 context ([[BootstrapPhaseContexts]]) and restores narrowed
   * integers ([[WideIntegers]]). A failure names its redeemer.
   */
  def evaluate(prepared: Prepared, params: CclProtocolParams, slotConfig: CclSlotConfig): Evaluation =
    val protocolVersion = ProtocolParamsBridge.extractProtocolVersion(params)
    val tx = prepared.tx
    try
      val redeemers = ContextEvaluation.evaluate(tx, prepared.utxos,
        SlotConfig(slotConfig.getZeroTime, slotConfig.getZeroSlot, slotConfig.getSlotLength),
        MajorProtocolVersion(protocolVersion.major), ProtocolParamsBridge.costModels(params),
        bootstrapPhase = BootstrapPhaseContexts.applies(tx, protocolVersion.major), restore = prepared.restore)
      val outcomes = redeemers.map { redeemer =>
        new ScriptOutcome(purpose(redeemer.tag), prepared.index(redeemer.tag, redeemer.index), true,
          redeemer.exUnits.memory, redeemer.exUnits.steps, util.List.of(), null)
      }
      new Evaluation(true, outcomes.asJava)
    catch
      case e: ContextEvaluation.ScriptFailure =>
        val outcome = new ScriptOutcome(purpose(e.redeemer.tag), prepared.index(e.redeemer.tag, e.redeemer.index),
          false, e.spentBudget.memory, e.spentBudget.steps, e.logs.asJava,
          "script " + e.scriptHash.toHex + ": " + firstLine(e.getMessage))
        new Evaluation(false, util.List.of(outcome))

  /**
   * The arguments each needed script receives ([[ContextEvaluation.arguments]]), for comparing the bridge's contexts
   * with another translation.
   */
  def arguments(prepared: Prepared, protocolMajor: Int, slotConfig: CclSlotConfig): Seq[(Redeemer, Seq[Data])] =
    ContextEvaluation.arguments(prepared.tx, prepared.utxos,
      SlotConfig(slotConfig.getZeroTime, slotConfig.getZeroSlot, slotConfig.getSlotLength),
      MajorProtocolVersion(protocolMajor), BootstrapPhaseContexts.applies(prepared.tx, protocolMajor),
      prepared.restore).map((redeemer, _, args) => (redeemer, args))

  private def firstLine(message: String): String =
    if message == null then "script failed" else message.linesIterator.nextOption().getOrElse(message)

  /** The purpose name of a redeemer tag; Scalus declares the tags in CDDL order (0 spend … 5 proposing). */
  private def purpose(tag: RedeemerTag): String = ScriptCollection.purposeName(tag.ordinal)
