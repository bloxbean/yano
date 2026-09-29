package org.yanoproject.scalusbridge

import org.yanoproject.ledger.rules.conway.utxow.PlutusScriptDecoder
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.LedgerToPlutusTranslation.*
import scalus.cardano.ledger.utils.{AllNeededScriptHashes, AllResolvedScripts}
import scalus.cardano.onchain.plutus.{v1, v2, v3}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.DeBruijnedProgram
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.{Log, MachineParams, RestrictingBudgetSpender}

import scala.util.control.NonFatal

/**
 * Runs a transaction's needed Plutus scripts over script contexts the bridge builds with Scalus's translation
 * (`LedgerToPlutusTranslation`), the same as Scalus's `PlutusScriptEvaluator.evalPlutusScriptsWithContexts` in
 * validate mode: needed scripts in Scalus's order (`AllNeededScriptHashes`), native scripts skipped, a missing script,
 * redeemer or V1/V2 spending datum an error, each script budgeted by its redeemer's ExUnits on the VM of its language
 * (`MachineParams.fromCostModels` at the protocol version), stopping at the first failure. The machine is
 * [[BridgeVM]]: Scalus's, with `equalsData`, `serialiseData` and `verifyEcdsaSecp256k1Signature` following plutus-core
 * where Scalus 1.1.1 does not.
 *
 * Owning the loop lets the bridge adjust what a script receives, which Scalus's evaluator builds internally:
 *  - `bootstrapPhase`: the PlutusV3 context of protocol version 9 ([[BootstrapPhaseContexts]]);
 *  - `restore`: applied to every script argument (datum, redeemer, context), to put back the integers
 *    [[WideIntegers]] narrowed for Scalus's decoder;
 *  - every PlutusV3 context gets its governance fields as Haskell builds them ([[V3Governance]]);
 *  - with several withdrawals, `txInfoWdrl` and the `Rewarding` redeemers are put in Haskell's order
 *    ([[WithdrawalOrder]]; the redeemers are renumbered when the transaction is prepared);
 * and name the redeemer of a failing script ([[ScriptFailure]]; Scalus's exception names only the script hash).
 */
