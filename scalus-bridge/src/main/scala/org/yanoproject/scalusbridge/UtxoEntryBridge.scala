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
 * on-chain script CBOR, so it is exact; the inline datum comes from [[UtxoEntry.inlineDatumCbor]] when
 * present (Plutus sees a datum as `Data`, so a re-encoding would only matter for its bytes).
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

  def output(entry: UtxoEntry): TransactionOutput =
    val bytes = CborSerializationUtil.serialize(entry.output().serialize())
    val decoded = Cbor.decode(bytes).to[TransactionOutput].value
    val inlineDatum = entry.inlineDatumCbor()
    if inlineDatum == null then decoded
    else
      decoded match
        case babbage: TransactionOutput.Babbage =>
          babbage.copy(datumOption = Some(DatumOption.Inline(Data.fromCbor(inlineDatum))))
        case other => other
