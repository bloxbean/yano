package org.yanoproject.scalusbridge

import scalus.cardano.ledger.{Language, MajorProtocolVersion}
import scalus.crypto.{NativeSecp256k1, Secp256k1Context}
import scalus.uplc.{BuiltinRuntime, BuiltinSemanticsVariant, CardanoBuiltins, Constant, DeBruijn, DeBruijnedProgram, DefaultFun, Term}
import scalus.uplc.builtin.{platform, Builtins, ByteString, Data}
import scalus.uplc.eval.{BudgetSpender, CekMachine, CekValue, DeserializationError, InvalidReturnValue, Logger, MachineParams}

import java.io.ByteArrayOutputStream

/**
 * The CEK machine of one Plutus language at one protocol version, as Scalus's `PlutusVM` builds it (same machine
 * parameters, builtin semantics variant, case-on-builtins switch and result check), with three builtins made to
 * follow plutus-core 1.65.0.0 where Scalus 1.1.1 does not (ADR-056 Phase 7c; `ScalusWorkaroundsTest`'s canaries fail
 * when Scalus fixes one):
 *
 *  - `equalsData` is the derived `Eq` of `Data` (plutus-core PlutusCore/Data.hs:42-48, the denotation at
 *    PlutusCore/Default/Builtins.hs:1835-1841): two `Map`s are equal only with the same pairs in the same order.
 *    Scalus's `equalsData` is `==` (Builtins.scala:637), and `Data.Map.equals` compares the pairs as sets
 *    (Data.scala:162-168), ignoring order and duplicate keys, so a script comparing two such maps could succeed in
 *    Scalus and fail on chain.
 *
 *  - `serialiseData` of a `Constr` whose alternative is at least 2^63 writes it as `encodeWord64` (Data.hs:150-160).
 *    Scalus's encoder writes `constr.toLong` (DataApi.scala:115-120), so `Constr (2^64-1) []` serialises as
 *    `d866822080` instead of `d866821bffffffffffffffff80` (preprod transaction `2edd684f…`). Every other `Data`
 *    keeps Scalus's encoder, which matches Haskell's.
 *
 *  - `verifyEcdsaSecp256k1Signature` returns `False` for a signature whose `r` or `s` is zero. Haskell parses the
 *    signature with libsecp256k1's `secp256k1_ecdsa_signature_parse_compact` (cardano-crypto-class
 *    `EcdsaSecp256k1DSIGN.rawDeserialiseSigDSIGN`), which rejects only `r` or `s` at or above the group order, and
 *    `secp256k1_ecdsa_verify` then fails, so the builtin is `False` (plutus-core PlutusCore/Crypto/Secp256k1.hs:48-57).
 *    Scalus's `JVMPlatformSpecific.verifyEcdsaSecp256k1Signature` requires `0 < r, s < n` and throws ("Invalid
 *    signature: r out of range"), failing the script (preprod, PV 10: 61 chain-valid spends of script `9dd6dd04…`
 *    that check an all-zero signature). Every other input keeps Scalus's implementation.
 *
 * Costs are Scalus's own costing functions, unchanged.
 */
