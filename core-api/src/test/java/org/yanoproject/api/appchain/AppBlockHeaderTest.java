package org.yanoproject.api.appchain;

import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.consensus.ConsensusDigests;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppBlockHeaderTest {
    @Test void completeHeaderReproducesFullBlockAndCommitDigestsAcrossViews() {
        for (int view = 0; view < 10; view++) {
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "header-é", 7, filled(1), view,
                    filled(2), 42, filled(3), 1234, filled(4), filled(5), List.of(), filled(6),
                    view == 0 ? new byte[0] : new byte[]{1, 2, (byte) view}, FinalityCert.empty());
            AppBlockHeader header = AppBlockHeader.from(block);
            assertThat(header.blockHash()).isEqualTo(AppBlockCodec.blockHash(block));
            assertThat(header.valueHash()).isEqualTo(AppBlockCodec.valueHash(block));
            assertThat(header.commitDigest()).isEqualTo(ConsensusDigests.commit(block));
            assertThat(header.commitDigest()).isNotEqualTo(header.blockHash());
            assertThat(header.commitDigest()).isNotEqualTo(ConsensusDigests.prepare(block));
            header.stateRoot()[0] = 99;
            header.justificationDigest()[0] = 99;
            assertThat(header.blockHash()).isEqualTo(AppBlockCodec.blockHash(block));
        }
    }

    @Test void rejectsIncompleteOrMalformedCurrentHeaders() {
        assertThatThrownBy(() -> new AppBlockHeader(AppBlock.BLOCK_VERSION, "chain", 1,
                new byte[0], 0, filled(1), 0, new byte[0], 0, filled(2), filled(3), filled(4), filled(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppBlockHeader(1, "chain", 1,
                filled(0), 0, filled(1), 0, new byte[0], 0, filled(2), filled(3), filled(4), filled(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