object ContextEvaluation:

  /** A script failed: which redeemer, which script, what it spent and logged. */
  final class ScriptFailure(val redeemer: Redeemer, val scriptHash: ScriptHash, val spentBudget: ExUnits,
                            val logs: Seq[String], message: String, cause: Throwable)
    extends RuntimeException(message, cause)

  /**
   * @return the redeemers with the ExUnits each script spent
   * @throws ScriptFailure at the first failing script
   */
  def evaluate(tx: ScalusTx, utxos: Utxos, slotConfig: SlotConfig, protocolVersion: MajorProtocolVersion,
               costModels: CostModels, bootstrapPhase: Boolean, restore: Data => Data): Seq[Redeemer] =
    def vm(language: Language) =
      new BridgeVM(language, MachineParams.fromCostModels(costModels, language, protocolVersion), protocolVersion)
    lazy val vmV1 = vm(Language.PlutusV1)
    lazy val vmV2 = vm(Language.PlutusV2)
    lazy val vmV3 = vm(Language.PlutusV3)
    arguments(tx, utxos, slotConfig, protocolVersion, bootstrapPhase, restore).map { case (redeemer, script, args) =>
      val machine = script match
        case _: Script.PlutusV1 => vmV1
        case _: Script.PlutusV2 => vmV2
        case _ => vmV3
      redeemer.copy(exUnits = run(machine, script, redeemer, args))
    }

  /**
   * The arguments each needed Plutus script receives, in evaluation order: V1/V2 `[datum,] redeemer, context`, V3
   * `[context]`, as Haskell builds them (the corrections listed on [[ContextEvaluation]]).
   */
  def arguments(tx: ScalusTx, utxos: Utxos, slotConfig: SlotConfig, protocolVersion: MajorProtocolVersion,
                bootstrapPhase: Boolean, restore: Data => Data): Seq[(Redeemer, PlutusScript, Seq[Data])] =
    lazy val txInfoV1 = getTxInfoV1(tx, utxos, slotConfig, protocolVersion)
    lazy val txInfoV2 = getTxInfoV2(tx, utxos, slotConfig, protocolVersion)
    lazy val txInfoV3 =
      val info = getTxInfoV3(tx, utxos, slotConfig, protocolVersion)
      if bootstrapPhase then BootstrapPhaseContexts.translate(info) else info
    val withdrawals = tx.body.value.withdrawals.exists(_.withdrawals.size > 1)
    def ordered(language: Int, context: Data) =
      if withdrawals then WithdrawalOrder.fixContext(language, context) else context
    neededPlutus(tx, utxos).map { case (redeemer, script, datum) =>
      val context = script match
        case _: Script.PlutusV1 => ordered(1, v1.ScriptContext(txInfoV1, getScriptPurposeV1(tx, redeemer)).toData)
        case _: Script.PlutusV2 => ordered(2, v2.ScriptContext(txInfoV2, getScriptPurposeV2(tx, redeemer)).toData)
        case _: Script.PlutusV3 =>
          val info = getScriptInfoV3(tx, redeemer, datum)
          ordered(3, V3Governance.fix(v3.ScriptContext(txInfoV3, redeemer.data,
            if bootstrapPhase then BootstrapPhaseContexts.translate(info) else info).toData, tx, redeemer))
        case other =>
          throw new UnsupportedOperationException(s"${other.language} script evaluation is not supported")
      val args = script match
        case _: Script.PlutusV3 => Seq(context)
        case _ => datum.toSeq :+ redeemer.data :+ context
      (redeemer, script, args.map(restore))
    }

  /**
   * The needed Plutus scripts with their redeemers and datums, in the order and with the checks of Scalus's
   * `evalPlutusScriptsWithContexts`.
   */
  def neededPlutus(tx: ScalusTx, utxos: Utxos): Seq[(Redeemer, PlutusScript, Option[Data])] =
    val redeemers = tx.witnessSet.redeemers.map(_.value.toMap).getOrElse(Map.empty)
    val scripts = AllResolvedScripts.allResolvedScriptsMap(tx, utxos) match
      case Right(map) => map
      case Left(error) => throw error
    val needed = AllNeededScriptHashes.allNeededScriptData(tx, utxos) match
      case Right(data) => data.toSeq
      case Left(error) => throw error
    needed.flatMap { case (tag, index, hash, output) =>
      val datum = output.flatMap(_.resolveDatum(tx))
      scripts.get(hash) match
        case Some(plutus: PlutusScript) =>
          if tag == RedeemerTag.Spend && datum.isEmpty then
            plutus match
              case _: Script.PlutusV1 | _: Script.PlutusV2 =>
                throw new IllegalStateException(s"Missing required datum for plutus script: $plutus")
              case _ =>
          val redeemer = redeemers.get((tag, index)) match
            case Some((data, exUnits)) => Redeemer(tag = tag, index = index, data = data, exUnits = exUnits)
            case None => throw new IllegalStateException(s"Redeemer not found for tag $tag and index $index")
          Some((redeemer, plutus, datum))
        case Some(_) => None
        case None => throw new IllegalStateException(s"Script not found: $hash")
    }

  private def run(vm: BridgeVM, script: PlutusScript, redeemer: Redeemer, args: Seq[Data]): ExUnits =
    val applied = args.foldLeft(PlutusBinaries.program(script))((program, arg) => program $ arg)
    val spender = RestrictingBudgetSpender(redeemer.exUnits)
    val logger = Log()
    try vm.evaluateScript(applied, spender, logger)
    catch
      case NonFatal(e) =>
        throw new ScriptFailure(redeemer, script.scriptHash, spender.getSpentBudget, logger.getLogs.toSeq,
          e.getMessage, e)
    spender.getSpentBudget

/**
 * A PlutusV1/V2 script's program, as Haskell reads it.
 *
 * plutus-ledger-api 1.65.0.0 `deserialiseScript` decodes the CBOR byte string that wraps the flat program and, for
 * PlutusV1 and V2 only, ignores any bytes after it (PlutusLedgerApi/Common/SerialisedScript.hs:261-264: the
 * `RemainderError` applies from PlutusV3). Scalus 1.1.1 decodes the whole binary as one CBOR item, so a V1/V2 script
 * with trailing bytes is not well formed there (`PlutusScript.isWellFormed`) and cannot run. Preprod, PV 10:
 * transactions `aee75c1c…` and `c3cc23d4…` create PlutusV2 reference scripts (`5272e713…`, `3db59752…`) whose
 * binary is followed by more bytes; the chain accepted them, and the Java engine reported
 * `UTXOW.MalformedReferenceScripts`. The hash stays that of the whole binary.
 */
object PlutusBinaries:

  /** @return the script's program, a V1/V2 one from its leading CBOR item (`PlutusScriptDecoder.leadingItem`) */
  def program(script: PlutusScript): DeBruijnedProgram = script match
    case _: Script.PlutusV1 | _: Script.PlutusV2 =>
      val binary = script.script.bytes
      val leading = PlutusScriptDecoder.leadingItem(binary)
      if leading eq binary then script.deBruijnedProgram else DeBruijnedProgram.fromCbor(leading)
    case _ => script.deBruijnedProgram
