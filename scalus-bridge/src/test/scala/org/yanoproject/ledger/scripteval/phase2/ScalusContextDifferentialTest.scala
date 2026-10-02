package org.yanoproject.ledger.scripteval.phase2

import com.bloxbean.cardano.client.address.{AddressProvider, Credential}
import com.bloxbean.cardano.client.plutus.spec.{ConstrPlutusData, ExUnits as CclExUnits, Redeemer as CclRedeemer, RedeemerTag as CclRedeemerTag}
import com.bloxbean.cardano.client.spec.UnitInterval
import com.bloxbean.cardano.client.transaction.spec.{ProtocolParamUpdate, Transaction, Withdrawal}
import com.bloxbean.cardano.client.transaction.spec.governance.{Anchor, Constitution, ProposalProcedure, Vote, Voter, VoterType, VotingProcedure, VotingProcedures}
import com.bloxbean.cardano.client.transaction.spec.governance.actions.{GovAction, GovActionId, NewConstitution, ParameterChangeAction, TreasuryWithdrawalsAction, UpdateCommittee}
import org.julclang.core.PlutusData as JulcData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yanoproject.api.utxo.model.Outpoint
import org.yanoproject.ledger.rules.conway.tx.RawTransaction
import org.yanoproject.ledger.rules.fixtures.tx.{ConwayTxBuilder, MutationWorld, TxSpec}
import org.yanoproject.ledger.rules.view.model.{Outpoints, UtxoEntry}
import org.yanoproject.scalusbridge.ScalusPhaseTwo
import scalus.cardano.ledger.RedeemerTag
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.{ByteString, Data}

import java.math.BigInteger
import java.util
import scala.jdk.CollectionConverters.*

/**
 * ADR-056 Phase 7c: the script contexts the Scalus bridge builds (Scalus's translation with the bridge's corrections)
 * against script-evaluators' [[ConwayTxInfoTranslator]], which builds them as cardano-ledger `f649f975` does. Both are
 * compared as encoded `Data`, so the order of every map and list matters.
 *
 * The transaction spends the PlutusV3 script input and votes with its script as a DRep. It carries key and script
 * voters, action ids whose indexes compare differently as text (9 and 10), a treasury withdrawal to a key and to a
 * script credential, a committee update removing six members and adding three (key and script, more than four so
 * that a hash set's order shows), an unreduced quorum of 6/2000, a parameter change with unreduced rationals, and a
 * new constitution with a guardrail script. Both scripts run: the spend and the DRep vote.
 */
