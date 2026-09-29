package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.LedgerToPlutusTranslation.*
import scalus.cardano.ledger.utils.{AllNeededScriptHashes, AllResolvedScripts}
import scalus.cardano.onchain.plutus.{v1, v2, v3}
import scalus.cardano.onchain.plutus.prelude.{Option as POption, SortedMap as PSortedMap}
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.{Log, MachineParams, PlutusVM, RestrictingBudgetSpender}

import scala.util.control.NonFatal

/**
 * The Conway bootstrap-phase (protocol version 9) PlutusV3 script context, which Scalus 1.1.1 does not build.
 *
 * Haskell `transTxCert` (cardano-ledger `f649f975`, Conway/TxInfo.hs:572-581) translates a `RegDepositTxCert`
 * (`reg_cert`, tag 7) to `TxCertRegStaking cred Nothing` and an `UnRegDepositTxCert` (`unreg_cert`, tag 8) to
 * `TxCertUnRegStaking cred Nothing` while `hardforkConwayBootstrapPhase` (`pvMajor == 9`); from protocol version 10 they
 * carry the deposit. Every other certificate is translated the same at 9 and 10 (the legacy tags 0 and 1 never carry
 * a deposit; `RegDepositDelegTxCert` always does). The translation applies wherever a V3 `TxCert` appears: the
 * `TxInfo` certificates, the `TxInfo` redeemer map's certifying purposes (`transTxRedeemers` →
 * `transPlutusPurposeV3`, :636-640) and the certifying `ScriptInfo`. Plutus V1/V2 contexts (`transTxCertV1V2`,
 * :383-397) never carry the deposit, at any protocol version.
 *
 * Scalus's `LedgerToPlutusTranslation.getTxCertV3` takes no protocol version and always includes the deposit, and its
 * evaluator builds contexts internally. So when the difference applies ([[applies]]), [[evaluate]] runs the scripts
 * with its own loop, the same as Scalus's `PlutusScriptEvaluator` (`evalPlutusScriptsWithContexts`, validate mode:
 * needed scripts in Scalus's order, each budgeted by its redeemer's ExUnits, stopping at the first failure), over
 * the translated V3 context ([[translate]]). Every other transaction keeps Scalus's evaluator.
 */
