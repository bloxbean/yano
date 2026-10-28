package org.yanoproject.tx.gate;

import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawBlockBody;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate re-validates the transactions a block carries, byte for byte: never re-encoded, across the 23/24 boundary
 * where the block's transaction arrays become indefinite-length.
 */
class BlockRevalidatorBlockTxsTest {

    private static final byte[] HEADER = HexUtil.decodeHexString("828040"); // [[], h'']
    private static final String KEYS_OUT_OF_ORDER = "84a3020001800080a0f5f6"; // body {2: 0, 1: [], 0: []}

    @Test
    void readsEachTransactionAsSubmitted() {
        for (int count : new int[]{23, 24}) {
            List<byte[]> submitted = new ArrayList<>();
            submitted.add(HexUtil.decodeHexString(KEYS_OUT_OF_ORDER));
            for (int i = 1; i < count; i++)
                submitted.add(HexUtil.decodeHexString(String.format("84a3008001800219%04xa0f5f6", i)));
            byte[] block = RawBlockBody.of(Era.Conway, submitted).block(HEADER);

            BlockRevalidator.BlockTxs txs = BlockRevalidator.BlockTxs.read(block);

            assertThat(txs.txs()).as("%d transactions", count).hasSize(count);
            for (int i = 0; i < count; i++) {
                assertThat(txs.txs().get(i)).as("tx %d of %d", i, count).isEqualTo(submitted.get(i));
                assertThat(txs.ids().get(i)).as("id %d of %d", i, count)
                        .isEqualTo(HexUtil.encodeHexString(RawTx.of(submitted.get(i)).txId()));
            }
            // blake2b256 of the body as encoded, not as cbor-java would re-encode it (36fdff68…)
            assertThat(txs.ids().getFirst())
                    .isEqualTo("edc0a6d9d982bfb81f6d840af5843165c872d54df5a540cb6a25cb1bc96a606e");
            assertThat(txs.invalid()).isEmpty();
        }
    }
}
