package org.yanoproject.scalusbridge

import org.yanoproject.ledger.rules.conway.tx.LedgerValue
import scalus.cardano.ledger.*
import scalus.cardano.ledger.Value as ScalusValue
import scalus.cardano.ledger.rules.{Context, OutputsHaveTooBigValueStorageSizeValidator, STS, State}

import java.math.BigInteger
import java.util.{Map as JMap, TreeMap}

/**
 * Sizes an output's value as Haskell does for `OutputTooBigUTxO`.
 *
 * Scalus 1.1.1's `OutputsHaveTooBigValueStorageSizeValidator` measures `Cbor.encode(value)`, whose maps all have
 * definite-length heads. Haskell's `validateOutputTooBigUTxO` (`Alonzo/Rules/Utxo.hs:412-431`) measures
 * `serialize (pvMajor pv) v`, and `encodeMap` writes a map of more than 23 entries indefinite-length
 * (cardano-ledger-binary `Encoder.hs:432-443`), so a policy (or a policy map) of 256 or more entries is one byte
 * smaller. Preprod `96ae78f7…` (a 324-asset policy, exactly `maxValSize` = 5000 bytes) is valid in Haskell and
 * rejected by Scalus (5001). The size is the java engine's, [[LedgerValue.serializedSize]].
 */
object YanoOutputValueSizeValidator extends STS.Validator:
  override final type Error = TransactionException.OutputsHaveTooBigValueStorageSizeException

  override def name: String = OutputsHaveTooBigValueStorageSizeValidator.name

  override def validate(context: Context, state: State, tx: Transaction): Result =
    val maxValueSize = context.env.params.maxValueSize
    val body = tx.body.value
    val invalidOutputs = tooBig(body.outputs, maxValueSize)
    val invalidCollateralReturn = tooBig(body.collateralReturnOutput.toIndexedSeq, maxValueSize).headOption
    if invalidOutputs.isEmpty && invalidCollateralReturn.isEmpty then success
    else
      failure(TransactionException.OutputsHaveTooBigValueStorageSizeException(tx.id, maxValueSize, invalidOutputs,
        invalidCollateralReturn))

  private def tooBig(outputs: IndexedSeq[Sized[TransactionOutput]], maxValueSize: Long)
      : IndexedSeq[(TransactionOutput, Int)] =
    outputs.map(sized => (sized.value, haskellSize(sized.value.value))).filter(_._2 > maxValueSize)

  /**
   * The length of Haskell's encoding of `value` ([[LedgerValue.serializedSize]]).
   *
   * Quantities and the coin are Scala `Long`s here, so a Word64 at or above 2^63 cannot appear: Scalus's decoder
   * refuses it, and where the bridge substitutes a `WideIntegers` placeholder, the placeholder is a positive value
   * of the same encoded length (a 9-byte integer), so the measured size is unchanged.
   */
  private[scalusbridge] def haskellSize(value: ScalusValue): Int =
    val assets = new TreeMap[String, JMap[String, BigInteger]]()
    value.assets.assets.foreach { (policy, names) =>
      val byName = new TreeMap[String, BigInteger]()
      names.foreach((name, quantity) => byName.put(name.bytes.toHex, BigInteger.valueOf(quantity)))
      assets.put(policy.toHex, byName)
    }
    new LedgerValue(BigInteger.valueOf(value.coin.value), assets).serializedSize()
