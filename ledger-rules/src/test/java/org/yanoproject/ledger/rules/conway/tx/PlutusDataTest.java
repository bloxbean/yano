package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plutus {@code Data} decoding: strict for the ledger's datums, redeemers and inline datums ({@code decodeFull'}),
 * the leading item only for a script's {@code Data} constants ({@code deserialiseOrFail}); without recursion.
 */
class PlutusDataTest {

    private static void valid(String hex) {
        assertThatCode(() -> PlutusData.validate(HexUtil.decodeHexString(hex))).as(hex).doesNotThrowAnyException();
    }

    private static void invalid(String hex) {
        assertThatThrownBy(() -> PlutusData.validate(HexUtil.decodeHexString(hex))).as(hex)
                .isInstanceOf(TxDecodingException.class);
    }

    @Test
    void decodesPlutusData() {
        valid("d8799f0102ff");                  // constructor 0, indefinite fields
        valid("d9050080");                      // constructor 7 (tag 1280)
        valid("d866820780");                    // tag 102: [index, fields]
        valid("d8669f0780ff");                  // tag 102, indefinite
        valid("bf0102ff");                      // indefinite map
        valid("c249010000000000000000");        // bignum
        valid("5f5840" + "00".repeat(64) + "4100ff");
        invalid("d86682071880");                // tag 102 needs a list of fields
        invalid("d86683078000");                // tag 102 has exactly two elements
        invalid("d8669f078000ff");
        invalid("d8668107");
        invalid("d80249010000000000000000");    // a two-byte tag head is not a bignum to cborg
        invalid("5841" + "00".repeat(65));      // a byte string over 64 bytes
        invalid("d88080");                      // tag 128
        invalid("bf01ff");                      // a break after a map key
        invalid("f5");
    }

    @Test
    void theLedgersDataHasNoTrailingBytesButAScriptConstantMay() {
        invalid("0100");
        invalid("d87980ff");
        // A Data constant ignores whatever follows its first item (deserialiseOrFail), but the item itself is checked.
        assertThatCode(() -> PlutusData.validateFirst(HexUtil.decodeHexString("0100"))).doesNotThrowAnyException();
        assertThatCode(() -> PlutusData.validateFirst(HexUtil.decodeHexString("d87980ff1c")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PlutusData.validateFirst(HexUtil.decodeHexString("5841" + "00".repeat(65) + "00")))
                .isInstanceOf(TxDecodingException.class);
    }

    @Test
    void anInlineDatumIsExactlyOneData() {
        // [{0: [], 1: [{0: addr, 1: 1000000, 2: [1, 24(h'..')]}], 2: 0}, {}, true, null]
        String address = "581d60" + "11".repeat(28);
        String good = tx("a3" + "00" + address + "01" + "1a000f4240" + "02" + "8201d8184100");
        assertThat(RawTransaction.parse(HexUtil.decodeHexString(good), null).outputs().getFirst().inlineDatum())
                .containsExactly(0);
        String trailing = tx("a3" + "00" + address + "01" + "1a000f4240" + "02" + "8201d818420000");
        assertThatThrownBy(() -> RawTransaction.parse(HexUtil.decodeHexString(trailing), null))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("trailing bytes");
    }

    private static String tx(String output) {
        return "84" + "a3" + "0080" + "0181" + output + "0200" + "a0" + "f5" + "f6";
    }

    @Test
    void dataOfAnyDepthIsCheckedWithoutRecursion() {
        int depth = 200_000;
        valid("9f".repeat(depth) + "00" + "ff".repeat(depth));
        valid("d87981".repeat(depth) + "00");
        invalid("81".repeat(depth) + "f5");
    }
}
