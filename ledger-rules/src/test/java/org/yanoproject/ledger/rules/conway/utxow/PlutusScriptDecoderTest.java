package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plutus-ledger-api 1.65.0.0 {@code deserialiseScript} (the phase-1 {@code isValidPlutusScript}): the CBOR envelope
 * and PlutusV3 remainder rule, flat framing, {@code constr}/{@code case} and program versions, builtin availability
 * per language and protocol version, constants (types, kinds, sizes, values, {@code Data}, BLS, {@code Value}).
 */
class PlutusScriptDecoderTest {

    // ------------------------------------------------------------------ a small flat encoder

    /** Most-significant-bit-first flat writer. */
    private static final class Flat {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        Flat bits(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                bit(((value >>> i) & 1) == 1);
            }
            return this;
        }

        Flat bit(boolean b) {
            current = (current << 1) | (b ? 1 : 0);
            if (++used == 8) {
                out.write(current);
                current = 0;
                used = 0;
            }
            return this;
        }

        /** A natural as 7-bit groups, least significant first, each in 8 bits. */
        Flat natural(long n) {
            do {
                int group = (int) (n & 0x7f);
                n >>>= 7;
                bits(n != 0 ? group | 0x80 : group, 8);
            } while (n != 0);
            return this;
        }

        Flat integer(long i) {
            return natural(i >= 0 ? i << 1 : ((-i) << 1) - 1);
        }

        Flat filler() {
            while (used != 7) {
                bit(false);
            }
            return bit(true);
        }

        Flat bytes(byte[] b) {
            filler();
            for (int off = 0; off < b.length; off += 255) {
                int n = Math.min(255, b.length - off);
                out.write(n);
                out.write(b, off, n);
            }
            out.write(0);
            return this;
        }

