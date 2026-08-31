package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceActionType;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatus;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatusReason;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.ProposalLifecycleRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

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

    @Test
    void exposesCanonicalGovernanceSnapshotsIncludingExplicitEmptyPresence() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            var governance = rocks.governanceStore();
            var delta = new ArrayList<DefaultAccountStateStore.DeltaOp>();
            var proposals = new LinkedHashMap<GovActionId, ProposalLifecycleRecord>();
            proposals.put(new GovActionId("22".repeat(32), 1), lifecycle(GovernanceProposalStatus.ACTIVE));
            proposals.put(new GovActionId("11".repeat(32), 2), lifecycle(GovernanceProposalStatus.RATIFIED));
            try (var batch = new org.rocksdb.WriteBatch(); var options = new org.rocksdb.WriteOptions()) {
                governance.storeProposalLifecycleSnapshot(42, proposals, batch, delta);
                governance.storeDRepDistributionSnapshotMarker(42, batch, delta);
                rocks.db().write(options, batch);
            }
            List<String> seen = new ArrayList<>();
            try (HistoricalEpochStateView view = store.openHistoricalEpochStateView()) {
                assertThat(view.hasProposalStatusSnapshot(42)).isTrue();
                assertThat(view.hasDRepDistributionSnapshot(42)).isTrue();
                view.forEachProposalStatus(42, (tx, index, action, status, reason, proposed, expires) ->
                        seen.add((tx[0] & 0xff) + ":" + index + ":" + status));
                List<String> dreps = new ArrayList<>();
                view.forEachDRepDistributionEntry(42, (type, hash, coin) -> dreps.add("unexpected"));
                assertThat(dreps).isEmpty();
            }
            assertThat(seen).containsExactly("17:2:RATIFIED", "34:1:ACTIVE");
        }
    }

    private static ProposalLifecycleRecord lifecycle(GovernanceProposalStatus status) {
        return new ProposalLifecycleRecord(GovernanceActionType.PARAMETER_CHANGE, status,
                status == GovernanceProposalStatus.RATIFIED
                        ? GovernanceProposalStatusReason.RATIFIED : GovernanceProposalStatusReason.NONE,
                40, 45);
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
