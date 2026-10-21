package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CborItemsTest {

    private static int length(String hex) {
        return CborItems.skip(HexUtil.decodeHexString(hex), 0);
    }

    private static final String[] WELL_FORMED = {
            "00", "17", "1818", "190100", "1a00010000", "1b0000000100000000", // unsigned ints
            "20", "3903e7",                                                  // negative ints
            "40", "4401020304", "6161", "7f616161626163ff", "5f41014102ff",  // strings, chunked strings
            "80", "83010203", "9f0102ff", "9f9f80ffff",                      // arrays
            "a0", "a201020304", "bf0102ff", "bf61610161629f01ffff",          // maps
            "c11a514b67b0", "d9010283010203", "d87980",                      // tags
            "f4", "f5", "f6", "f7", "f820", "f93c00", "fa47c35000", "fb3ff199999999999a" // simple, floats
    };

    private static final String[] MALFORMED = {
            "",           // empty
            "18",         // missing argument byte
            "430102",     // byte string shorter than declared
            "8301",       // array missing items
            "a101",       // map missing value
            "9f01",       // indefinite array without break
            "ff",         // stray break
            "8201ff",     // break inside a definite array
            "1c",         // reserved additional information
            "3f",         // indefinite negative integer
            "c1",         // tag without content
            "5b8000000000000000" // length overflowing a signed long
    };

    @Test
    void skipsWholeItem() {
        for (String hex : WELL_FORMED) {
            assertThat(length(hex)).as(hex).isEqualTo(hex.length() / 2);
            // Trailing bytes after the item are not consumed.
            assertThat(length(hex + "00")).as(hex).isEqualTo(hex.length() / 2);
        }
    }

    @Test
    void skipsFromOffset() {
        byte[] data = HexUtil.decodeHexString("83" + "01" + "8201" + "02" + "03");

        assertThat(CborItems.skip(data, 2)).isEqualTo(5);
    }

    @Test
    void rejectsMalformed() {
        for (String hex : MALFORMED) {
            assertThatThrownBy(() -> length(hex)).as(hex).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void deepNestingDoesNotOverflowTheStack() {
        int depth = 200_000;
        byte[] data = new byte[depth + 1];
        Arrays.fill(data, 0, depth, (byte) 0x81);
        data[depth] = 0x00;

        assertThat(CborItems.skip(data, 0)).isEqualTo(depth + 1);
    }
}
