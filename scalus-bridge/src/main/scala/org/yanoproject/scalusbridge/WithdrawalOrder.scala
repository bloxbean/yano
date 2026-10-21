package org.yanoproject.scalusbridge

import scalus.cardano.address.{StakeAddress, StakePayload}
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data

import java.util.Arrays

/**
 * Haskell's order of a transaction's withdrawals, which Scalus 1.1.1 does not follow.
 *
 * cardano-ledger orders withdrawals by account address: the network, then the credential, and `Credential` derives
 * `Ord` with `ScriptHashObj` before `KeyHashObj` (cardano-ledger-core Credential.hs:98-101, Address.hs:183-190). That
 * order gives a `Rewarding` redeemer its index and orders `txInfoWdrl` and the `Rewarding` entries of
 * `txInfoRedeemers`. Scalus orders a `RewardAccount` by the network, then the credential's hash bytes only
 * (RewardAccount.scala:24-30), so when a transaction withdraws from both a key and a script credential, a `Rewarding`
 * redeemer names another withdrawal: preprod, PV 10, six chain-valid transactions (e.g. `22434324…`, withdrawals
 * `e08a04df…` (key) and `f0d1f873…` (script), redeemer `reward[0]`) reported `CollectErrors [NoRedeemer reward[1]]`.
 *
 * [[remap]] renumbers the `Rewarding` redeemers from Haskell's order to Scalus's, so that Scalus pairs each with the
 * withdrawal Haskell pairs it with (the script, its purpose); [[fixContext]] then puts `txInfoWdrl` and the
 * `Rewarding` entries of `txInfoRedeemers` back in Haskell's order, which depends on the language:
 *  - `txInfoRedeemers` (V2, V3) lists the redeemers in the ledger's `(tag, index)` order (Babbage/TxInfo.hs:222-226),
 *    so its `Rewarding` entries follow the ledger's order: script credentials first;
 *  - V3 `txInfoWdrl` is the ledger map translated entry by entry (`transMap`, Conway/TxInfo.hs:549-551, :697-699):
 *    script credentials first;
 *  - V1/V2 `txInfoWdrl` is rebuilt as a `Map PV1.StakingCredential` (`transWithdrawals`, Alonzo/Plutus/TxInfo.hs:
 *    301-309; V2 wraps its `Map.toList`, Conway/TxInfo.hs:469), so it follows plutus-ledger-api's derived `Ord`, in
 *    which `PubKeyCredential` comes before `ScriptCredential` (PlutusLedgerApi/V1/Credential.hs:30-37): key credentials
 *    first. Within a kind, both orders compare the hash bytes.
 */
