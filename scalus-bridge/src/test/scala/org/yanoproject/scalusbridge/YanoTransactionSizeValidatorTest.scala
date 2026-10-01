package org.yanoproject.scalusbridge

import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata
import com.bloxbean.cardano.client.transaction.spec.{AuxiliaryData, Transaction as CclTransaction, TransactionBody,
  TransactionInput, TransactionOutput, TransactionWitnessSet, Value}
import org.junit.jupiter.api.Assertions.{assertEquals, assertSame}
import org.junit.jupiter.api.Test
import scalus.cardano.ledger.Transaction as ScalusTx
import scalus.cardano.ledger.rules.TransactionSizeValidator

import java.math.BigInteger
import java.util.List as JList

/**
 * Haskell sizes a transaction without its `is_valid` flag (`Alonzo/Tx.hs:432-443`,
 * `toCBORForSizeComputation`): for a definite-length four-element transaction that is one byte less than the
 * encoding. Amaru scenario 00040 (a transaction exactly at `maxTxSize`) runs through the engine in
 * `ScalusEngineCorpusFixesTest`.
 */
class YanoTransactionSizeValidatorTest:

  private def cclTx(withMetadata: Boolean): Array[Byte] =
    val body = TransactionBody.builder()
      .inputs(JList.of(new TransactionInput("aa" * 32, 0)))
      .outputs(JList.of(TransactionOutput.builder()
        .address("addr_test1vzpwq95z3xyum8vqndgdd9mdnmafh3djcxnc6jemlgdmswcve6tkw")
        .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build())
        .build()))
      .fee(BigInteger.valueOf(200_000))
      .build()
    val tx = CclTransaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true)
    if withMetadata then
      tx.auxiliaryData(AuxiliaryData.builder().metadata(new CBORMetadata().put(BigInteger.valueOf(674), "size")).build())
    tx.build().serialize()

  @Test
  def sizeIsTheEncodingWithoutTheIsValidByte(): Unit =
    for withMetadata <- Seq(false, true) do
      val bytes = cclTx(withMetadata)
      assertEquals(0x84, bytes(0) & 0xff)
      assertEquals(bytes.length - 1, YanoTransactionSizeValidator.haskellSize(ScalusTx.fromCbor(bytes)))

  @Test
  def replacesScalusSizeValidatorUnderItsName(): Unit =
    assertEquals(TransactionSizeValidator.name, YanoTransactionSizeValidator.name)
    assertSame(YanoTransactionSizeValidator,
      YanoCardanoMutator.validators.find(_.name == TransactionSizeValidator.name).orNull)