        byte[] finish() {
            filler();
            return out.toByteArray();
        }
    }

    private static Flat program(int major, int minor) {
        return new Flat().natural(major).natural(minor).natural(0);
    }

    /** @return the PlutusBinary: the flat bytes as a CBOR byte string */
    private static byte[] cbor(byte[] flat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (flat.length < 24) {
            out.write(0x40 | flat.length);
        } else if (flat.length < 256) {
            out.write(0x58);
            out.write(flat.length);
        } else if (flat.length < 0x10000) {
            out.write(0x59);
            out.write(flat.length >> 8);
            out.write(flat.length);
        } else {
            out.write(0x5a);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write(flat.length >> shift);
            }
        }
        out.writeBytes(flat);
        return out.toByteArray();
    }

    private static Flat lam(Flat f) {
        return f.bits(2, 4);
    }

    private static Flat unitConstant(Flat f) {
        return f.bits(4, 4).bit(true).bits(3, 4).bit(false);
    }

    private static boolean ok(int language, byte[] script, int pv) {
        return PlutusScriptDecoder.isWellFormed(language, script, pv);
    }

    // ------------------------------------------------------------------ envelope and framing

    @Test
    void theAlwaysSucceedsScriptsAndRealValidatorsDecode() throws Exception {
        assertThat(ok(3, HexUtil.decodeHexString("450101002499"), 10)).isTrue();
        byte[] mine = cbor(unitConstant(lam(program(1, 1))).finish());
        assertThat(HexUtil.encodeHexString(mine)).isEqualTo("450101002499");
        // Amaru's corpus PlutusV2 always-succeeds script (three lambdas and a unit)
        assertThat(ok(2, HexUtil.decodeHexString("46010000222499"), 10)).isTrue();
        List<String> real = realScripts();
        assertThat(real).hasSize(3);
        for (String hex : real) {
            assertThat(PlutusScriptDecoder.check(3, HexUtil.decodeHexString(hex), 10)).as(hex.substring(0, 20))
                    .isEmpty();
        }
    }

    @Test
    void onlyPlutusV3RejectsBytesAfterTheCborByteString() {
        byte[] trailing = HexUtil.decodeHexString("45010100249900");
        assertThat(PlutusScriptDecoder.check(3, trailing, 10)).get().asString().contains("RemainderError");
        byte[] v2trailing = HexUtil.decodeHexString("4601000022249900");
        assertThat(ok(2, v2trailing, 10)).as("PlutusV1/V2 allow a remainder").isTrue();
        assertThat(ok(3, HexUtil.decodeHexString("5f450101002499ff"), 10)).as("indefinite byte string").isFalse();
        assertThat(ok(3, HexUtil.decodeHexString("01020304"), 10)).isFalse();
    }

    @Test
    void theFlatProgramMustEndWithAFillerAndNothingElse() {
        byte[] good = unitConstant(lam(program(1, 1))).finish();
        byte[] extra = new byte[good.length + 1];
        System.arraycopy(good, 0, extra, 0, good.length);
        assertThat(PlutusScriptDecoder.check(3, cbor(extra), 10)).get().asString().contains("TooMuchSpace");
        // a filler may run past the next byte boundary: ...1001100 0, then 0000000 1
        assertThat(ok(3, cbor(HexUtil.decodeHexString("010100249801")), 10)).isTrue();
        assertThat(ok(3, cbor(HexUtil.decodeHexString("0101002498")), 10)).as("a filler without its one bit")
                .isFalse();
    }

    // ------------------------------------------------------------------ versions and term constructors

    /**
     * {@code constr} and {@code case} need program version 1.1.0; the program version itself is not checked against
     * the language here ({@code plcVersionsAvailableIn} is a phase-2 check, Eval.hs:118-122).
     */
    @Test
    void constrAndCaseNeedVersion110ButThePlutusCoreVersionIsAPhaseTwoCheck() {
        byte[] constr110 = cbor(lam(program(1, 1)).bits(8, 4).natural(0).bit(false).finish());
        byte[] constr100 = cbor(lam(program(1, 0)).bits(8, 4).natural(0).bit(false).finish());
        assertThat(ok(3, constr110, 10)).isTrue();
        assertThat(PlutusScriptDecoder.check(3, constr100, 10)).get().asString().contains("before version 1.1.0");
        byte[] case100 = cbor(unitConstant(lam(program(1, 0)).bits(9, 4)).bit(false).finish());
        assertThat(ok(3, case100, 10)).isFalse();
        assertThat(ok(1, HexUtil.decodeHexString("450101002499"), 10))
                .as("a 1.1.0 program as PlutusV1 decodes; running it is what fails").isTrue();
    }

    /** From protocol version 11 a {@code constr} may have at most 1024 fields. */
    @Test
    void constrFieldsAreBoundedFromProtocolVersion11() {
        Flat f = lam(program(1, 1)).bits(8, 4).natural(0);
        for (int i = 0; i < 1025; i++) {
            f.bit(true).bits(6, 4);
        }
        byte[] wide = cbor(f.bit(false).finish());
        assertThat(ok(3, wide, 10)).isTrue();
        assertThat(ok(3, wide, 11)).isFalse();
    }

    @Test
    void deeplyNestedTermsDecodeWithoutRecursion() {
        Flat f = program(1, 1);
        for (int i = 0; i < 200_000; i++) {
            f.bits(1, 4); // delay
        }
        assertThat(ok(3, cbor(f.bits(6, 4).finish()), 10)).isTrue();
    }

    // ------------------------------------------------------------------ builtins

    private static byte[] builtin(int tag) {
        return cbor(lam(program(1, 0)).bits(7, 4).bits(tag, 7).finish());
    }

    /** {@code builtinsAvailableIn} (Versions.hs:320-330). */
    @Test
    void builtinsFollowTheLanguageAndProtocolVersion() {
        // PlutusV1: batch 1 (0-50) until 11, then everything
        assertThat(ok(1, builtin(50), 10)).isTrue();
        assertThat(ok(1, builtin(51), 10)).as("serialiseData in PlutusV1 before 11").isFalse();
        assertThat(ok(1, builtin(51), 11)).isTrue();
        // PlutusV2: 0-53 and, from 10, integerToByteString/byteStringToInteger; the BLS batch only from 11
        assertThat(ok(2, builtin(53), 9)).isTrue();
        assertThat(ok(2, builtin(73), 9)).isFalse();
        assertThat(ok(2, builtin(73), 10)).isTrue();
        assertThat(ok(2, builtin(54), 10)).isFalse();
        assertThat(ok(2, builtin(54), 11)).isTrue();
        // PlutusV3: 0-74 from 9, the bitwise batch (75-86) from 10, batch 6 (87-100) from 11
        assertThat(ok(3, builtin(74), 9)).isTrue();
        assertThat(ok(3, builtin(75), 9)).isFalse();
        assertThat(ok(3, builtin(86), 10)).isTrue();
        assertThat(ok(3, builtin(87), 10)).as("expModInteger before 11").isFalse();
        assertThat(ok(3, builtin(100), 11)).isTrue();
        assertThat(ok(3, builtin(101), 11)).as("no builtin 101").isFalse();
    }

    // ------------------------------------------------------------------ constants

    private static Flat constant(int... tags) {
        Flat f = lam(program(1, 1)).bits(4, 4);
        for (int tag : tags) {
            f.bit(true).bits(tag, 4);
        }
        return f.bit(false);
    }

    @Test
    void constantValuesDecodeByType() {
        assertThat(ok(3, cbor(constant(0).integer(-123456789).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(1).bytes(new byte[300]).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(2).bytes("héllo".getBytes(StandardCharsets.UTF_8)).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(2).bytes(new byte[]{(byte) 0xc3, 0x28}).finish()), 10))
                .as("invalid UTF-8").isFalse();
        assertThat(ok(3, cbor(constant(4).bit(true).finish()), 10)).isTrue();
        // list integer [1, 2]: 7 5 0
        assertThat(ok(3, cbor(constant(7, 5, 0).bit(true).integer(1).bit(true).integer(2).bit(false).finish()), 10))
                .isTrue();
        // pair bool unit: 7 7 6 4 3
        assertThat(ok(3, cbor(constant(7, 7, 6, 4, 3).bit(false).finish()), 10)).isTrue();
        // array bytestring: 7 12 1
        assertThat(ok(3, cbor(constant(7, 12, 1).bit(true).bytes(new byte[]{1}).bit(false).finish()), 10)).isTrue();
    }

    @Test
    void illKindedOrUnknownTypesAndBlsConstantsDoNotDecode() {
        assertThat(ok(3, cbor(constant(5).finish()), 10)).as("list without an argument").isFalse();
        assertThat(ok(3, cbor(constant(7, 0, 0).finish()), 10)).as("integer applied to integer").isFalse();
        assertThat(ok(3, cbor(constant(7, 5, 5).finish()), 10)).as("list of a type operator").isFalse();
        assertThat(ok(3, cbor(constant(14).finish()), 10)).as("unknown tag").isFalse();
        assertThat(ok(3, cbor(constant(0, 0).integer(1).finish()), 10)).as("left-over type tags").isFalse();
        assertThat(PlutusScriptDecoder.check(3, cbor(constant(9).bytes(new byte[48]).finish()), 10)).get()
                .asString().contains("BLS12-381");
    }

    /** From protocol version 11 a constant's type may have at most 32 nodes ({@code defaultUniSize}). */
    @Test
    void constantTypeSizeIsBoundedFromProtocolVersion11() {
        // list (list (... (list integer))) with 16 lists: 16 applications, 16 operators, 1 leaf = 33 nodes
        int[] tags = new int[33];
        for (int i = 0; i < 16; i++) {
            tags[2 * i] = 7;
            tags[2 * i + 1] = 5;
        }
        tags[32] = 0;
        byte[] deep = cbor(constant(tags).bit(false).finish());
        assertThat(ok(3, deep, 10)).isTrue();
        assertThat(ok(3, deep, 11)).isFalse();
    }

    @Test
    void dataConstantsFollowPlutusDataDecoding() {
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d8799f0102ff")).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d9050080")).finish()), 10))
                .as("constructor 7 (tag 1280)").isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d86682071880")).finish()), 10))
                .as("tag 102 needs a list index then fields").isFalse();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d866820780")).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("c249010000000000000000")).finish()), 10))
                .as("bignum").isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d80249010000000000000000")).finish()), 10))
                .as("a two-byte tag head is not a bignum to cborg").isFalse();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("5841" + "00".repeat(65))).finish()), 10))
                .as("byte string over 64 bytes").isFalse();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("5f5840" + "00".repeat(64) + "4100ff"))
                .finish()), 10)).as("chunks of at most 64 bytes").isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d87a80")).finish()), 10)).isTrue();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("d88080")).finish()), 10))
                .as("tag 128").isFalse();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("0100")).finish()), 10))
                .as("trailing CBOR").isFalse();
        assertThat(ok(3, cbor(constant(8).bytes(HexUtil.decodeHexString("f5")).finish()), 10)).isFalse();
    }

    @Test
    void valueConstantsAreCanonical() {
        byte[] a = {0x01};
        byte[] b = {0x02};
        assertThat(ok(3, cbor(constant(13).bit(true).bytes(a).bit(true).bytes(b).integer(5).bit(false).bit(false)
                .finish()), 11)).isTrue();
        assertThat(ok(3, cbor(constant(13).bit(true).bytes(a).bit(true).bytes(b).integer(0).bit(false).bit(false)
                .finish()), 11)).as("zero quantity").isFalse();
        assertThat(ok(3, cbor(constant(13).bit(true).bytes(a).bit(false).bit(false).finish()), 11))
                .as("empty inner map").isFalse();
        assertThat(ok(3, cbor(constant(13).bit(true).bytes(b).bit(true).bytes(a).integer(1).bit(false)
                .bit(true).bytes(a).bit(true).bytes(a).integer(1).bit(false).bit(false).finish()), 11))
                .as("currencies not ascending").isFalse();
        assertThat(ok(3, cbor(constant(13).bit(true).bytes(new byte[33]).bit(true).bytes(a).integer(1).bit(false)
                .bit(false).finish()), 11)).as("key over 32 bytes").isFalse();
    }

    private static List<String> realScripts() throws Exception {
        try (InputStream in = Objects.requireNonNull(PlutusScriptDecoderTest.class.getResourceAsStream(
                "real-plutus-v3-scripts.txt"));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        }
    }
}