final class BridgeVM(language: Language, params: MachineParams, protocolVersion: MajorProtocolVersion):

  private val semanticVariant = BuiltinSemanticsVariant.fromProtocolAndPlutusVersion(protocolVersion, language)
  private val builtins = new CardanoBuiltins(params.builtinCostModel, platform, semanticVariant)
  private val caseOnBuiltins = protocolVersion >= MajorProtocolVersion.vanRossemPV || language == Language.PlutusV4

  private val equalsData = builtins.EqualsData.copy(f = (_: Logger, args: Seq[CekValue]) =>
    CekValue.VCon(Constant.Bool(BridgeVM.equalsData(dataArg(DefaultFun.EqualsData, args(0)),
      dataArg(DefaultFun.EqualsData, args(1))))))

  private val serialiseData = builtins.SerialiseData.copy(f = (_: Logger, args: Seq[CekValue]) =>
    CekValue.VCon(Constant.ByteString(BridgeVM.serialiseData(dataArg(DefaultFun.SerialiseData, args(0))))))

  private val verifyEcdsa = builtins.VerifyEcdsaSecp256k1Signature.copy(f = (logger: Logger, args: Seq[CekValue]) =>
    val sig = bytesArg(DefaultFun.VerifyEcdsaSecp256k1Signature, args(2))
    if BridgeVM.zeroComponentBelowOrder(sig.bytes) then
      val pk = bytesArg(DefaultFun.VerifyEcdsaSecp256k1Signature, args(0))
      val msg = bytesArg(DefaultFun.VerifyEcdsaSecp256k1Signature, args(1))
      // Secp256k1Context loads the native library, as Scalus's own implementation does first.
      require(Secp256k1Context.isEnabled, "secp256k1 native library not available")
      if pk.size != 33 || !NativeSecp256k1.isValidPubKey(pk.bytes) then
        throw new IllegalArgumentException(s"Invalid verification key ${pk.toHex}")
      if msg.size != 32 then throw new IllegalArgumentException(s"Invalid message hash length ${msg.size}")
      CekValue.VCon(Constant.Bool(false))
    else builtins.VerifyEcdsaSecp256k1Signature.f(logger, args))

  private def bytesArg(fun: DefaultFun, value: CekValue): ByteString = value match
    case CekValue.VCon(Constant.ByteString(bytes)) => bytes
    case _ => throw new DeserializationError(fun, value)

  private def dataArg(fun: DefaultFun, value: CekValue): Data = value match
    case CekValue.VCon(Constant.Data(data)) => data
    case _ => throw new DeserializationError(fun, value)

  private def builtin(fun: DefaultFun): BuiltinRuntime = fun match
    case DefaultFun.EqualsData => equalsData
    case DefaultFun.SerialiseData => serialiseData
    case DefaultFun.VerifyEcdsaSecp256k1Signature => verifyEcdsa
    case other => builtins.getBuiltinRuntime(other)

  /** Scalus's `PlutusVM.evaluateScript`: evaluates, then V3 must return unit. */
  def evaluateScript(program: DeBruijnedProgram, budgetSpender: BudgetSpender, logger: Logger): Term =
    val cek = new CekMachine(params, budgetSpender, logger, builtin, caseOnBuiltins)
    val result = DeBruijn.fromDeBruijnTerm(cek.evaluateTerm(program.term))
    val valid = (language, result) match
      case (Language.PlutusV1 | Language.PlutusV2, _) => true
      case (Language.PlutusV3 | Language.PlutusV4, Term.Const(Constant.Unit, _)) => true
      case _ => false
    if valid then result else throw new InvalidReturnValue(result)