object WithdrawalOrder:

  /**
   * @return the transaction with its `Rewarding` redeemers renumbered to Scalus's order, and Scalus's index to
   *         Haskell's for every renumbered withdrawal (empty when the orders agree)
   */
  def remap(tx: ScalusTx): (ScalusTx, Map[Int, Int]) =
    val accounts = tx.body.value.withdrawals.map(_.withdrawals.keys.toIndexedSeq).getOrElse(IndexedSeq.empty)
    val haskell = accounts.sortWith((a, b) => compare(a, b) < 0)
    if haskell == accounts then (tx, Map.empty)
    else
      val scalusIndex = accounts.zipWithIndex.toMap
      val haskellToScalus = haskell.indices.map(h => h -> scalusIndex(haskell(h))).toMap
      val witnessSet = tx.witnessSet
      val renumbered = witnessSet.redeemers.map { raw =>
        val redeemers = raw.value.toIndexedSeq.map { r =>
          if r.tag == RedeemerTag.Reward then r.copy(index = haskellToScalus.getOrElse(r.index, r.index)) else r
        }
        KeepRaw(Redeemers.from(redeemers))
      }
      val updated = tx.copy(witnessSetRaw = KeepRaw(witnessSet.copy(redeemers = renumbered)))
      (updated, haskellToScalus.map(_.swap))

  /** cardano-ledger's `Ord AccountAddress`: network, then script credentials before key credentials, then the hash. */
  private def compare(a: RewardAccount, b: RewardAccount): Int = (a.address, b.address) match
    case (StakeAddress(n1, p1), StakeAddress(n2, p2)) if n1 == n2 && rank(p1) != rank(p2) =>
      Integer.compare(rank(p1), rank(p2))
    case _ => summon[Ordering[RewardAccount]].compare(a, b) // Scalus's: the network, then the hash

  private def rank(payload: StakePayload): Int = payload match
    case _: StakePayload.Script => 0
    case _ => 1

  /**
   * Puts `txInfoWdrl` (V1 field 5, V2 and V3 field 6) and the `Rewarding` block of `txInfoRedeemers` (V2 and V3 field
   * 9) in Haskell's order.
   *
   * @param language 1, 2 or 3
   * @param context  the script context as Data
   */
  def fixContext(language: Int, context: Data): Data = context match
    case Data.Constr(c, fields) if c == 0 && fields.toScalaList.nonEmpty =>
      val all = fields.toScalaList
      Data.Constr(c, PList.from(fixTxInfo(language, all.head) :: all.tail))
    case other => other

  private def fixTxInfo(language: Int, txInfo: Data): Data = txInfo match
    case Data.Constr(c, fields) =>
      val all = fields.toScalaList.toIndexedSeq
      val withdrawals = if language == 1 then 5 else 6
      // V1/V2: plutus-ledger-api's Credential order (key first); V3: the ledger's (script first).
      val scriptFirst = language == 3
      if all.size <= withdrawals then txInfo
      else
        var updated = all.updated(withdrawals, all(withdrawals) match
          case Data.List(items) =>
            Data.List(PList.from(items.toScalaList.sortWith((x, y) => before(pairKey(x), pairKey(y), scriptFirst))))
          case Data.Map(entries) =>
            Data.Map(PList.from(entries.toScalaList.sortWith((x, y) => before(x._1, y._1, scriptFirst))))
          case other => other)
        if language != 1 && all.size > 9 then
          updated = updated.updated(9, all(9) match
            case Data.Map(entries) => Data.Map(PList.from(reorderRewarding(entries.toScalaList)))
            case other => other)
        Data.Constr(c, PList.from(updated.toList))
    case other => other

  /** A V1 withdrawal is a pair `Constr 0 [stakingCredential, amount]`. */
  private def pairKey(pair: Data): Data = pair match
    case Data.Constr(_, fields) if fields.toScalaList.nonEmpty => fields.toScalaList.head
    case other => other

  /** Stable reorder of the `Rewarding` purposes (constructor 2) among their own positions. */
  private def reorderRewarding(entries: List[(Data, Data)]): List[(Data, Data)] =
    def rewarding(key: Data) = key match
      case Data.Constr(c, _) => c == 2
      case _ => false
    val sorted = entries.filter(e => rewarding(e._1))
      .sortWith((x, y) => before(purposeKey(x._1), purposeKey(y._1), scriptFirst = true))
    val it = sorted.iterator
    entries.map(e => if rewarding(e._1) then it.next() else e)

  private def purposeKey(purpose: Data): Data = purpose match
    case Data.Constr(_, fields) if fields.toScalaList.nonEmpty => fields.toScalaList.head
    case other => other

  /**
   * Two credentials, as Data (a V3 `Credential` or a V1/V2 `StakingHash credential`), in the ledger's order
   * (`scriptFirst`: `Ord Credential`, Credential.hs:98-101) or plutus-ledger-api's (key first). The one credential
   * order of the bridge's context corrections ([[V3Governance]] too).
   */
  private[scalusbridge] def before(a: Data, b: Data, scriptFirst: Boolean): Boolean =
    val (ka, ha) = credential(a, scriptFirst)
    val (kb, hb) = credential(b, scriptFirst)
    if ka != kb then ka < kb else Arrays.compareUnsigned(ha, hb) < 0

  /** @return (the kind's rank: a `ScriptCredential` is constructor 1, a `PubKeyCredential` 0; the hash bytes) */
  private def credential(data: Data, scriptFirst: Boolean): (Int, Array[Byte]) = data match
    case Data.Constr(c, fields) if c <= 1 && fields.toScalaList.size == 1 =>
      fields.toScalaList.head match
        case Data.B(hash) => ((if scriptFirst then 1 - c else c).toInt, hash.bytes)
        case inner => credential(inner, scriptFirst) // StakingHash (Constr 0 [credential])
    case _ => (2, Array.emptyByteArray)
