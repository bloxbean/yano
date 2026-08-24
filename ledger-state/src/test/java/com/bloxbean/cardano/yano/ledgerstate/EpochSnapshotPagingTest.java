package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-039 epoch-stake artifact streams the delegation snapshot page by page, and the sink
 * refuses a commit whose row count does not match what the artifact declared. So a paging cursor
 * that skipped or repeated a row would not corrupt the archive quietly - it would deadlock the
 * drain, and only on epochs large enough to need more than one page.
 */
class EpochSnapshotPagingTest {

    @TempDir Path tempDir;

    private static final int EPOCH = 42;

    private static byte[] snapshotKey(int epoch, int index) {
        byte[] hash = new byte[28];
        // Big-endian index in the tail keeps RocksDB key order equal to insertion order.
        hash[26] = (byte) (index >>> 8);
        hash[27] = (byte) index;
        byte[] key = new byte[4 + 1 + hash.length];
        System.arraycopy(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array(),
                0, key, 0, 4);
        key[4] = 0;
        System.arraycopy(hash, 0, key, 5, hash.length);
        return key;
    }

    @Test
    void pagingReturnsEveryRowExactlyOnceAcrossPageBoundaries() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(EpochSnapshotPagingTest.class), true, null);

            int total = 250;
            try (var batch = new WriteBatch(); var options = new WriteOptions()) {
                for (int i = 0; i < total; i++) {
                    batch.put(rocks.cfSupplier().handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT),
                            snapshotKey(EPOCH, i),
                            AccountStateCborCodec.encodeEpochDelegSnapshot("ab".repeat(28),
                                    BigInteger.valueOf(1_000 + i)));
                }
                // A neighbouring epoch must not leak into the scan.
                batch.put(rocks.cfSupplier().handle(AccountStateCfNames.EPOCH_DELEG_SNAPSHOT),
                        snapshotKey(EPOCH + 1, 0),
                        AccountStateCborCodec.encodeEpochDelegSnapshot("cd".repeat(28), BigInteger.ONE));
                rocks.db().write(options, batch);
            }

            // A page size that does not divide the total, so the last page is partial too.
            int pageSize = 37;
            List<BigInteger> amounts = new ArrayList<>();
            var seen = new HashSet<String>();
            byte[] cursor = null;
            int pages = 0;
            do {
                var page = store.readEpochDelegSnapshotPage(EPOCH, cursor, pageSize);
                pages++;
                for (var row : page.rows()) {
                    amounts.add(row.amount());
                    assertThat(seen.add(java.util.HexFormat.of().formatHex(row.credentialHash())))
                            .as("no row may be returned twice").isTrue();
                }
                cursor = page.nextKey();
            } while (cursor != null && pages < 100);

            assertThat(pages).as("the page size must actually force multiple pages").isGreaterThan(1);
            assertThat(amounts)
                    .as("every row exactly once, no row dropped at a page boundary")
                    .hasSize(total);
            // Values prove ordering and identity, not just the count.
            assertThat(amounts.get(0)).isEqualTo(BigInteger.valueOf(1_000));
            assertThat(amounts.get(total - 1)).isEqualTo(BigInteger.valueOf(1_000 + total - 1));
        }
    }
}