object BridgeVM:

  private val TWO_63 = BigInt(1) << 63
  private val SECP256K1_ORDER = BigInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

  /**
   * @return true for a 64-byte compact signature that libsecp256k1 parses (both components below the group order)
   *         and that has a zero component, which no verification accepts
   */
  def zeroComponentBelowOrder(sig: Array[Byte]): Boolean =
    if sig.length != 64 then false
    else
      val r = BigInt(1, sig.slice(0, 32))
      val s = BigInt(1, sig.slice(32, 64))
      (r == 0 || s == 0) && r < SECP256K1_ORDER && s < SECP256K1_ORDER

  /** Haskell's derived `Eq Data`: every constructor, list and map compared element by element, in order. */
  def equalsData(a: Data, b: Data): Boolean = (a, b) match
    case (Data.Constr(i, xs), Data.Constr(j, ys)) => i == j && equalLists(xs.toScalaList, ys.toScalaList)
    case (Data.Map(xs), Data.Map(ys)) =>
      val l = xs.toScalaList
      val r = ys.toScalaList
      l.size == r.size && l.lazyZip(r).forall { case ((k1, v1), (k2, v2)) => equalsData(k1, k2) && equalsData(v1, v2) }
    case (Data.List(xs), Data.List(ys)) => equalLists(xs.toScalaList, ys.toScalaList)
    case (Data.I(x), Data.I(y)) => x == y
    case (Data.B(x), Data.B(y)) => x == y
    case _ => false

  private def equalLists(xs: scala.List[Data], ys: scala.List[Data]): Boolean =
    xs.size == ys.size && xs.lazyZip(ys).forall(equalsData)

  /** `serialiseData`: Scalus's encoder, except for a `Constr` alternative Scalus writes wrongly. */
  def serialiseData(data: Data): ByteString =
    if hasWideConstr(data) then ByteString.fromArray(HaskellDataEncoder.encode(data))
    else Builtins.serialiseData(data)

  private def hasWideConstr(data: Data): Boolean = data match
    case Data.Constr(i, xs) => i >= TWO_63 || xs.toScalaList.exists(hasWideConstr)
    case Data.Map(xs) => xs.toScalaList.exists { case (k, v) => hasWideConstr(k) || hasWideConstr(v) }
    case Data.List(xs) => xs.toScalaList.exists(hasWideConstr)
    case _ => false

  /** plutus-core `encodeData` (PlutusCore/Data.hs:147-199), byte for byte. */
  private object HaskellDataEncoder:

    private val WORD64_MAX = (BigInt(1) << 64) - 1

    def encode(data: Data): Array[Byte] =
      val out = new ByteArrayOutputStream()
      write(out, data)
      out.toByteArray

    private def write(out: ByteArrayOutputStream, data: Data): Unit = data match
      case Data.Constr(i, xs) if i >= 0 && i < 7 =>
        head(out, 6, BigInt(121) + i)
        list(out, xs.toScalaList)
      case Data.Constr(i, xs) if i >= 7 && i < 128 =>
        head(out, 6, BigInt(1280) + i - 7)
        list(out, xs.toScalaList)
      case Data.Constr(i, xs) =>
        head(out, 6, 102)
        head(out, 4, 2)
        if i <= WORD64_MAX then head(out, 0, i) else integer(out, i)
        list(out, xs.toScalaList)
      case Data.Map(entries) =>
        val pairs = entries.toScalaList
        head(out, 5, pairs.size)
        pairs.foreach { case (k, v) => write(out, k); write(out, v) }
      case Data.List(xs) => list(out, xs.toScalaList)
      case Data.I(i) => integer(out, i)
      case Data.B(b) => bytes(out, b.bytes)

    /** cborg's `encode` of a list: definite empty, indefinite otherwise. */
    private def list(out: ByteArrayOutputStream, xs: scala.List[Data]): Unit =
      if xs.isEmpty then head(out, 4, 0)
      else
        out.write(0x9f)
        xs.foreach(write(out, _))
        out.write(0xff)

    private def integer(out: ByteArrayOutputStream, i: BigInt): Unit =
      if i >= 0 && i <= WORD64_MAX then head(out, 0, i)
      else if i < 0 && i >= -WORD64_MAX - 1 then head(out, 1, -1 - i)
      else if i >= 0 then
        head(out, 6, 2)
        bytes(out, magnitude(i))
      else
        head(out, 6, 3)
        bytes(out, magnitude(-1 - i))

    private def magnitude(i: BigInt): Array[Byte] =
      val raw = i.bigInteger.toByteArray
      if raw.length > 1 && raw(0) == 0 then raw.drop(1) else raw

    private def bytes(out: ByteArrayOutputStream, b: Array[Byte]): Unit =
      if b.length <= 64 then
        head(out, 2, b.length)
        out.write(b)
      else
        out.write(0x5f)
        b.grouped(64).foreach { chunk =>
          head(out, 2, chunk.length)
          out.write(chunk)
        }
        out.write(0xff)

    private def head(out: ByteArrayOutputStream, major: Int, value: BigInt): Unit =
      val m = major << 5
      if value < 24 then out.write(m | value.toInt)
      else
        val size = if value < 0x100 then 1 else if value < 0x10000 then 2 else if value < BigInt(1L << 32) then 4 else 8
        out.write(m | (size match { case 1 => 24; case 2 => 25; case 4 => 26; case _ => 27 }))
        val raw = value.bigInteger
        for shift <- (size - 1) to 0 by -1 do
          out.write(raw.shiftRight(8 * shift).intValue() & 0xff)
