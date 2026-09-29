package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Redeemer, RedeemerTag, Transaction as ScalusTx}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data

import java.util.Arrays

/**
 * The governance fields of a PlutusV3 context as cardano-ledger `f649f975` builds them, where Scalus 1.1.1 does not.
 *
 * Haskell translates each ledger `Map` entry by entry in the map's own order (`transMap`, Conway/TxInfo.hs:697-699)
 * and a `Set` by `Set.toList`, so they follow the ledger's `Ord`: a `Credential` puts `ScriptHashObj` first
 * (Credential.hs:98-101), a `Voter` is committee, then DRep, then pool (each by credential), a `GovActionId` is the
 * transaction id's bytes, then the index. Rationals are reduced: `transBoundedRational = fromHaskellRatio .
 * unboundRational` (Plutus/TxInfo.hs:118-119) and `ToPlutusData Rational` (ToPlutusData.hs:77-79). Scalus 1.1.1
 * differs in each of these:
 *  - `txInfoVotes` (field 12): voters and action ids sorted by their `toString` (`getVotingProcedures`), so index 10
 *    comes before 9; and a `Voting` purpose's voter is looked up by the redeemer's index in that order too
 *    (`getScriptPurposeV3`), so a voting script can see another voter than the one it votes for;
 *  - `TreasuryWithdrawals` and `UpdateCommittee`'s added members: `SortedMap.fromList` with the on-chain `Ord`,
 *    `PubKeyCredential` first; the removed members in hash-set order (`ScalaSet.toSeq`);
 *  - the quorum and the `ParameterChange` rationals kept as decoded, unreduced (`6/2000`);
 *  - `NewConstitution`: plutus-ledger-api's `newtype Constitution` is `Constr 0 [Maybe ScriptHash]`
 *    (V3/Contexts.hs:266-273, :692; Conway/TxInfo.hs:682-693), while Scalus's `type Constitution =
 *    Option[ScriptHash]` drops the constructor (preview PV 11 `89d3a627…`).
 *
 * [[fix]] rewrites the votes, the `Voting` purposes (of `txInfoRedeemers`, field 9, and of a `VotingScript` script
 * info) from the redeemer's index into the ledger's voters, and every proposal procedure: `txInfoProposalProcedures`
 * (field 13), the `Proposing` purposes of `txInfoRedeemers` and a `ProposingScript` script info. `ScalusContextDifferentialTest`
 * compares the result with script-evaluators' `ConwayTxInfoTranslator`; `ScalusWorkaroundsTest` has the canaries.
 */