class ScalusContextDifferentialTest:

  private val script = MutationWorld.ALWAYS_SUCCEEDS
  private val scriptHash = script.getScriptHash

  private def hash(fill: Int): Array[Byte] = Array.fill[Byte](28)(fill.toByte)
  private def key(fill: Int) = Credential.fromKey(hash(fill))
  private def scriptCred(fill: Int) = Credential.fromScript(hash(fill))
  private def account(c: Credential) = AddressProvider.getRewardAddress(c, MutationWorld.NETWORK).toBech32
  private val anchor = new Anchor("https://example.com/a", Array.fill[Byte](32)(7))
  // Only the contexts are compared (no phase one), so any deposit does.
  private val deposit = BigInteger.valueOf(1_000_000)

  private def governanceSpec(): TxSpec =
    val spec = MutationWorld.scriptSpec()
    // Votes: a committee key, a DRep script (this transaction's script) and a DRep key, a pool.
    val actions = Seq(new GovActionId("c0" * 32, 10), new GovActionId("c0" * 32, 9), new GovActionId("0a" * 32, 0))
    val votes = new util.LinkedHashMap[Voter, util.Map[GovActionId, VotingProcedure]]()
    val ballot = () =>
      val m = new util.LinkedHashMap[GovActionId, VotingProcedure]()
      actions.foreach(a => m.put(a, new VotingProcedure(Vote.YES, null)))
      m
    votes.put(new Voter(VoterType.DREP_KEY_HASH, Credential.fromKey(hash(0x01))), ballot())
    votes.put(new Voter(VoterType.DREP_SCRIPT_HASH, Credential.fromScript(scriptHash)), ballot())
    votes.put(new Voter(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH, Credential.fromKey(hash(0xe0))), ballot())
    votes.put(new Voter(VoterType.STAKING_POOL_KEY_HASH, Credential.fromKey(hash(0x77))), ballot())
    spec.votingProcedures = new VotingProcedures(votes)
    // Voters in the ledger's order: committee key, DRep script, DRep key, pool: the script votes at index 1.
    spec.redeemers.add(CclRedeemer.builder().tag(CclRedeemerTag.Voting).index(1).data(ConstrPlutusData.of(0))
      .exUnits(CclExUnits.builder().mem(BigInteger.valueOf(100_000)).steps(BigInteger.valueOf(50_000_000)).build())
      .build())
    val proposal = (action: GovAction) =>
      ProposalProcedure.builder().deposit(deposit).rewardAccount(account(key(0x42)))
        .govAction(action).anchor(anchor).build()
    spec.proposals.add(proposal(TreasuryWithdrawalsAction.builder()
      .withdrawals(util.List.of(new Withdrawal(account(key(0x02)), BigInteger.valueOf(5)),
        new Withdrawal(account(scriptCred(0xd0)), BigInteger.valueOf(6)))).build()))
    val removed = new util.LinkedHashSet[Credential]()
    Seq(key(0x03), scriptCred(0xd1), key(0x90), scriptCred(0x05), key(0xa7), scriptCred(0x6e)).foreach(removed.add)
    val added = new util.LinkedHashMap[Credential, Integer]()
    added.put(key(0x04), 50)
    added.put(scriptCred(0xd2), 51)
    added.put(key(0x80), 52)
    spec.proposals.add(proposal(UpdateCommittee.builder().membersForRemoval(removed).newMembersAndTerms(added)
      .quorumThreshold(new UnitInterval(BigInteger.valueOf(6), BigInteger.valueOf(2000))).build()))
    spec.proposals.add(proposal(ParameterChangeAction.builder().protocolParamUpdate(ProtocolParamUpdate.builder()
      .expansionRate(new UnitInterval(BigInteger.valueOf(6), BigInteger.valueOf(2000)))
      .treasuryGrowthRate(new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(4))).build()).build()))
    spec.proposals.add(proposal(NewConstitution.builder()
      .constitution(Constitution.builder().anchor(anchor).scripthash("fa" * 28).build()).build()))
    spec.changeAdjust = deposit.multiply(BigInteger.valueOf(4)).negate()
    spec

  @Test
  def governanceFieldsMatchHaskellsTranslation(): Unit =
    val compared = compare(governanceSpec(), 10)
    assertEquals(Seq(RedeemerTag.Spend, RedeemerTag.Voting), compared)

  /** @return the redeemer tags compared; fails at the first context that differs */
  private def compare(spec: TxSpec, protocolMajor: Int): Seq[RedeemerTag] =
    val view = MutationWorld.view(protocolMajor)
    val cbor = ConwayTxBuilder.build(spec, view).cbor()
    val raw = RawTransaction.parse(cbor, Transaction.deserialize(cbor))
    val resolved = new util.HashMap[Outpoint, UtxoEntry]()
    raw.allInputs().asScala.foreach { in =>
      view.utxo(Outpoints.normalize(in.outpoint())).orElseThrowUnavailable()
        .ifPresent(e => resolved.put(e.outpoint(), e))
    }
    val slotConfig = MutationWorld.env(protocolMajor).slotConfig()
    val prepared = ScalusPhaseTwo.prepare(cbor, resolved.values(), protocolMajor, 0)
      .fold(reason => throw new IllegalStateException(reason), identity)
    val translator = new ConwayTxInfoTranslator(raw, resolved, protocolMajor, slotConfig)
    ScalusPhaseTwo.arguments(prepared, protocolMajor, slotConfig).map { (redeemer, args) =>
      val reference = translator.redeemer(redeemer.tag.ordinal, redeemer.index).orElseThrow()
      val expected = translator.arguments(3, reference).asScala.map(toScalus).toSeq
      assertEquals(expected.map(render), args.map(render), s"${redeemer.tag}[${redeemer.index}]")
      redeemer.tag
    }

  /** The encoded Data, one line per map entry or list item, so an assertion shows where the order differs. */
  private def render(data: Data): String = data match
    case Data.Constr(c, fields) => s"<$c ${fields.toScalaList.map(render).mkString("[", ", ", "]")}>"
    case Data.Map(entries) => entries.toScalaList.map((k, v) => s"\n  ${render(k)} -> ${render(v)}").mkString("{", "", "}")
    case Data.List(items) => items.toScalaList.map(render).mkString("[", ", ", "]")
    case Data.I(i) => i.toString
    case Data.B(b) => "#" + b.toHex

  private def toScalus(data: JulcData): Data = data match
    case c: JulcData.ConstrData => Data.Constr(BigInt(c.constructorTag()), PList.from(c.fields().asScala.map(toScalus)))
    case m: JulcData.MapData =>
      Data.Map(PList.from(m.entries().asScala.map(p => (toScalus(p.key()), toScalus(p.value())))))
    case l: JulcData.ListData => Data.List(PList.from(l.items().asScala.map(toScalus)))
    case i: JulcData.IntData => Data.I(BigInt(i.value()))
    case b: JulcData.BytesData => Data.B(ByteString.fromArray(b.value()))
