package com.bloxbean.cardano.yano.scalusbridge

import com.bloxbean.cardano.client.transaction.spec.{
  Asset,
  MultiAsset,
  Transaction as CclTransaction,
  TransactionBody,
  TransactionInput as CclTransactionInput,
  TransactionOutput as CclTransactionOutput,
  TransactionWitnessSet,
  Value as CclValue
}
import com.bloxbean.cardano.client.plutus.spec.PlutusData
import com.bloxbean.cardano.client.spec.{Era, UnitInterval}
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import scalus.cardano.ledger.{Transaction as ScalusTransaction}

import java.math.BigInteger
import java.util.{HexFormat, List, Set}

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
      .value(new CclValue(
        BigInteger.valueOf(4_800_000L),
        List.of(MultiAsset.builder()
          .policyId("cd" * 28)
          .assets(List.of(Asset.builder()
            .name("scalus-1.1.1")
            .value(BigInteger.valueOf(42L))
            .build()))
          .build())
      ))
      .inlineDatum(PlutusData.unit())
      .build()
    val poolRegistration = PoolRegistration.builder()
      .operator(HexFormat.of().parseHex("11" * 28))
      .vrfKeyHash(HexFormat.of().parseHex("22" * 32))
      .pledge(BigInteger.ZERO)
      .cost(BigInteger.valueOf(170_000_000L))
      .margin(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(20L)))
      .rewardAccount("e1" + "33" * 28)
      .poolOwners(Set.of("11" * 28))
      .relays(List.of())
      .build()
    val original = CclTransaction.builder()
      .era(Era.Conway)
      .body(TransactionBody.builder()
        .inputs(List.of(input))
        .outputs(List.of(output))
        .fee(BigInteger.valueOf(200_000L))
        .ttl(12_345L)
        .certs(List.of(poolRegistration))
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
    assertEquals(1, decoded.body.value.certificates.toSeq.size)
    val decodedOutput = decoded.body.value.outputs.head.value
    assertEquals(42L, decodedOutput.value.assets.assets.values.head.values.head)
    assertTrue(decodedOutput.inlineDatum.isDefined)