object V3Governance:

  private val TreasuryWithdrawals = BigInt(2)
  private val UpdateCommittee = BigInt(4)
  private val ParameterChange = BigInt(0)
  private val NewConstitution = BigInt(5)
  private val Proposing = BigInt(5)
  private val Voting = BigInt(4)

  /**
   * The `ChangedParameters` keys whose values hold rationals (`UnitInterval`/`NonNegativeInterval`, Conway/PParams.hs
   * `eraPParams`): pool pledge influence (9), expansion rate (10), treasury growth rate (11), execution prices (19, a
   * list of two), pool and DRep voting thresholds (25, 26, lists), the reference-script fee per byte (33).
   */
  private val RationalParameters = Set(9, 10, 11, 19, 25, 26, 33).map(BigInt(_))

  /**
   * @param context  a PlutusV3 `ScriptContext` as Data, from Scalus's translation
   * @param tx       the transaction (its voting redeemers' indexes)
   * @param redeemer the redeemer of the script the context is for
   */
  def fix(context: Data, tx: ScalusTx, redeemer: Redeemer): Data = context match
    case Data.Constr(c, fields) if c == 0 && fields.toScalaList.size == 3 =>
      val List(txInfo, data, scriptInfo) = fields.toScalaList: @unchecked
      val votingIndexes = tx.witnessSet.redeemers.map(_.value.toIndexedSeq).getOrElse(IndexedSeq.empty)
        .filter(_.tag == RedeemerTag.Voting).map(_.index).sorted
      val (fixedInfo, voters) = fixTxInfo(txInfo, votingIndexes)
      val info = scriptInfo match
        case Data.Constr(v, _) if v == Voting && redeemer.tag == RedeemerTag.Voting =>
          Data.Constr(v, PList.from(List(voters(redeemer.index))))
        case other => fixPurpose(other)
      Data.Constr(c, PList.from(List(fixedInfo, data, info)))
    case other => other

  /** @return the fixed `TxInfo` and its voters in the ledger's order */
  private def fixTxInfo(txInfo: Data, votingIndexes: IndexedSeq[Int]): (Data, IndexedSeq[Data]) = txInfo match
    case Data.Constr(c, fields) if fields.toScalaList.size >= 14 =>
      val all = fields.toScalaList.toIndexedSeq
      val votes = all(12) match
        case Data.Map(entries) => Data.Map(sorted(entries.toScalaList.map { case (voter, ballots) =>
          (voter, ballots match
            case Data.Map(byAction) => Data.Map(sorted(byAction.toScalaList, actionBefore))
            case other => other)
        }, voterBefore))
        case other => other
      val voters = votes match
        case Data.Map(entries) => entries.toScalaList.map(_._1).toIndexedSeq
        case _ => IndexedSeq.empty
      // Scalus lists txInfoRedeemers by (tag, index): the k-th Voting entry is the k-th voting redeemer.
      val votingIndex = votingIndexes.iterator
      val redeemers = all(9) match
        case Data.Map(entries) => Data.Map(PList.from(entries.toScalaList.map {
          case (Data.Constr(v, _), value) if v == Voting && votingIndex.hasNext =>
            (Data.Constr(v, PList.from(List(voters(votingIndex.next())))), value)
          case (k, value) => (fixPurpose(k), value)
        }))
        case other => other
      val proposals = all(13) match
        case Data.List(items) => Data.List(items.map(fixProcedure))
        case other => other
      (Data.Constr(c, PList.from(all.updated(9, redeemers).updated(12, votes).updated(13, proposals).toList)), voters)
    case other => (other, IndexedSeq.empty)

  /** `Proposing index procedure` (a `ScriptPurpose`) or `ProposingScript index procedure` (a `ScriptInfo`). */
  private def fixPurpose(purpose: Data): Data = purpose match
    case Data.Constr(c, fields) if c == Proposing && fields.toScalaList.size == 2 =>
      val List(index, procedure) = fields.toScalaList: @unchecked
      Data.Constr(c, PList.from(List(index, fixProcedure(procedure))))
    case other => other

  /** `ProposalProcedure deposit returnAddress action`. */
  private def fixProcedure(procedure: Data): Data = procedure match
    case Data.Constr(c, fields) if c == 0 && fields.toScalaList.size == 3 =>
      val List(deposit, returnAddress, action) = fields.toScalaList: @unchecked
      Data.Constr(c, PList.from(List(deposit, returnAddress, fixAction(action))))
    case other => other

  private def fixAction(action: Data): Data = action match
    case Data.Constr(a, args) =>
      val fields = args.toScalaList
      val fixed = a match
        case ParameterChange if fields.size == 3 => fields.updated(1, reduceParameters(fields(1)))
        case TreasuryWithdrawals if fields.size == 2 => fields.updated(0, credentialMap(fields(0)))
        case UpdateCommittee if fields.size == 4 =>
          val removed = fields(1) match
            case Data.List(items) => Data.List(PList.from(items.toScalaList.sortWith(credentialBefore)))
            case other => other
          fields.updated(1, removed).updated(2, credentialMap(fields(2))).updated(3, reduce(fields(3)))
        case NewConstitution if fields.size == 2 => fields.updated(1, Data.Constr(0, PList.from(List(fields(1)))))
        case _ => fields
      Data.Constr(a, PList.from(fixed))
    case other => other

  private def credentialMap(data: Data): Data = data match
    case Data.Map(entries) => Data.Map(sorted(entries.toScalaList, credentialBefore))
    case other => other

  private def reduceParameters(data: Data): Data = data match
    case Data.Map(entries) => Data.Map(entries.map { case (k, v) =>
      k match
        case Data.I(key) if RationalParameters.contains(key) => (k, reduce(v))
        case _ => (k, v)
    })
    case other => other

  /** A rational `List [I n, I d]` or `Constr 0 [I n, I d]` reduced; a list of rationals element by element. */
  private def reduce(data: Data): Data = data match
    case Data.List(items) => items.toScalaList match
      case List(Data.I(n), Data.I(d)) => Data.List(PList.from(reduced(n, d)))
      case rationals => Data.List(PList.from(rationals.map(reduce)))
    case Data.Constr(c, fields) if c == 0 => fields.toScalaList match
      case List(Data.I(n), Data.I(d)) => Data.Constr(c, PList.from(reduced(n, d)))
      case _ => data
    case other => other

  private def reduced(n: BigInt, d: BigInt): List[Data] =
    if d == 0 then List(Data.I(n), Data.I(d))
    else
      val gcd = n.gcd(d) * d.signum
      List(Data.I(n / gcd), Data.I(d / gcd))

  private def sorted(entries: List[(Data, Data)], before: (Data, Data) => Boolean): PList[(Data, Data)] =
    PList.from(entries.sortWith((x, y) => before(x._1, y._1)))

  private def credentialBefore(a: Data, b: Data): Boolean = WithdrawalOrder.before(a, b, scriptFirst = true)

  /** `CommitteeVoter`, `DRepVoter` (constructors 0 and 1, each a credential), `StakePoolVoter` (2, a key hash). */
  private def voterBefore(a: Data, b: Data): Boolean = (a, b) match
    case (Data.Constr(ca, fa), Data.Constr(cb, fb)) if ca != cb => ca < cb
    case (Data.Constr(c, fa), Data.Constr(_, fb)) if c < 2 => credentialBefore(fa.toScalaList.head, fb.toScalaList.head)
    case (Data.Constr(_, fa), Data.Constr(_, fb)) => Arrays.compareUnsigned(bytes(fa.toScalaList.head),
      bytes(fb.toScalaList.head)) < 0
    case _ => false

  /** `GovernanceActionId txId index`: the id's bytes, then the index. */
  private def actionBefore(a: Data, b: Data): Boolean = (a, b) match
    case (Data.Constr(_, fa), Data.Constr(_, fb)) =>
      val List(ta, ia) = fa.toScalaList: @unchecked
      val List(tb, ib) = fb.toScalaList: @unchecked
      val byId = Arrays.compareUnsigned(bytes(ta), bytes(tb))
      if byId != 0 then byId < 0 else integer(ia) < integer(ib)
    case _ => false

  /** The bytes of a `B`, or of a newtype over one encoded as `Constr 0 [B]`. */
  private def bytes(data: Data): Array[Byte] = data match
    case Data.B(b) => b.bytes
    case Data.Constr(_, fields) if fields.toScalaList.size == 1 => bytes(fields.toScalaList.head)
    case _ => Array.emptyByteArray

  private def integer(data: Data): BigInt = data match
    case Data.I(i) => i
    case _ => BigInt(0)
