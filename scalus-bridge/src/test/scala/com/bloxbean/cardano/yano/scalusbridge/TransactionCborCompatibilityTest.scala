package com.bloxbean.cardano.yano.scalusbridge

import com.bloxbean.cardano.client.transaction.spec.{
  Transaction as CclTransaction,
  TransactionBody,
  TransactionInput as CclTransactionInput,
  TransactionOutput as CclTransactionOutput,
  TransactionWitnessSet,
  Value as CclValue
}
import com.bloxbean.cardano.client.spec.Era
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals}
import org.junit.jupiter.api.Test
import scalus.cardano.ledger.{Transaction as ScalusTransaction}

import java.math.BigInteger
import java.util.List

class TransactionCborCompatibilityTest:
  @Test
  def cclTransactionRoundTripsThroughScalusCborCodec(): Unit =
    val input = CclTransactionInput.builder()
      .transactionId("ab" * 32)
      .index(1)
      .build()
    val output = CclTransactionOutput.builder()
      .address(
        "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp"
      )
      .value(new CclValue(BigInteger.valueOf(4_800_000L), null))
      .build()
    val original = CclTransaction.builder()
      .era(Era.Conway)
      .body(TransactionBody.builder()
        .inputs(List.of(input))
        .outputs(List.of(output))
        .fee(BigInteger.valueOf(200_000L))
        .ttl(12_345L)
        .build())
      .witnessSet(new TransactionWitnessSet())
      .isValid(true)
      .build()
      .serialize()

    val decoded = ScalusTransaction.fromCbor(original)
    val reencoded = decoded.toCbor
    val roundTripped = ScalusTransaction.fromCbor(reencoded)

    assertArrayEquals(original, reencoded)
    assertEquals(decoded.id, roundTripped.id)
    assertEquals(decoded.body.value, roundTripped.body.value)
