package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
import io.bullet.borer.Cbor
import org.yanoproject.api.utxo.model.Outpoint
import org.yanoproject.ledger.rules.view.model.UtxoEntry
import scalus.cardano.ledger.{DatumOption, TransactionHash, TransactionInput, TransactionOutput}
import scalus.uplc.builtin.Data

import java.util

/**
 * Converts ledger-view UTxO entries (ADR-056 §3) to Scalus's UTxO map, without the script supplier the legacy
 * path needs: a view entry already carries the reference script bytes, and its inline datum bytes when the
 * source has them.
 *
 * The CCL output is re-encoded and decoded with Scalus's own decoder. The reference script is CCL's
 * on-chain script CBOR, so it is exact; the inline datum comes from [[UtxoEntry.inlineDatumCbor]], which every
 * ledger view supplies (the canonical UTxO store, and `TxEffectsDeriver` for outputs produced in the same block or
 * mempool chain). Without it the datum is CCL's canonical re-encoding, which sorts a map's keys (shorter first), and
 * a script sees a different `Data` (ADR-056 Phase 7c).
 */
object UtxoEntryBridge:

  /** @return the Scalus UTxO map for `entries` */
  def convert(entries: util.Collection[UtxoEntry]): Map[TransactionInput, TransactionOutput] =
    val builder = Map.newBuilder[TransactionInput, TransactionOutput]
    val it = entries.iterator()
    while it.hasNext do
      val entry = it.next()
      builder += input(entry.outpoint()) -> output(entry)
    builder.result()

  def input(outpoint: Outpoint): TransactionInput =
    TransactionInput(TransactionHash.fromHex(outpoint.txHash()), outpoint.index())

  def output(entry: UtxoEntry): TransactionOutput = output(outputBytes(entry), entry.inlineDatumCbor())

  /** @return the entry's output as CBOR (CCL's encoding) */
  def outputBytes(entry: UtxoEntry): Array[Byte] = CborSerializationUtil.serialize(entry.output().serialize())

  /**
   * @param outputCbor  an output's CBOR ([[outputBytes]], possibly narrowed by [[WideIntegers]])
   * @param inlineDatum the inline datum's original bytes, or null to keep the output's own
   */
  def output(outputCbor: Array[Byte], inlineDatum: Array[Byte]): TransactionOutput =
    val decoded = Cbor.decode(outputCbor).to[TransactionOutput].value
    if inlineDatum == null then decoded
    else
      decoded match
        case babbage: TransactionOutput.Babbage =>
          babbage.copy(datumOption = Some(DatumOption.Inline(Data.fromCbor(inlineDatum))))
        case other => other