object BootstrapPhaseContexts:

  /** `hardforkConwayBootstrapPhase` (Conway/Era.hs:257-258). */
  val BootstrapProtocolMajor: Int = 9

  /** @return whether the transaction has a certificate whose V3 translation differs at `protocolMajor` */
  def applies(tx: ScalusTx, protocolMajor: Int): Boolean =
    protocolMajor == BootstrapProtocolMajor && tx.body.value.certificates.toSeq.exists {
      case Certificate.RegCert(_, Some(_)) | Certificate.UnregCert(_, Some(_)) => true
      case _ => false
    }

  /** `transTxCert` at protocol version 9: the (de)registration deposit is `Nothing`. */
  def withoutDeposit(cert: v3.TxCert): v3.TxCert = cert match
    case v3.TxCert.RegStaking(credential, _) => v3.TxCert.RegStaking(credential, POption.None)
    case v3.TxCert.UnRegStaking(credential, _) => v3.TxCert.UnRegStaking(credential, POption.None)
    case other => other

  private def translatePurpose(purpose: v3.ScriptPurpose): v3.ScriptPurpose = purpose match
    case v3.ScriptPurpose.Certifying(index, cert) => v3.ScriptPurpose.Certifying(index, withoutDeposit(cert))
    case other => other

  /** The bootstrap-phase `TxInfo`: certificates and the certifying purposes of the redeemer map. */
  def translate(txInfo: v3.TxInfo): v3.TxInfo =
    // The redeemer map is ordered by purpose; a certifying purpose is ordered by its index first, and the indexes
    // are distinct, so the translated keys keep the order.
    txInfo.copy(
      certificates = txInfo.certificates.map(withoutDeposit),
      redeemers = PSortedMap.unsafeFromList(txInfo.redeemers.toList.map { case (purpose, redeemer) =>
        (translatePurpose(purpose), redeemer)
      })
    )

  /** The bootstrap-phase certifying `ScriptInfo`. */
  def translate(scriptInfo: v3.ScriptInfo): v3.ScriptInfo = scriptInfo match
    case v3.ScriptInfo.CertifyingScript(index, cert) => v3.ScriptInfo.CertifyingScript(index, withoutDeposit(cert))
    case other => other

  /** @return the translated V3 script contexts of the transaction's needed PlutusV3 scripts, in evaluation order */
  def scriptContextsV3(tx: ScalusTx, utxos: Utxos, slotConfig: SlotConfig,
                       protocolVersion: MajorProtocolVersion): Seq[v3.ScriptContext] =
    lazy val txInfo = translate(getTxInfoV3(tx, utxos, slotConfig, protocolVersion))
    neededPlutus(tx, utxos).collect { case (redeemer, _: Script.PlutusV3, datum) =>
      v3.ScriptContext(txInfo, redeemer.data, translate(getScriptInfoV3(tx, redeemer, datum)))
    }

  /**
   * Runs the needed Plutus scripts over the bootstrap-phase contexts.
   *
   * @return the redeemers with the ExUnits each script spent
   * @throws PlutusScriptEvaluationException at the first failing script, as Scalus's evaluator does
   */
  def evaluate(tx: ScalusTx, utxos: Utxos, slotConfig: SlotConfig, protocolVersion: MajorProtocolVersion,
               costModels: CostModels): Seq[Redeemer] =
    lazy val txInfoV1 = getTxInfoV1(tx, utxos, slotConfig, protocolVersion)
    lazy val txInfoV2 = getTxInfoV2(tx, utxos, slotConfig, protocolVersion)
    lazy val txInfoV3 = translate(getTxInfoV3(tx, utxos, slotConfig, protocolVersion))
    lazy val vmV1 = PlutusVM.makePlutusV1VM(
      MachineParams.fromCostModels(costModels, Language.PlutusV1, protocolVersion), protocolVersion)
    lazy val vmV2 = PlutusVM.makePlutusV2VM(
      MachineParams.fromCostModels(costModels, Language.PlutusV2, protocolVersion), protocolVersion)
    lazy val vmV3 = PlutusVM.makePlutusV3VM(
      MachineParams.fromCostModels(costModels, Language.PlutusV3, protocolVersion), protocolVersion)
    neededPlutus(tx, utxos).map { case (redeemer, script, datum) =>
      val (vm, args) = script match
        case _: Script.PlutusV1 =>
          (vmV1, datum.toSeq :+ redeemer.data :+ v1.ScriptContext(txInfoV1, getScriptPurposeV1(tx, redeemer)).toData)
        case _: Script.PlutusV2 =>
          (vmV2, datum.toSeq :+ redeemer.data :+ v2.ScriptContext(txInfoV2, getScriptPurposeV2(tx, redeemer)).toData)
        case _: Script.PlutusV3 =>
          (vmV3, Seq(v3.ScriptContext(txInfoV3, redeemer.data,
            translate(getScriptInfoV3(tx, redeemer, datum))).toData))
        case other =>
          throw new UnsupportedOperationException(s"${other.language} script evaluation is not supported")
      redeemer.copy(exUnits = run(vm, script, redeemer, args))
    }

  /**
   * The needed Plutus scripts with their redeemers and datums, in the order and with the checks of Scalus's
   * `evalPlutusScriptsWithContexts`: native scripts are skipped, a missing script, redeemer or V1/V2 spending datum
   * is an error.
   */
  private def neededPlutus(tx: ScalusTx, utxos: Utxos): Seq[(Redeemer, PlutusScript, Option[Data])] =
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

  private def run(vm: PlutusVM, script: PlutusScript, redeemer: Redeemer, args: Seq[Data]): ExUnits =
    val applied = args.foldLeft(script.deBruijnedProgram)((program, arg) => program $ arg)
    val spender = RestrictingBudgetSpender(redeemer.exUnits)
    val logger = Log()
    try vm.evaluateScript(applied, spender, logger)
    catch
      case NonFatal(e) =>
        throw new PlutusScriptEvaluationException(e.getMessage, e, logger.getLogs, script.scriptHash,
          spentBudget = spender.getSpentBudget)
    spender.getSpentBudget
