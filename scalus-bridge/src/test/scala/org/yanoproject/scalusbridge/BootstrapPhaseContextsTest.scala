package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.plutus.spec.{ConstrPlutusData, ExUnits as CclExUnits, PlutusV3Script, Redeemer as CclRedeemer, RedeemerTag as CclRedeemerTag}
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.cert.{Certificate as CclCertificate, RegCert, StakeCredential, StakeRegistration, UnregCert}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yanoproject.api.utxo.model.Outpoint
import org.yanoproject.ledger.rules.fixtures.tx.{BuiltTx, ConwayTxBuilder, MutationWorld, TxSpec}
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult
import org.yanoproject.ledger.rules.view.model.{Outpoints, UtxoEntry}
import scalus.cardano.ledger.{LedgerToPlutusTranslation, MajorProtocolVersion, ProtocolVersion, SlotConfig}
import scalus.cardano.onchain.plutus.prelude.Option as POption
import scalus.cardano.onchain.plutus.v3
import scalus.uplc.Program

import java.math.BigInteger
import java.util
import scala.jdk.CollectionConverters.*

/**
 * The Conway bootstrap-phase PlutusV3 context (Conway/TxInfo.hs:572-581, :636-640): at protocol version 9 a
 * `reg_cert` / `unreg_cert` carries no deposit in the `TxInfo` certificates, the redeemer map's certifying purposes
 * and the certifying `ScriptInfo`; from 10 it does. Checked with real PlutusV3 scripts that read the deposit.
 */
