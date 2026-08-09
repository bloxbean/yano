package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalEpochStateViewTest {
    @TempDir Path tempDir;

    @Test
    void iteratesOnePinnedSnapshotInCanonicalCredentialOrder() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            put(rocks, 41, 1, 2, 20);
            put(rocks, 42, 1, 2, 200);
            put(rocks, 42, 0, 9, 90);
            put(rocks, 42, 0, 1, 10);
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);

            List<String> entries = new ArrayList<>();
            try (HistoricalEpochStateView view = store.openHistoricalEpochStateView()) {
                assertThat(view.hasStakeSnapshot(42)).isTrue();
                assertThat(view.hasStakeSnapshot(43)).isFalse();
                view.forEachStakeEntry(42, (type, hash, coin, pool) -> entries.add(
                        type + ":" + (hash[27] & 0xff) + ":" + coin + ":" + (pool[27] & 0xff)));
            }

            assertThat(entries).containsExactly("0:1:10:1", "0:9:90:9", "1:2:200:2");
        }
    }

    @Test
    void heldRocksSnapshotIsStableAndCloseGuarded() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            put(rocks, 42, 0, 1, 10);
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            HistoricalEpochStateView view = store.openHistoricalEpochStateView();
            put(rocks, 42, 0, 2, 20);

            List<Integer> hashes = new ArrayList<>();
            view.forEachStakeEntry(42, (type, hash, coin, pool) -> hashes.add(hash[27] & 0xff));
            view.close();

            assertThat(hashes).containsExactly(1);
            assertThatThrownBy(() -> view.hasStakeSnapshot(42))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }

    private static void put(TestRocksDBHelper rocks, int epoch, int type,
                            int suffix, long amount) throws Exception {
        byte[] key = new byte[33];
        ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        key[4] = (byte) type;
        key[32] = (byte) suffix;
        rocks.db().put(rocks.cfSnapshot(), key,
                AccountStateCborCodec.encodeEpochDelegSnapshot(
                        "%056x".formatted(suffix), BigInteger.valueOf(amount)));
    }
}
