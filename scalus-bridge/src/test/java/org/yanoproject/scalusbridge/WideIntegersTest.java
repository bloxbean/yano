package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-056 Phase 7c: narrowing the integers Scalus decodes as a signed long, and what is refused. */
class WideIntegersTest {

    private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final String PLACEHOLDER_0 = "1b4000000000000000"; // 2^62
    private static final String PLACEHOLDER_1 = "1b4000000000000001";

    /** {@code [{0: [], 1: [[addr, [2, {policy: {"": q1, "a": q2}}]]], 2: fee}, witnesses, true, aux]}. */
    private static String tx(String q1, String q2, String fee, String witnesses, String aux) {
        String value = "8202a1581c" + "11".repeat(28) + "a240" + q1 + "4161" + q2;
        return "84a30080018182" + "581d61" + "22".repeat(28) + value + "02" + fee + witnesses + "f5" + aux;
    }

    @Test
    void wideQuantitiesAreNarrowedInPlaceInOrderAndRestored() {
        // 2^64-1 and 2^63 in one output; the fee 7 is the largest other integer.
        byte[] cbor = HexUtil.decodeHexString(tx("1bffffffffffffffff", "1b8000000000000000", "07", "a0", "f6"));

        WideIntegers.Narrowing n = WideIntegers.narrow(cbor, List.of(), List.of());

        assertThat(n.unsupported()).isEmpty();
        // Same length, only the arguments change: 2^63 -> 2^62, 2^64-1 -> 2^62+1 (the order is kept).
        assertThat(HexUtil.encodeHexString(n.txCbor()))
                .isEqualTo(tx(PLACEHOLDER_1, PLACEHOLDER_0, "07", "a0", "f6"));
        assertThat(n.integers()).isEqualTo(Map.of(new BigInteger("4611686018427387904"), TWO_63,
                new BigInteger("4611686018427387905"), MAX));
        assertThat(n.constructors()).isEmpty();
        assertThat(cbor).as("the input is not modified")
                .isEqualTo(HexUtil.decodeHexString(tx("1bffffffffffffffff", "1b8000000000000000", "07", "a0", "f6")));
    }

    @Test
    void placeholdersAreAboveEveryOtherInteger() {
        // A fee of 2^62+5 pushes the placeholders above it.
        byte[] cbor = HexUtil.decodeHexString(tx("1bffffffffffffffff", "01", "1b4000000000000005", "a0", "f6"));

        WideIntegers.Narrowing n = WideIntegers.narrow(cbor, List.of(), List.of());

        assertThat(n.integers()).containsOnlyKeys(new BigInteger("4611686018427387910"));
    }

    @Test
    void nothingWideLeavesTheBytesAlone() {
        byte[] cbor = HexUtil.decodeHexString(tx("01", "02", "07", "a0", "f6"));

        WideIntegers.Narrowing n = WideIntegers.narrow(cbor, List.of(), List.of());

        assertThat(n.txCbor()).isSameAs(cbor);
        assertThat(n.restoresNothing()).isTrue();
    }

    @Test
    void constructorAlternativesOfDatumsAndRedeemersAreNarrowedButNotOfWitnessDatums() {
        String wideConstr = "d866821bffffffffffffffff80"; // Constr (2^64-1) []
        // An inline datum in an output (via a resolved output) and a redeemer's data.
        byte[] output = HexUtil.decodeHexString("a300581d61" + "22".repeat(28) + "0102" + "02" + "8201d8184d" + wideConstr);
        // Witness set {5: [[spend, 0, Constr (2^64-1) [], [1, 2]]]}.
        String redeemers = "a105818400" + "00" + wideConstr + "820102";
        byte[] cbor = HexUtil.decodeHexString(tx("01", "02", "07", redeemers, "f6"));

        WideIntegers.Narrowing n = WideIntegers.narrow(cbor, List.of(output), Arrays.asList((byte[]) null));

        assertThat(n.unsupported()).isEmpty();
        assertThat(n.constructors()).isEqualTo(Map.of(new BigInteger("4611686018427387904"), MAX));
        assertThat(HexUtil.encodeHexString(n.outputs().get(0))).endsWith("d86682" + PLACEHOLDER_0 + "80");
        assertThat(HexUtil.encodeHexString(n.txCbor())).contains("d86682" + PLACEHOLDER_0 + "80");

        // A witness datum's hash is computed from its bytes: refused.
        String datums = "a10481" + wideConstr;
        WideIntegers.Narrowing witness = WideIntegers.narrow(HexUtil.decodeHexString(tx("01", "02", "07", datums, "f6")),
                List.of(), List.of());
        assertThat(witness.unsupported()).hasValueSatisfying(r -> assertThat(r).contains("witness datum"));
    }

    @Test
    void metadataIsNarrowedWithoutRestoring() {
        // Auxiliary data {721: 10^19} and {1: -10^19}.
        byte[] cbor = HexUtil.decodeHexString(tx("01", "02", "07", "a0", "a21902d11b8ac7230489e80000013b8ac7230489e7ffff"));

        WideIntegers.Narrowing n = WideIntegers.narrow(cbor, List.of(), List.of());

        assertThat(n.unsupported()).isEmpty();
        assertThat(n.restoresNothing()).isTrue();
        assertThat(HexUtil.encodeHexString(n.txCbor())).endsWith("a21902d11b7fffffffffffffff013b7fffffffffffffff");
    }

    @Test
    void aWideRationalIsRefused() {
        // Body key 20: [[deposit, account, [0, null, {10: 30([2^63, 2^64-1])}, null], anchor]] (a parameter change).
        String proposal = "818400581de0" + "11".repeat(28) + "8400f6a10ad81e821b8000000000000000"
                + "1bffffffffffffffff" + "f6" + "827168747470733a2f2f612e6578616d706c655820" + "00".repeat(32);
        byte[] cbor = HexUtil.decodeHexString("84a4008001800200" + "14" + proposal + "a0f5f6");
        assertThat(WideIntegers.narrow(cbor, List.of(), List.of()).unsupported())
                .hasValueSatisfying(r -> assertThat(r).contains("tag 30"));
    }

    @Test
    void wideValiditySlotsAndNegativeBodyIntegersAreRefused() {
        byte[] ttl = HexUtil.decodeHexString("84a4008001800200031bffffffffffffffffa0f5f6");
        assertThat(WideIntegers.narrow(ttl, List.of(), List.of()).unsupported())
                .hasValueSatisfying(r -> assertThat(r).contains("validity interval slot 18446744073709551615"));
        byte[] start = HexUtil.decodeHexString("84a4008001800200081b8000000000000000a0f5f6");
        assertThat(WideIntegers.narrow(start, List.of(), List.of()).unsupported()).isPresent();
        // A mint quantity below -2^63 (Haskell's decoder bounds mint to Int64, so no valid transaction has one).
        byte[] mint = HexUtil.decodeHexString("84a400800180020009a1581c" + "11".repeat(28) + "a1403b8000000000000000a0f5f6");
        assertThat(WideIntegers.narrow(mint, List.of(), List.of()).unsupported())
                .hasValueSatisfying(r -> assertThat(r).contains("negative integer"));
    }
}
