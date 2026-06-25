package com.bloxbean.cardano.yano.testkit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoAdaPotComparatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsesYanoAdaPotJson() throws Exception {
        var json = MAPPER.readTree("""
                {
                  "epoch": 10,
                  "treasury": "1000",
                  "reserves": "2000",
                  "deposits": "3000",
                  "fees": "4000",
                  "distributed_rewards": "5000",
                  "undistributed_rewards": "6000",
                  "rewards_pot": "7000",
                  "pool_rewards_pot": "8000"
                }
                """);

        var snapshot = YanoAdaPotComparator.fromJson(json);

        assertEquals(10, snapshot.epoch());
        assertEquals(new BigInteger("1000"), snapshot.treasury());
        assertEquals(new BigInteger("2000"), snapshot.reserves());
        assertEquals(new BigInteger("8000"), snapshot.poolRewardsPot());
    }

    @Test
    void readsPipeSeparatedTreasuryReserveReference() throws Exception {
        Path reference = tempDir.resolve("adapot.csv");
        Files.writeString(reference, """
                # epoch|treasury|reserves
                1|100|200
                2,300,400
                """);

        Map<Integer, YanoAdaPotComparator.AdaPotSnapshot> snapshots =
                YanoAdaPotComparator.readTreasuryReserveReference(reference);

        assertEquals(new BigInteger("100"), snapshots.get(1).treasury());
        assertEquals(new BigInteger("400"), snapshots.get(2).reserves());
    }

    @Test
    void malformedReferenceNumbersIncludeFileAndLineContext() throws Exception {
        Path reference = tempDir.resolve("bad-adapot.csv");
        Files.writeString(reference, "1|not-a-number|200\n");

        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> YanoAdaPotComparator.readTreasuryReserveReference(reference));

        assertTrue(error.getMessage().contains("line 1"));
        assertTrue(error.getMessage().contains(reference.toString()));
    }

    @Test
    void comparesTreasuryAndReserves() {
        Map<Integer, YanoAdaPotComparator.AdaPotSnapshot> actual = Map.of(
                1, YanoAdaPotComparator.AdaPotSnapshot.treasuryReserves(
                        1, BigInteger.TEN, BigInteger.ONE));
        Map<Integer, YanoAdaPotComparator.AdaPotSnapshot> reference = Map.of(
                1, YanoAdaPotComparator.AdaPotSnapshot.treasuryReserves(
                        1, BigInteger.TEN, BigInteger.ONE));

        var result = YanoAdaPotComparator.compareTreasuryAndReserves(actual, reference);

        assertTrue(result.matches());
        assertEquals(1, result.checked());
    }

    @Test
    void reportsTreasuryAndReserveMismatches() {
        Map<Integer, YanoAdaPotComparator.AdaPotSnapshot> actual = Map.of(
                1, YanoAdaPotComparator.AdaPotSnapshot.treasuryReserves(
                        1, BigInteger.TEN, BigInteger.ONE));
        Map<Integer, YanoAdaPotComparator.AdaPotSnapshot> reference = Map.of(
                1, YanoAdaPotComparator.AdaPotSnapshot.treasuryReserves(
                        1, BigInteger.valueOf(11), BigInteger.valueOf(2)));

        AssertionError error = assertThrows(AssertionError.class,
                () -> YanoAdaPotComparator.assertTreasuryAndReservesMatch(actual, reference));

        assertTrue(error.getMessage().contains("epoch 1"));
        assertTrue(error.getMessage().contains("expected treasury=11"));
    }
}
