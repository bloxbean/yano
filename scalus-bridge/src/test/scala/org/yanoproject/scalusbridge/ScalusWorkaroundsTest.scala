package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.address.{AddressProvider, Credential as CclCredential}
import com.bloxbean.cardano.client.plutus.spec.{ConstrPlutusData, PlutusV2Script, ExUnits as CclExUnits, Redeemer as CclRedeemer, RedeemerTag as CclRedeemerTag}
import com.bloxbean.cardano.client.transaction.spec.Withdrawal as CclWithdrawal
import com.bloxbean.cardano.client.util.HexUtil
import io.bullet.borer.Cbor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yanoproject.api.utxo.model.Outpoint
import org.yanoproject.ledger.rules.fixtures.tx.{ConwayTxBuilder, MutationWorld, TxSpec}
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions
import org.yanoproject.ledger.rules.view.model.{Outpoints, UtxoEntry}
import scalus.cardano.address.{Network, StakeAddress, StakePayload}
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.cardano.onchain.plutus.prelude.{List as PList, Option as POption}
import scalus.cardano.onchain.plutus.v3
import scala.collection.immutable.SortedMap
import scalus.uplc.{Constant, DefaultFun, Program, Term}
import scalus.uplc.builtin.{Builtins, ByteString, Data, JVMPlatformSpecific}
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.{CountingBudgetSpender, Log, MachineParams}

import java.math.BigInteger
import java.util
import java.util.Arrays
import scala.jdk.CollectionConverters.*

/**
 * The bridge's workarounds for Scalus 1.1.1 (ADR-056 Phase 7c). Each has a canary: an assertion of Scalus's current
 * behaviour that fails once Scalus follows Haskell, which is the signal to remove the workaround. The chain-valid
 * transactions each one fixes are in `PublicNetworkPhase2Test`.
 */
