package org.yanoproject.scalusbridge

import org.yanoproject.ledger.rules.TxIdentity
import scalus.cardano.ledger.{KeepRaw, ProtocolVersion, Transaction as ScalusTx}

import scala.util.control.NonFatal

/**
 * Decodes a transaction for Scalus without changing its id.
 *
 * Scalus's decoder rejects indefinite-length containers in the body and its outputs, which Haskell accepts.
 * When the original bytes do not decode, the body is decoded from a definite-length copy
 * ([[DefiniteLengthCbor]]) and given back its original bytes as raw bytes, so `tx.id` (and so signature
 * checks and every script context) still use the transaction id computed from the original body.
 */
object ScalusTransactions:

  def decode(txCbor: Array[Byte], protocolVersion: ProtocolVersion): ScalusTx =
    given ProtocolVersion = protocolVersion
    try ScalusTx.fromCbor(txCbor)
    catch
      case NonFatal(original) =>
        val normalized =
          try DefiniteLengthCbor.normalizeTransaction(txCbor)
          catch case NonFatal(_) => throw original
        val decoded =
          try ScalusTx.fromCbor(normalized)
          catch case NonFatal(_) => throw original
        val bodyBytes = TxIdentity.bodyBytes(txCbor)
        decoded.copy(body = KeepRaw.unsafe(decoded.body.value, bodyBytes))
