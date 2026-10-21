package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.ledger.LedgerToPlutusTranslation.*
import scalus.cardano.onchain.plutus.v3
import scalus.cardano.onchain.plutus.prelude.{Option as POption, SortedMap as PSortedMap}

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
 * evaluator builds contexts internally. So when the difference applies ([[applies]]), the scripts run over the
 * translated V3 context ([[translate]]) in the bridge's own loop ([[ContextEvaluation]], the same as Scalus's
 * `PlutusScriptEvaluator.evalPlutusScriptsWithContexts` in validate mode).
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
    ContextEvaluation.neededPlutus(tx, utxos).collect { case (redeemer, _: Script.PlutusV3, datum) =>
      v3.ScriptContext(txInfo, redeemer.data, translate(getScriptInfoV3(tx, redeemer, datum)))
    }
