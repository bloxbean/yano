package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The definite-length copy of a transaction's body that the Scalus bridge decodes ({@code ScalusTransactions}). */
class DefiniteLengthCborTest {

    @Test
    void setTagsCanBeDroppedFromTheBody() {
        // [{0: 258([[h'00..', 0]])}, {}, true, null] -> [{0: [[h'00..', 0]]}, {}, true, null]
        String input = "825820" + "00".repeat(32) + "00";
        byte[] tagged = HexUtil.decodeHexString("84a100d9010281" + input + "a0f5f6");
        assertThat(HexUtil.encodeHexString(DefiniteLengthCbor.normalizeBody(tagged, true)))
                .isEqualTo("84a10081" + input + "a0f5f6");
        assertThat(DefiniteLengthCbor.normalizeBody(tagged, false)).isEqualTo(tagged);
    }

    @Test
    void onlyTheBodyIsMadeDefinite() {
        // [{0: [_ input]}, {1: [_ ]}, true, {1: [_ 1]}]: indefinite arrays in the body, the witness set and the metadata.
        String input = "825820" + "00".repeat(32) + "00";
        byte[] tx = HexUtil.decodeHexString("84a1009f" + input + "ffa1019fff" + "f5" + "a1019f01ff");
        assertThat(HexUtil.encodeHexString(DefiniteLengthCbor.normalizeBody(tx, false)))
                .isEqualTo("84a10081" + input + "a1019fff" + "f5" + "a1019f01ff");
    }
}