class BootstrapPhaseContextsTest:

  private val evaluator = new ScalusScriptPhaseEvaluator()

  /** 1 when a `Maybe` must be `Nothing`, 0 when it must be `Just`. */
  private def depositChecker(expectedMaybeTag: Int): PlutusV3Script =
    // isNothing c = fstPair (unConstrData (field 1 of c)) == expected; checks the first TxInfo certificate (TxInfo
    // field 5) and the certifying ScriptInfo's certificate (ScriptContext field 2, CertifyingScript field 1).
    val isExpected =
      s"(lam c [(builtin equalsInteger) [(force (force (builtin fstPair))) [(builtin unConstrData) " +
        "[(force (builtin headList)) [(force (builtin tailList)) [(force (force (builtin sndPair))) " +
        s"[(builtin unConstrData) c]]]]]] (con integer $expectedMaybeTag)])"
    val tail = (n: Int, list: String) => (1 to n).foldLeft(list)((l, _) => s"[(force (builtin tailList)) $l]")
    val txInfoCert = "[(force (builtin headList)) [(builtin unListData) [(force (builtin headList)) " +
      tail(5, "txInfoFields") + "]]]"
    val scriptInfoCert = "[(force (builtin headList)) [(force (builtin tailList)) [(force (force (builtin sndPair))) " +
      "[(builtin unConstrData) [(force (builtin headList)) " + tail(2, "fields") + "]]]]]"
    val source =
      s"""(program 1.1.0
         |  (lam ctx
         |    [(lam isExpected
         |      [(lam fields
         |        [(lam txInfoFields
         |          (force [(force (builtin ifThenElse)) [isExpected $txInfoCert]
         |            (delay (force [(force (builtin ifThenElse)) [isExpected $scriptInfoCert]
         |              (delay (con unit ()))
         |              (delay (error))]))
         |            (delay (error))]))
         |         [(force (force (builtin sndPair))) [(builtin unConstrData) [(force (builtin headList)) fields]]]])
         |       [(force (force (builtin sndPair))) [(builtin unConstrData) ctx]]])
         |     $isExpected]))""".stripMargin
    val program = Program.parseUplc(source).fold(e => throw new IllegalArgumentException(e), identity)
    PlutusV3Script.builder().`type`("PlutusScriptV3").cborHex(program.doubleCborHex).build()
      .asInstanceOf[PlutusV3Script]

  private val expectsNothing = depositChecker(1)
  private val expectsJust = depositChecker(0)

  /** The simple base with `cert` (credential: `script`), certified by `script` (redeemer Cert 0). */
  private def certifying(script: PlutusV3Script, cert: StakeCredential => CclCertificate, implicitCoin: BigInteger)
      : TxSpec =
    val spec = MutationWorld.simpleSpec()
    spec.certs.add(cert(StakeCredential.fromScriptHash(script.getScriptHash)))
    spec.plutusScripts.add(script)
    spec.redeemers.add(CclRedeemer.builder().tag(CclRedeemerTag.Cert).index(BigInteger.ZERO)
      .data(ConstrPlutusData.of(0))
      .exUnits(CclExUnits.builder().mem(BigInteger.valueOf(2_000_000)).steps(BigInteger.valueOf(2_000_000_000L))
        .build())
      .build())
    spec.collateral.add(MutationWorld.collateralInput(0))
    spec.changeAdjust = implicitCoin
    spec

  private def registering(script: PlutusV3Script): TxSpec =
    certifying(script, c => new RegCert(c, MutationWorld.KEY_DEPOSIT), MutationWorld.KEY_DEPOSIT.negate())

  private def build(spec: TxSpec, protocolMajor: Int): BuiltTx =
    ConwayTxBuilder.build(spec, MutationWorld.view(protocolMajor))

  private def resolved(built: BuiltTx, protocolMajor: Int): util.Map[Outpoint, UtxoEntry] =
    val view = MutationWorld.view(protocolMajor)
    val body = built.tx().getBody
    val inputs = body.getInputs.asScala ++ Option(body.getCollateral).map(_.asScala).getOrElse(Nil)
    val map = new util.LinkedHashMap[Outpoint, UtxoEntry]()
    inputs.foreach { (in: TransactionInput) =>
      val outpoint = Outpoints.of(in.getTransactionId, in.getIndex)
      view.utxo(outpoint).orElseThrowUnavailable().ifPresent(e => map.put(outpoint, e))
    }
    map

  private def evaluate(spec: TxSpec, protocolMajor: Int): ScriptPhaseResult =
    val built = build(spec, protocolMajor)
    evaluator.evaluate(built.cbor(), built.tx(), resolved(built, protocolMajor),
      MutationWorld.protocolParams(protocolMajor), MutationWorld.env(protocolMajor).slotConfig())

  @Test
  def aRegistrationDepositIsNothingAtProtocolVersion9AndJustFrom10(): Unit =
    assertTrue(evaluator.translatesBootstrapPhaseCertificateDeposits())
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], evaluate(registering(expectsNothing), 9))
    assertInstanceOf(classOf[ScriptPhaseResult.Failed], evaluate(registering(expectsNothing), 10))
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], evaluate(registering(expectsJust), 10))
    // The failure path of the bootstrap-phase evaluation.
    val failed = evaluate(registering(expectsJust), 9)
    assertInstanceOf(classOf[ScriptPhaseResult.Failed], failed)
    assertFalse(failed.asInstanceOf[ScriptPhaseResult.Failed].scripts().get(0).success())

  @Test
  def aDeregistrationRefundIsNothingAtProtocolVersion9(): Unit =
    // Deregistering a credential no ledger holds: the evaluator only runs the scripts (phase one is not its job).
    val deregistering = (script: PlutusV3Script) =>
      certifying(script, c => new UnregCert(c, MutationWorld.KEY_DEPOSIT), MutationWorld.KEY_DEPOSIT)
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], evaluate(deregistering(expectsNothing), 9))
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], evaluate(deregistering(expectsJust), 10))
    assertInstanceOf(classOf[ScriptPhaseResult.Failed], evaluate(deregistering(expectsNothing), 10))

  @Test
  def theTranslatedContextAtEveryV3Position(): Unit =
    val built = build(registering(expectsNothing), 9)
    val tx = ScalusTransactions.decode(built.cbor(), ProtocolVersion(9, 0))
    val utxos = UtxoEntryBridge.convert(resolved(built, 9).values())
    assertTrue(BootstrapPhaseContexts.applies(tx, 9))
    assertFalse(BootstrapPhaseContexts.applies(tx, 10))
    val ccl = MutationWorld.env(9).slotConfig()
    val slotConfig = SlotConfig(ccl.getZeroTime, ccl.getZeroSlot, ccl.getSlotLength)
    val contexts = BootstrapPhaseContexts.scriptContextsV3(tx, utxos, slotConfig, MajorProtocolVersion(9))
    assertEquals(1, contexts.size)
    val context = contexts.head
    val noDeposit: v3.TxCert => Boolean = {
      case v3.TxCert.RegStaking(_, deposit) => deposit == POption.None
      case _ => false
    }
    assertTrue(noDeposit(context.txInfo.certificates.head))
    assertTrue(context.txInfo.redeemers.toList.toScalaList.map(_._1).forall {
      case v3.ScriptPurpose.Certifying(_, cert) => noDeposit(cert)
      case _ => true
    })
    assertEquals(1, context.txInfo.redeemers.toList.toScalaList.size)
    context.scriptInfo match
      case v3.ScriptInfo.CertifyingScript(index, cert) =>
        assertEquals(BigInt(0), index)
        assertTrue(noDeposit(cert))
      case other => fail(s"expected a certifying script, got $other")
    // Scalus's own translation keeps the deposit (why the bootstrap phase needs its own contexts).
    val untranslated = LedgerToPlutusTranslation.getTxInfoV3(tx, utxos, slotConfig, MajorProtocolVersion(9))
    assertFalse(noDeposit(untranslated.certificates.head))

  @Test
  def onlyTheDepositOfRegistrationAndDeregistrationIsDropped(): Unit =
    val credential = scalus.cardano.onchain.plutus.v1.Credential.ScriptCredential(
      scalus.uplc.builtin.ByteString.fromHex("11" * 28))
    val delegatee = v3.Delegatee.Stake(
      scalus.cardano.onchain.plutus.v1.PubKeyHash(scalus.uplc.builtin.ByteString.fromHex("22" * 28)))
    // RegDepositDelegTxCert keeps its deposit at every protocol version (Conway/TxInfo.hs:585-586).
    val regDeleg = v3.TxCert.RegDeleg(credential, delegatee, BigInt(2_000_000))
    assertEquals(regDeleg, BootstrapPhaseContexts.withoutDeposit(regDeleg))
    assertEquals(v3.TxCert.UnRegStaking(credential, POption.None),
      BootstrapPhaseContexts.withoutDeposit(v3.TxCert.UnRegStaking(credential, POption.Some(BigInt(2_000_000)))))
    // A legacy registration (tag 0) needs no translation.
    val legacy = MutationWorld.simpleSpec()
    legacy.certs.add(new StakeRegistration(StakeCredential.fromKeyHash(Array.fill[Byte](28)(1))))
    legacy.changeAdjust = MutationWorld.KEY_DEPOSIT.negate()
    val tx = ScalusTransactions.decode(build(legacy, 9).cbor(), ProtocolVersion(9, 0))
    assertFalse(BootstrapPhaseContexts.applies(tx, 9))