class ScalusWorkaroundsTest:

  private val two64Minus1 = BigInt("18446744073709551615")

  // ------------------------------------------------------------------ WideIntegers

  @Test
  def canaryScalusDecodesWord64QuantitiesAndConstrAlternativesAsSignedLongs(): Unit =
    // An output {0: addr, 1: [2, {policy: {"": 2^63}}]}.
    val output = HexUtil.decodeHexString("a200581d61" + "22" * 28 + "018202a1581c" + "11" * 28 + "a1401b8000000000000000")
    assertThrows(classOf[Exception], () => Cbor.decode(output).to[TransactionOutput].value)
    assertThrows(classOf[Exception], () => Data.fromCbor(HexUtil.decodeHexString("d866821bffffffffffffffff80")))

  private val input = "81825820" + "00" * 32 + "00"

  @Test
  def canaryScalusDecodesAWord64FeeAndAWideMetadatumAsSignedLongs(): Unit =
    given ProtocolVersion = ProtocolVersion(10, 0)
    // [{0: [input], 1: [], 2: 2^63}, {}, true, null] and [{0: [input], 1: [], 2: 0}, {}, true, {1: 2^63}].
    val wideFee = HexUtil.decodeHexString("84a300" + input + "0180021b8000000000000000a0f5f6")
    val wideMetadatum = HexUtil.decodeHexString("84a300" + input + "01800200a0f5a1011b8000000000000000")
    assertThrows(classOf[Exception], () => ScalusTx.fromCbor(wideFee))
    assertThrows(classOf[Exception], () => ScalusTx.fromCbor(wideMetadatum))

  // ------------------------------------------------------------------ BridgeVM: serialiseData

  @Test
  def canaryScalusSerialisesAWideConstrAlternativeAsASignedLong(): Unit =
    assertEquals("d866822080", Builtins.serialiseData(Data.Constr(two64Minus1, PList.Nil)).toHex)

  @Test
  def serialiseDataWritesAWideConstrAlternativeAsHaskellDoes(): Unit =
    // plutus-core encodeData: tag 102, [encodeWord64 i, fields]; non-empty lists indefinite, bytes chunked by 64.
    val wide = Data.Constr(two64Minus1, PList.from(Seq(Data.I(1), Data.B(ByteString.fromArray(Array.fill(65)(7.toByte))))))
    assertEquals("d866821bffffffffffffffff9f015f5840" + "07" * 64 + "4107ffff", BridgeVM.serialiseData(wide).toHex)
    // Anything else is Scalus's encoding.
    val narrow = Data.Constr(BigInt(200), PList.from(Seq(Data.Map(PList.from(Seq((Data.I(-5), Data.List(PList.Nil))))))))
    assertEquals(Builtins.serialiseData(narrow), BridgeVM.serialiseData(narrow))

  // ------------------------------------------------------------------ BridgeVM: equalsData

  private val ab = Data.Map(PList.from(Seq((Data.I(1), Data.I(1)), (Data.I(2), Data.I(2)))))
  private val ba = Data.Map(PList.from(Seq((Data.I(2), Data.I(2)), (Data.I(1), Data.I(1)))))

  @Test
  def canaryScalusComparesMapsAsSets(): Unit =
    assertTrue(Builtins.equalsData(ab, ba))
    assertTrue(Builtins.equalsData(Data.Map(PList.from(Seq((Data.I(1), Data.I(1)), (Data.I(1), Data.I(1))))),
      Data.Map(PList.from(Seq((Data.I(1), Data.I(1)))))))

  @Test
  def equalsDataComparesMapsInOrderAndWithDuplicates(): Unit =
    val equals = (a: Data, b: Data) => runBuiltin(Term.Apply(Term.Apply(Term.Builtin(DefaultFun.EqualsData),
      Term.Const(Constant.Data(a))), Term.Const(Constant.Data(b))))
    assertEquals(Term.Const(Constant.Bool(false)), equals(ab, ba))
    assertEquals(Term.Const(Constant.Bool(true)), equals(ab, ab))
    val once = Data.Map(PList.from(Seq((Data.I(1), Data.I(1)))))
    val twice = Data.Map(PList.from(Seq((Data.I(1), Data.I(1)), (Data.I(1), Data.I(1)))))
    assertEquals(Term.Const(Constant.Bool(false)), equals(once, twice))
    // Nested inside a constructor and a list, too.
    val wrap = (d: Data) => Data.Constr(3, PList.from(Seq(Data.List(PList.from(Seq(d))))))
    assertEquals(Term.Const(Constant.Bool(false)), equals(wrap(ab), wrap(ba)))
    assertEquals(Term.Const(Constant.Bool(true)), equals(wrap(ab), wrap(ab)))

  private def runBuiltin(term: Term): Term =
    val program = Program((1, 0, 0), term)
    val pv = MajorProtocolVersion(10)
    new BridgeVM(Language.PlutusV2, MachineParams.defaultParamsFor(Language.PlutusV2, pv), pv)
      .evaluateScript(program.deBruijnedProgram, CountingBudgetSpender(), Log())

  // ------------------------------------------------------------------ BridgeVM: verifyEcdsaSecp256k1Signature

  private val generator = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  private val message = "e7f29b2f9885ec92048f11fe9107f7a312f8a92315103cd8604d0145e4230508"

  private def verify(pk: String, sig: String): Term =
    val source = s"(program 1.0.0 [(builtin verifyEcdsaSecp256k1Signature) (con bytestring #$pk) " +
      s"(con bytestring #$message) (con bytestring #$sig)])"
    val program = Program.parseUplc(source).fold(e => throw new IllegalArgumentException(e), identity)
    val pv = MajorProtocolVersion(10)
    val vm = new BridgeVM(Language.PlutusV2, MachineParams.defaultParamsFor(Language.PlutusV2, pv), pv)
    vm.evaluateScript(program.deBruijnedProgram, CountingBudgetSpender(), Log())

  @Test
  def canaryScalusThrowsForAZeroSignatureComponent(): Unit =
    assertThrows(classOf[IllegalArgumentException], () => JVMPlatformSpecific.verifyEcdsaSecp256k1Signature(
      ByteString.fromHex(generator), ByteString.fromHex(message), ByteString.fromHex("00" * 64)))

  @Test
  def aZeroSignatureComponentIsFalseAndAnOverflowStillFails(): Unit =
    // libsecp256k1's parse_compact accepts r = 0 or s = 0 (below the order), and verification fails: False.
    assertEquals(Term.Const(Constant.Bool(false)), verify(generator, "00" * 64))
    assertEquals(Term.Const(Constant.Bool(false)), verify(generator, "00" * 32 + "01" + "00" * 31))
    // r at the group order overflows: parse_compact fails, the builtin fails ("Invalid signature").
    val order = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
    assertThrows(classOf[Exception], () => verify(generator, order + "00" * 32))
    // An invalid key fails first, as in Haskell.
    assertThrows(classOf[Exception], () => verify("05" + "00" * 32, "00" * 64))

  // ------------------------------------------------------------------ V3Constitution

  private val guardrail = ByteString.fromHex("fa24fb305126805cf2164c161d852a0e7330cf988f1fe558cf7d4a64")

  @Test
  def canaryScalusDropsTheConstitutionConstructor(): Unit =
    val action: v3.GovernanceAction = v3.GovernanceAction.NewConstitution(POption.None, POption.Some(guardrail))
    assertEquals(Data.Constr(5, PList.from(Seq(Data.Constr(1, PList.Nil), Data.Constr(0, PList.from(Seq(Data.B(guardrail))))))),
      action.toData)

  // ------------------------------------------------------------------ V3Governance (ScalusContextDifferentialTest)

  private val lowScript = Credential.ScriptHash(ScriptHash.fromHex("05" * 28))
  private val highKey = Credential.KeyHash(AddrKeyHash.fromHex("f0" * 28))

  @Test
  def canaryScalusSortsVotesByTheirText(): Unit =
    val tx = TransactionHash.fromHex("c0" * 32)
    val ballot = VotingProcedure(Vote.Yes, None)
    val votes = LedgerToPlutusTranslation.getVotingProcedures(Some(VotingProcedures(SortedMap(
      Voter.DRepKey(AddrKeyHash.fromHex("01" * 28)) -> SortedMap(GovActionId(tx, 9) -> ballot, GovActionId(tx, 10) -> ballot)))))
    // Haskell: index 9 before 10.
    assertEquals(BigInt(10), votes.toList.head._2.toList.head._1.govActionIx)

  @Test
  def canaryScalusPutsKeyCredentialsFirstInGovernanceMaps(): Unit =
    val account = (c: Credential) => RewardAccount(StakeAddress(Network.Testnet, c match
      case Credential.KeyHash(h) => StakePayload.Stake(StakeKeyHash.fromByteString(h))
      case Credential.ScriptHash(h) => StakePayload.Script(h)))
    val withdrawals = LedgerToPlutusTranslation.getGovernanceActionV3(GovAction.TreasuryWithdrawals(
      Map(account(highKey) -> Coin(5), account(lowScript) -> Coin(6)), None))
    val first = withdrawals match
      case v3.GovernanceAction.TreasuryWithdrawals(entries, _) => entries.toList.head._1
      case other => fail(s"unexpected $other")
    // Haskell: the script credential first (Credential.hs:98-101).
    assertTrue(first.isInstanceOf[scalus.cardano.onchain.plutus.v1.Credential.PubKeyCredential])

  @Test
  def canaryScalusKeepsRationalsUnreduced(): Unit =
    val update = LedgerToPlutusTranslation.getGovernanceActionV3(GovAction.UpdateCommittee(None, Set.empty,
      Map.empty, UnitInterval(6, 2000)))
    update match
      case v3.GovernanceAction.UpdateCommittee(_, _, _, quorum) => assertEquals(BigInt(6), quorum.numerator)
      case other => fail(s"unexpected $other")

  @Test
  def canaryScalusListsRemovedMembersInHashSetOrder(): Unit =
    val members: Seq[Credential] = Seq(0x03, 0x90, 0xa7).map(b => Credential.KeyHash(AddrKeyHash.fromHex(f"$b%02x" * 28))) ++
      Seq(0xd1, 0x05, 0x6e).map(b => Credential.ScriptHash(ScriptHash.fromHex(f"$b%02x" * 28)))
    val update = LedgerToPlutusTranslation.getGovernanceActionV3(GovAction.UpdateCommittee(None, members.toSet,
      Map.empty, UnitInterval(1, 2)))
    val removed = update match
      case v3.GovernanceAction.UpdateCommittee(_, r, _, _) => r.toScalaList.map(_.toData)
      case other => fail(s"unexpected $other")
    // Haskell: Set.toList, script credentials first, then by hash.
    val ledgerOrder = Seq(0x05, 0x6e, 0xd1).map(b => Data.Constr(1, PList.from(Seq(Data.B(ByteString.fromHex(f"$b%02x" * 28)))))) ++
      Seq(0x03, 0x90, 0xa7).map(b => Data.Constr(0, PList.from(Seq(Data.B(ByteString.fromHex(f"$b%02x" * 28))))))
    assertNotEquals(ledgerOrder, removed)

  // ------------------------------------------------------------------ WithdrawalOrder

  @Test
  def canaryScalusOrdersRewardAccountsByHashOnly(): Unit =
    val key = RewardAccount(StakeAddress(Network.Testnet, StakePayload.Stake(StakeKeyHash.fromHex("8a" + "00" * 27))))
    val script = RewardAccount(StakeAddress(Network.Testnet, StakePayload.Script(ScriptHash.fromHex("d1" + "00" * 27))))
    // Haskell: ScriptHashObj before KeyHashObj (Credential.hs:98-101), so the script account comes first.
    assertTrue(summon[Ordering[RewardAccount]].compare(key, script) < 0)

  @Test
  def canaryScalusListsV1V2WithdrawalsByHashOnly(): Unit =
    val key = RewardAccount(StakeAddress(Network.Testnet, StakePayload.Stake(StakeKeyHash.fromHex("d1" + "00" * 27))))
    val script = RewardAccount(StakeAddress(Network.Testnet, StakePayload.Script(ScriptHash.fromHex("8a" + "00" * 27))))
    val listed = LedgerToPlutusTranslation.getWithdrawals(Some(Withdrawals(SortedMap(key -> Coin(1), script -> Coin(2)))))
    // Haskell's V1/V2 txInfoWdrl puts the key credential first (PlutusLedgerApi/V1/Credential.hs:30-37).
    assertEquals(BigInt(2), listed.toScalaList.head._2)

  @Test
  def withdrawalsAndRewardingRedeemersAreInHaskellsOrderPerLanguage(): Unit =
    val pkh = Data.Constr(0, PList.from(Seq(Data.B(ByteString.fromHex("8a" + "00" * 27)))))
    val sh = Data.Constr(1, PList.from(Seq(Data.B(ByteString.fromHex("d1" + "00" * 27)))))
    val staking = (c: Data) => Data.Constr(0, PList.from(Seq(c)))
    val rewarding = (c: Data) => Data.Constr(2, PList.from(Seq(c)))
    val pair = (k: Data) => Data.Constr(0, PList.from(Seq(k, Data.I(0))))
    val spending = Data.Constr(1, PList.from(Seq(Data.I(0))))
    val context = (info: Data) => Data.Constr(0, PList.from(Seq(info, Data.I(0), Data.I(0))))
    // V2/V3: txInfoWdrl is field 6, txInfoRedeemers field 9.
    val txInfo = (wdrl: Seq[(Data, Data)], redeemers: Seq[(Data, Data)]) =>
      Data.Constr(0, PList.from(Seq.fill(6)(Data.I(0)) ++ Seq(Data.Map(PList.from(wdrl)), Data.I(0), Data.I(0),
        Data.Map(PList.from(redeemers)), Data.I(0), Data.I(0))))
    // The redeemers always follow the ledger's order (script first).
    val scalusRedeemers = Seq(spending -> Data.I(1), rewarding(pkh) -> Data.I(2), rewarding(sh) -> Data.I(3))
    val ledgerRedeemers = Seq(spending -> Data.I(1), rewarding(sh) -> Data.I(3), rewarding(pkh) -> Data.I(2))
    // V3 txInfoWdrl: the ledger's Map Credential order (Credential.hs:98-101), script first.
    assertEquals(context(txInfo(Seq(sh -> Data.I(0), pkh -> Data.I(0)), ledgerRedeemers)),
      WithdrawalOrder.fixContext(3, context(txInfo(Seq(pkh -> Data.I(0), sh -> Data.I(0)), scalusRedeemers))))
    // V2 txInfoWdrl: a Map PV1.StakingCredential, whose derived Ord puts PubKeyCredential first
    // (PlutusLedgerApi/V1/Credential.hs:30-37), whatever order Scalus built it in.
    assertEquals(context(txInfo(Seq(staking(pkh) -> Data.I(0), staking(sh) -> Data.I(0)),
        Seq(spending -> Data.I(1), rewarding(staking(sh)) -> Data.I(3), rewarding(staking(pkh)) -> Data.I(2)))),
      WithdrawalOrder.fixContext(2, context(txInfo(Seq(staking(sh) -> Data.I(0), staking(pkh) -> Data.I(0)),
        Seq(spending -> Data.I(1), rewarding(staking(pkh)) -> Data.I(2), rewarding(staking(sh)) -> Data.I(3))))))
    // V1 txInfoWdrl (field 5): a list of pairs, in the same key-first order.
    val v1 = (wdrl: Seq[Data]) => context(Data.Constr(0, PList.from(Seq.fill(5)(Data.I(0)) ++
      Seq(Data.List(PList.from(wdrl))) ++ Seq.fill(4)(Data.I(0)))))
    assertEquals(v1(Seq(pair(staking(pkh)), pair(staking(sh)))),
      WithdrawalOrder.fixContext(1, v1(Seq(pair(staking(sh)), pair(staking(pkh))))))

  /**
   * A PlutusV2 withdrawal script that succeeds only if the first `txInfoWdrl` entry is a `PubKeyCredential`, run over
   * a transaction withdrawing from a key credential whose hash sorts after the script's. Haskell's V2 context lists the
   * key credential first; the ledger's order and a hash order would put the script first.
   */
  @Test
  def aPlutusV2ScriptSeesKeyWithdrawalsFirst(): Unit =
    val tail = (n: Int, list: String) => (1 to n).foldLeft(list)((l, _) => s"[(force (builtin tailList)) $l]")
    val firstKeyKind = "[(force (force (builtin fstPair))) [(builtin unConstrData) [(force (builtin headList)) " +
      "[(force (force (builtin sndPair))) [(builtin unConstrData) [(force (force (builtin fstPair))) " +
      "[(force (builtin headList)) [(builtin unMapData) [(force (builtin headList)) " + tail(6, "txInfoFields") +
      "]]]]]]]]]"
    val source =
      s"""(program 1.0.0
         |  (lam r (lam ctx
         |    [(lam txInfoFields
         |      (force [(force (builtin ifThenElse)) [(builtin equalsInteger) (con integer 0) $firstKeyKind]
         |        (delay (con unit ())) (delay (error))]))
         |     [(force (force (builtin sndPair))) [(builtin unConstrData) [(force (builtin headList))
         |       [(force (force (builtin sndPair))) [(builtin unConstrData) ctx]]]]]])))""".stripMargin
    val program = Program.parseUplc(source).fold(e => throw new IllegalArgumentException(e), identity)
    val script = PlutusV2Script.builder().`type`("PlutusScriptV2").cborHex(program.doubleCborHex).build()
      .asInstanceOf[PlutusV2Script]
    val keyHash = Array.fill[Byte](28)(0xff.toByte)
    assertTrue(Arrays.compareUnsigned(script.getScriptHash, keyHash) < 0)
    val account = (c: CclCredential) => AddressProvider.getRewardAddress(c, MutationWorld.NETWORK).toBech32

    val spec = MutationWorld.simpleSpec()
    spec.withdrawals.add(new CclWithdrawal(account(CclCredential.fromKey(keyHash)), BigInteger.ZERO))
    spec.withdrawals.add(new CclWithdrawal(account(CclCredential.fromScript(script.getScriptHash)), BigInteger.ZERO))
    spec.plutusV2Scripts.add(script)
    // reward[0] is the script: the ledger orders script credentials first.
    spec.redeemers.add(CclRedeemer.builder().tag(CclRedeemerTag.Reward).index(0)
      .data(ConstrPlutusData.of(0))
      .exUnits(CclExUnits.builder().mem(BigInteger.valueOf(2_000_000)).steps(BigInteger.valueOf(2_000_000_000L)).build())
      .build())
    spec.collateral.add(MutationWorld.collateralInput(0))
    val result = evaluate(spec, 10)
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], result, result.toString)

  private def built(spec: TxSpec, protocolMajor: Int) = ConwayTxBuilder.build(spec, MutationWorld.view(protocolMajor))

  private def resolved(spec: TxSpec, protocolMajor: Int): util.Map[Outpoint, UtxoEntry] =
    val view = MutationWorld.view(protocolMajor)
    val body = built(spec, protocolMajor).tx().getBody
    val resolved = new util.LinkedHashMap[Outpoint, UtxoEntry]()
    (body.getInputs.asScala ++ body.getCollateral.asScala).foreach { in =>
      val outpoint = Outpoints.of(in.getTransactionId, in.getIndex)
      view.utxo(outpoint).orElseThrowUnavailable().ifPresent(e => resolved.put(outpoint, e))
    }
    resolved

  private def evaluate(spec: TxSpec, protocolMajor: Int): ScriptPhaseResult =
    val tx = built(spec, protocolMajor)
    new ScalusScriptPhaseEvaluator().evaluate(tx.cbor(), tx.tx(), resolved(spec, protocolMajor),
      MutationWorld.protocolParams(protocolMajor), MutationWorld.env(protocolMajor).slotConfig())

  // ------------------------------------------------------------------ Plutus Core version (ScriptCollection)

  @Test
  def canaryScalusRunsAPlutusCore110V2ScriptBeforeProtocolVersion11(): Unit =
    val spec = MutationWorld.plutusCore110Spec()
    val prepared = ScalusPhaseTwo.prepare(built(spec, 10).cbor(), resolved(spec, 10).values(), 10, 0)
      .fold(e => throw new IllegalStateException(e), identity)
    assertTrue(ScalusPhaseTwo.evaluate(prepared, MutationWorld.protocolParams(10), MutationWorld.env(10).slotConfig())
      .passed)

  /** plcVersionsAvailableIn (Versions.hs:341-357): Plutus Core 1.1.0 for PlutusV1/V2 from protocol version 11. */
  @Test
  def aPlutusCore110V2ScriptFailsBeforeProtocolVersion11(): Unit =
    val spec = MutationWorld.plutusCore110Spec()
    evaluate(spec, 10) match
      case failed: ScriptPhaseResult.Failed =>
        assertTrue(failed.scripts().get(0).error().contains("PlutusCoreLanguageNotAvailableError 1.1.0 PlutusV2"))
      case other => fail(other.toString)
    assertInstanceOf(classOf[ScriptPhaseResult.Passed], evaluate(spec, 11))

  // ------------------------------------------------------------------ set tags (ScalusTransactions)

  @Test
  def canaryScalusRejectsASetTagOnUpdateCommitteeRemovals(): Unit =
    val bundle = PublicNetworkTransactions.PHASE2_CASES.asScala
      .find(_.name == "preview-2c3657d09e194507a4b120c3aeed4616818ad3875382ba68b4e668e6d3d5d625").get.bundle()
    given ProtocolVersion = ProtocolVersion(11, 0)
    assertThrows(classOf[Exception], () => ScalusTx.fromCbor(bundle.txCbor()))
    assertEquals(bundle.txHash(), ScalusTransactions.decode(bundle.txCbor(), ProtocolVersion(11, 0)).id.toHex)

  // ------------------------------------------------------------------ PlutusBinaries

  @Test
  def aV1V2ScriptIsReadFromItsLeadingCborItem(): Unit =
    val program = Program.parseUplc("(program 1.0.0 (con unit ()))").fold(e => throw new IllegalArgumentException(e), identity)
    val binary = program.deBruijnedProgram.cborEncoded :+ 0x00.toByte
    // Canary: Scalus reads the whole binary as one item.
    assertFalse(Script.PlutusV2(ByteString.fromArray(binary)).isWellFormed(MajorProtocolVersion(10)))
    // plutus-ledger-api ignores what follows for V1 and V2 (SerialisedScript.hs:261-264), not for V3.
    assertTrue(ScalusPhaseTwo.isWellFormed(2, binary, 10))
    assertTrue(ScalusPhaseTwo.isWellFormed(1, binary, 10))
    assertFalse(ScalusPhaseTwo.isWellFormed(3, binary, 10))
    assertEquals(program.deBruijnedProgram.term, PlutusBinaries.program(Script.PlutusV2(ByteString.fromArray(binary))).term)

  // ------------------------------------------------------------------ YanoOutputValueSizeValidator

  /** 2 ADA and `count` assets of one policy. */
  private def manyAssets(count: Int): Value =
    val names = (0 until count).map(i => AssetName(ByteString.fromArray(Array((i >> 8).toByte, i.toByte))) -> 1L)
    Value(Coin(2_000_000L), MultiAsset(SortedMap(ScriptHash.fromHex("ab" * 28) -> SortedMap.from(names))))

  @Test
  def canaryScalusMeasuresAValueWithDefiniteLengthMaps(): Unit =
    // Haskell's encodeMap is indefinite-length above 23 entries (Encoder.hs:432-443): with 256 assets its encoding is
    // one byte smaller than Scalus's (OutputsHaveTooBigValueStorageSizeValidator measures Cbor.encode(value)).
    val value = manyAssets(256)
    assertEquals(YanoOutputValueSizeValidator.haskellSize(value) + 1,
      scalus.serialization.cbor.Cbor.encode(value).length)
    // Up to 255 entries a definite head (b8 NN) and the indefinite framing (bf ... ff) are both 2 bytes.
    assertEquals(YanoOutputValueSizeValidator.haskellSize(manyAssets(255)),
      scalus.serialization.cbor.Cbor.encode(manyAssets(255)).length)

  @Test
  def outputValueSizeIsHaskellsForAPreprodValueOfExactlyMaxValSize(): Unit =
    val bundle = PublicNetworkTransactions.bundle(PublicNetworkTransactions.PREPROD_INDEFINITE_ASSET_MAP_OUTPUT)
    val value = ScalusTransactions.decode(bundle.txCbor(), ProtocolVersion(11, 0)).body.value.outputs(1).value.value
    assertEquals(5000, YanoOutputValueSizeValidator.haskellSize(value))
    assertEquals(5001, scalus.serialization.cbor.Cbor.encode(value).length)
