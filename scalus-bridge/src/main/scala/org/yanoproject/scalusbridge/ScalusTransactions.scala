package org.yanoproject.scalusbridge

import org.yanoproject.ledger.rules.TxIdentity
import org.yanoproject.ledger.rules.util.DefiniteLengthCbor
import scalus.cardano.ledger.{KeepRaw, ProtocolVersion, Transaction as ScalusTx}

import scala.util.control.NonFatal

/**
 * Decodes a transaction for Scalus without changing its id.
 *
 * Scalus's decoder rejects encodings of the body that Haskell accepts: indefinite-length containers in the body and
 * its outputs, and a set tag (`#6.258`) on an `UpdateCommittee` proposal's removed members (Scalus 1.1.1
 * `GovAction` decoder, GovAction.scala:199-203, reads a plain array; preview transaction `2c3657d0…`, PV 11). When
 * the original bytes do not decode, the body is decoded from a definite-length copy ([[DefiniteLengthCbor]]), then
 * from one without set tags, and given back its original bytes as raw bytes, so `tx.id` (and so signature checks and
 * every script context) still use the transaction id computed from the original body.
 */
object ScalusTransactions:

  def decode(txCbor: Array[Byte], protocolVersion: ProtocolVersion): ScalusTx =
    given ProtocolVersion = protocolVersion
    try ScalusTx.fromCbor(txCbor)
    catch
      case NonFatal(original) =>
        def normalized(dropSetTags: Boolean): Option[ScalusTx] =
          try Some(ScalusTx.fromCbor(DefiniteLengthCbor.normalizeBody(txCbor, dropSetTags)))
          catch case NonFatal(_) => None
        val decoded = normalized(false).orElse(normalized(true)).getOrElse(throw original)
        decoded.copy(body = KeepRaw.unsafe(decoded.body.value, TxIdentity.bodyBytes(txCbor)))
