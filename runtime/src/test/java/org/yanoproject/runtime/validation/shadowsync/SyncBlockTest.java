package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-056 Phase 7a: transactions reassembled from a block's original segment bytes. */
class SyncBlockTest {

    // A body with non-canonical encodings (an indefinite-length input list, a two-byte uint for the fee): a
    // re-encoding would change the bytes, and so the id.
    static final String BODY_0 = "a3" + "00" + "9f" + "825820" + "11".repeat(32) + "00" + "ff"
            + "01" + "80" + "02" + "1900c8";
    static final String BODY_1 = "a3" + "00" + "81825820" + "22".repeat(32) + "01" + "01" + "80" + "02" + "01";
    static final String BODY_2 = "a3" + "00" + "81825820" + "33".repeat(32) + "02" + "01" + "80" + "02" + "02";
    static final String WITNESS = "a0";
    static final String WITNESS_1 = "a1" + "00" + "80";
    static final String AUX_1 = "a1" + "0a" + "63616263"; // {10: "abc"}

    /** {@code [7, [header, bodies, witnesses, {1: aux}, [2]]]}. */
    static byte[] block() {
        String header = "82" + "80" + "80";
        String bodies = "83" + BODY_0 + BODY_1 + BODY_2;
        String witnesses = "83" + WITNESS + WITNESS_1 + WITNESS;
        String aux = "a1" + "01" + AUX_1;
        String invalid = "81" + "02";
        return HexUtil.decodeHexString("82" + "07" + "85" + header + bodies + witnesses + aux + invalid);
    }

    @Test
    void reassemblesEachTransactionFromTheOriginalSegmentBytes() {
        SyncBlock block = SyncBlock.parse(block());

        assertThat(block.size()).isEqualTo(3);
        assertThat(HexUtil.encodeHexString(block.txs().get(0))).isEqualTo("84" + BODY_0 + WITNESS + "f5" + "f6");
        assertThat(HexUtil.encodeHexString(block.txs().get(1))).isEqualTo("84" + BODY_1 + WITNESS_1 + "f5" + AUX_1);
        assertThat(HexUtil.encodeHexString(block.txs().get(2))).isEqualTo("84" + BODY_2 + WITNESS + "f4" + "f6");
        assertThat(block.txIds().get(0)).isEqualTo(
                HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(BODY_0))));
        assertThat(block.invalidTxs()).containsExactly(2);
        assertThat(block.phase2Invalid(2)).isTrue();
        assertThat(block.phase2Invalid(0)).isFalse();
    }

    @Test
    void theBodySizeAndHashComeFromTheStoredSegmentsAndAreComparedWithTheHeader() {
        String bodies = "83" + BODY_0 + BODY_1 + BODY_2;
        String witnesses = "83" + WITNESS + WITNESS_1 + WITNESS;
        String aux = "a1" + "01" + AUX_1;
        String invalid = "81" + "02";
        long size = (bodies.length() + witnesses.length() + aux.length() + invalid.length()) / 2;
        String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(
                h(bodies) + h(witnesses) + h(aux) + h(invalid))));

        SyncBlock.BodyDigest right = SyncBlock.parse(conwayBlock(size, hash, bodies, witnesses, aux, invalid)).body();
        assertThat(right).isNotNull();
        assertThat(right.actualSize()).isEqualTo(size);
        assertThat(right.actualHash()).isEqualTo(hash);
        assertThat(right.sizeMatches()).isTrue();
        assertThat(right.hashMatches()).isTrue();

        SyncBlock.BodyDigest wrong = SyncBlock.parse(conwayBlock(size + 1, "00".repeat(32), bodies, witnesses, aux,
                invalid)).body();
        assertThat(wrong.sizeMatches()).isFalse();
        assertThat(wrong.hashMatches()).isFalse();

        assertThat(SyncBlock.parse(block()).body()).as("no Babbage/Conway header body").isNull();
    }

    /** {@code [7, [[header_body(10), sig], bodies, witnesses, aux, invalid]]} with the given body size and hash. */
    private static byte[] conwayBlock(long size, String hash, String bodies, String witnesses, String aux,
                                      String invalid) {
        String headerBody = "8a" + "01" + "02" + "f6" + "40" + "40" + "40"
                + "1a" + String.format("%08x", size) + "5820" + hash + "80" + "80";
        String header = "82" + headerBody + "40";
        return HexUtil.decodeHexString("82" + "07" + "85" + header + bodies + witnesses + aux + invalid);
    }

    private static String h(String hex) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(hex)));
    }

    @Test
    void acceptsABareBlockWithoutTheEraEnvelope() {
        byte[] enveloped = block();
        byte[] bare = new byte[enveloped.length - 2];
        System.arraycopy(enveloped, 2, bare, 0, bare.length);

        assertThat(SyncBlock.parse(bare).txIds()).isEqualTo(SyncBlock.parse(enveloped).txIds());
    }

    @Test
    void rejectsBytesThatAreNotABlock() {
        assertThatThrownBy(() -> SyncBlock.parse(HexUtil.decodeHexString("8201")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyncBlock.parse(HexUtil.decodeHexString("ff")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
