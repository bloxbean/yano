package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.api.model.ProtocolParams as CclProtocolParams
import com.bloxbean.cardano.client.common.model.SlotConfig as CclSlotConfig
import org.yanoproject.ledger.rules.phase2.ScriptOutcome
import org.yanoproject.ledger.rules.view.model.UtxoEntry
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.utils.{AllNeededScriptHashes, AllResolvedScripts}

import java.util
import scala.jdk.CollectionConverters.*

/**
 * The Scalus calls behind [[ScalusScriptPhaseEvaluator]]: script well-formedness, the needed Plutus scripts
 * and the CEK run. Java-facing: arguments and results are Java/CCL types.
 */
object ScalusPhaseTwo:

  /** Result of [[evaluate]]. */
  final class Evaluation(val passed: Boolean, val scripts: util.List[ScriptOutcome])

  private def decode(txCbor: Array[Byte], protocolMajor: Int, protocolMinor: Int): ScalusTx =
    ScalusTransactions.decode(txCbor, ProtocolVersion(protocolMajor, protocolMinor))

  /**
   * Haskell `validateScriptsWellFormed`, first half (Babbage/Rules/Utxow.hs:264-273): the Plutus witness
   * scripts that do not decode as a program of their language at the protocol version (flat decoding, no
   * trailing bytes from V3, only builtins available at that version; Scalus `PlutusScript.isWellFormed`).
   *
   * @return the hashes (hex) of the malformed witness scripts
   */
  def malformedWitnessScripts(txCbor: Array[Byte], protocolMajor: Int, protocolMinor: Int): util.List[String] =
    val tx = decode(txCbor, protocolMajor, protocolMinor)
    val major = MajorProtocolVersion(protocolMajor)
    AllResolvedScripts.allWitnessesPlutusScripts(tx).toSeq
      .filterNot(_.isWellFormed(major))
      .map(_.scriptHash.toHex).sorted.asJava

  /**
   * Haskell `isValidPlutusScript` (Alonzo/Scripts.hs:276-277) for one script: whether its `PlutusBinary` (the
   * contents of the script's CBOR byte string) decodes as a program of its language at the protocol version
   * (Scalus `PlutusScript.isWellFormed`).
   *
   * @param language 1 PlutusV1, 2 PlutusV2, 3 PlutusV3
   */
  def isWellFormed(language: Int, script: Array[Byte], protocolMajor: Int): Boolean =
    val bytes = scalus.uplc.builtin.ByteString.fromArray(script)
    val plutus: PlutusScript = language match
      case 1 => Script.PlutusV1(bytes)
      case 2 => Script.PlutusV2(bytes)
      case 3 => Script.PlutusV3(bytes)
      case other => throw new IllegalArgumentException(s"not a Plutus language: $other")
    try plutus.isWellFormed(MajorProtocolVersion(protocolMajor))
    catch case _: Exception => false

  /**
   * Haskell `validateScriptsWellFormed`, second half: reference scripts of the transaction's own outputs,
   * including the collateral return, that are not well-formed.
   *
   * @return the hashes (hex) of the malformed reference scripts
   */
  def malformedOutputReferenceScripts(txCbor: Array[Byte], protocolMajor: Int,
                                      protocolMinor: Int): util.List[String] =
    val tx = decode(txCbor, protocolMajor, protocolMinor)
    val major = MajorProtocolVersion(protocolMajor)
    val body = tx.body.value
    val outputs = body.outputs.map(_.value) ++ body.collateralReturnOutput.map(_.value).toSeq
    outputs.flatMap(_.scriptRef).map(_.script).collect { case plutus: PlutusScript => plutus }
      .filterNot(_.isWellFormed(major))
      .map(_.scriptHash.toHex).distinct.sorted.asJava

  /**
   * The needed Plutus scripts (Haskell `resolveNeededPlutusScriptsWithPurpose`): every needed script hash
   * (spending inputs, mints, certificates, withdrawals, votes, proposals) that a witness or a resolved
   * reference script provides as a Plutus script.
   */
  def neededPlutusScripts(txCbor: Array[Byte], resolved: util.Collection[UtxoEntry], protocolMajor: Int,
                          protocolMinor: Int): util.List[NeededPlutusScript] =
    val tx = decode(txCbor, protocolMajor, protocolMinor)
    val utxos = UtxoEntryBridge.convert(resolved)
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
          Some(NeededPlutusScript(purpose(tag), index, hash.toHex, plutus.language.languageId + 1,
            redeemers.contains((tag, index))))
        case _ => None
    }.asJava

  /**
   * Runs every needed Plutus script with its redeemer's declared ExUnits as budget
   * ([[EvaluatorMode.Validate]]), stopping at the first failure as Scalus does.
   */
  def evaluate(txCbor: Array[Byte], resolved: util.Collection[UtxoEntry], params: CclProtocolParams,
               slotConfig: CclSlotConfig): Evaluation =
    val protocolVersion = ProtocolParamsBridge.extractProtocolVersion(params)
    val tx = decode(txCbor, protocolVersion.major, protocolVersion.minor)
    val utxos = UtxoEntryBridge.convert(resolved)
    val maxTx = ExUnits(parseLong(params.getMaxTxExMem), parseLong(params.getMaxTxExSteps))
    val evaluator = PlutusScriptEvaluator(
      SlotConfig(slotConfig.getZeroTime, slotConfig.getZeroSlot, slotConfig.getSlotLength),
      maxTx,
      MajorProtocolVersion(protocolVersion.major),
      ProtocolParamsBridge.costModels(params),
      EvaluatorMode.Validate,
      false,
      false
    )
    try
      val results = evaluator.evalPlutusScriptsWithContexts(tx, utxos)
      val outcomes = results.map { case (redeemer, _, _) =>
        new ScriptOutcome(purpose(redeemer.tag), redeemer.index, true, redeemer.exUnits.memory,
          redeemer.exUnits.steps, util.List.of(), null)
      }
      new Evaluation(true, outcomes.asJava)
    catch
      case e: PlutusScriptEvaluationException =>
        val outcome = new ScriptOutcome("script " + e.failedScriptHash.toHex, -1, false, e.spentBudget.memory,
          e.spentBudget.steps, e.logs.toSeq.asJava, firstLine(e.getMessage))
        new Evaluation(false, util.List.of(outcome))

  private def firstLine(message: String): String =
    if message == null then "script failed" else message.linesIterator.nextOption().getOrElse(message)

  private def parseLong(value: String): Long =
    if value == null || value.isBlank then Long.MaxValue else java.lang.Long.parseLong(value.trim)

  private def purpose(tag: RedeemerTag): String = tag match
    case RedeemerTag.Spend => "spend"
    case RedeemerTag.Mint => "mint"
    case RedeemerTag.Cert => "cert"
    case RedeemerTag.Reward => "reward"
    case RedeemerTag.Voting => "voting"
    case RedeemerTag.Proposing => "proposing"
