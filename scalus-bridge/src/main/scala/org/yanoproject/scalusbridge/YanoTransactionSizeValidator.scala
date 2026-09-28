package org.yanoproject.scalusbridge

import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.{Context, STS, State, TransactionSizeValidator}

/**
 * Sizes a transaction as Haskell does for `MaxTxSizeUTxO` (and the minimum fee).
 *
 * Scalus 1.1.1's `TransactionSizeValidator` re-encodes the whole four-element transaction, `is_valid`
 * included. Haskell's `sizeAlonzoTxF` (`Alonzo/Tx.hs:324-331`, used by Conway, `Conway/Tx.hs:86`) measures
 * `toCBORForSizeComputation` (`Alonzo/Tx.hs:432-443`): a three-element list header, then the body, the witness
 * set and the auxiliary data (or `null`) with their stored bytes, and no `is_valid` flag. A transaction exactly
 * at `maxTxSize` (Amaru scenario 00040) is therefore valid in Haskell and rejected by Scalus.
 *
 * The body, witness set and auxiliary data are `KeepRaw`: their original bytes (for a transaction decoded
 * through [[ScalusTransactions]]' definite-length fallback, the body's raw bytes are restored to the original
 * and the witness set and auxiliary data are never rewritten), so the size is the one of the bytes received.
 */
object YanoTransactionSizeValidator extends STS.Validator:
  override final type Error = TransactionException.InvalidTransactionSizeException

  override def name: String = TransactionSizeValidator.name

  override def validate(context: Context, state: State, tx: Transaction): Result =
    val size = haskellSize(tx)
    val maxTxSize = context.env.params.maxTxSize
    if size <= maxTxSize then success
    else failure(TransactionException.InvalidTransactionSizeException(tx.id, size, maxTxSize))

  /** `toCBORForSizeComputation`: list header (one byte for length 3) + body + witnesses + auxiliary data or null. */
  private[scalusbridge] def haskellSize(tx: Transaction): Int =
    1 + tx.body.raw.length + tx.witnessSetRaw.raw.length + tx.auxiliaryData.fold(1)(_.raw.length)
